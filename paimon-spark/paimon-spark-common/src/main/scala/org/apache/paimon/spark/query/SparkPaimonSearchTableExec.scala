/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.spark.query

import org.apache.paimon.globalindex.EsIndexSearch
import org.apache.paimon.globalindex.EsIndexSearch.Hit
import org.apache.paimon.spark.SparkTable
import org.apache.paimon.table.FileStoreTable
import org.apache.paimon.utils.JsonSerdeUtil

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, GenericInternalRow, UnsafeProjection}
import org.apache.spark.sql.connector.catalog.{Identifier, TableCatalog}
import org.apache.spark.sql.execution.LeafExecNode
import org.apache.spark.unsafe.types.UTF8String

import java.util.UUID
import java.util.function.Function

import scala.collection.JavaConverters._

/** Native shard queries run on executors; the driver merges a bounded global score window. */
case class SparkPaimonSearchTableExec(
    catalogName: String,
    envelope: String,
    override val output: Seq[Attribute])(@transient private val sparkSession: SparkSession)
  extends LeafExecNode {

  override protected def otherCopyArgs: Seq[AnyRef] = Seq(sparkSession)

  override protected def doExecute(): RDD[InternalRow] = {
    val request = SparkEsSearchRequest.parse(envelope)
    val catalog = sparkSession.sessionState.catalogManager.catalog(catalogName) match {
      case value: TableCatalog => value
      case _ => throw new IllegalArgumentException("Expected a Paimon table catalog")
    }
    val original = catalog.loadTable(Identifier.of(Array(request.database), request.table)) match {
      case SparkTable(value: FileStoreTable) => value
      case _ =>
        throw new IllegalArgumentException("paimon_es_search requires a Paimon file-store table")
    }
    val table = request.snapshotId
      .map(id => original.copy(Map("scan.snapshot-id" -> id.toString).asJava))
      .getOrElse(original)
    val plan = EsIndexSearch.plan(table, request.indexFields.map(Int.box).asJava)
    val fields =
      plan.table.rowType().getFields.asScala.map(field => field.id() -> field.name()).toMap
    val query = request.query.mapFields(new Function[String, String] {
      override def apply(alias: String): String = fields(request.bindings(alias))
    })
    val sc = sparkSession.sparkContext
    val shards = plan.shards.asScala.toVector
    implicit val order: Ordering[Hit] = Ordering.by(hit => (-hit.score, hit.rowId))
    val selected = sc
      .parallelize(shards, math.min(shards.size, math.max(1, sc.defaultParallelism)))
      .flatMap(shard => shard.search(query).asScala)
      .takeOrdered(query.limit())
      .drop(request.from)
      .take(request.size)
    require(
      selected.map(_.rowId).distinct.length == selected.length,
      "Repeated global result row ID")
    val source =
      if (request.fetchSource)
        plan
          .fetch(selected.toSeq.asJava)
          .asScala
          .map { case (id, bytes) => id.longValue() -> bytes }
          .toMap
      else Map.empty[Long, Array[Byte]]
    val sourceSchema = if (request.fetchSource) JsonSerdeUtil.toJson(plan.sourceType()) else null
    val queryId = UUID.randomUUID().toString
    val metadata = JsonSerdeUtil.OBJECT_MAPPER_INSTANCE.createObjectNode()
    metadata.put("snapshotId", plan.snapshotId.toString)
    metadata.put("indexType", "es-index")
    metadata.put("coverageComplete", true)
    metadata.put("scoring", "QUERY_THEN_FETCH")
    metadata.put("totalHitsTracked", false)
    metadata.put("hitCount", selected.length)
    val metadataJson = metadata.toString
    def string(value: String): UTF8String =
      if (value == null) null else UTF8String.fromString(value)
    def record(hit: Option[Hit], rank: Int): InternalRow = {
      val values = new Array[Any](17)
      values(0) = string(if (hit.isDefined) "HIT" else "PAGE_END")
      values(1) = string(queryId)
      values(2) = 0
      values(3) = string(queryId)
      values(4) = string(request.target)
      values(5) = string(request.database + "." + request.table)
      values(6) = string("main")
      values(7) = plan.snapshotId
      hit.foreach {
        value =>
          values(8) = value.rowId
          values(9) = value.score
          values(10) = rank.toLong
          values(12) = string(sourceSchema)
          values(13) = source.getOrElse(value.rowId, null)
          values(14) = if (request.fetchSource) string("paimon-internal-row-v1") else null
      }
      values(15) = string(metadataJson)
      new GenericInternalRow(values)
    }
    val projection = UnsafeProjection.create(output.map(_.dataType).toArray)
    val rows = selected.zipWithIndex.map {
      case (hit, position) =>
        projection(record(Some(hit), request.from + position + 1)).copy(): InternalRow
    }.toVector :+ projection(record(None, 0)).copy()
    sc.parallelize(rows, 1)
  }
}
