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

import org.apache.paimon.globalindex.EsSearchQuery
import org.apache.paimon.globalindex.EsSearchQuery.{integer, only, text}

import scala.collection.JavaConverters._

/** Single-target SEARCH subset of the version-1 paimon_es_search envelope. */
case class SparkEsSearchRequest(
    target: String,
    database: String,
    table: String,
    snapshotId: Option[Long],
    indexFields: Seq[Int],
    bindings: Map[String, Int],
    query: EsSearchQuery,
    from: Int,
    size: Int,
    fetchSource: Boolean)
  extends Serializable

object SparkEsSearchRequest {
  def parse(json: String): SparkEsSearchRequest = {
    val root = EsSearchQuery.parse(json)
    only(root, "version", "targets", "operation", "request", "options")
    integer(root.get("version"), 1, 1)
    require(
      text(root.get("operation")).equalsIgnoreCase("SEARCH"),
      "ESM1 paimon_es_search currently supports SEARCH only")
    if (root.has("options")) {
      val options = root.get("options")
      only(options, "scoring")
      if (options.has("scoring")) {
        require(
          text(options.get("scoring")).equalsIgnoreCase("QUERY_THEN_FETCH"),
          "ESM1 supports shard-local QUERY_THEN_FETCH scoring only")
      }
    }
    val targets = root.get("targets")
    require(
      targets != null && targets.isArray && targets.size() == 1,
      "ESM1 paimon_es_search requires exactly one target")
    val target = targets.get(0)
    only(target, "name", "database", "table", "branch", "snapshot", "indexes", "bindings")
    require(
      !target.has("branch") || text(target.get("branch")) == "main",
      "This ESM1 entry currently supports the main branch only")
    val indexes = target.get("indexes")
    require(
      indexes != null && indexes.isArray && indexes.size() == 1 &&
        indexes.get(0).isArray && indexes.get(0).size() > 0 && indexes.get(0).size() <= 1024,
      "Select exactly one nonempty grouped ESM1 index"
    )
    val ids = indexes.get(0).elements().asScala.map(integer(_, 0, Int.MaxValue)).toVector
    require(ids.distinct.size == ids.size, "Duplicate index field IDs")
    val aliases = target.get("bindings")
    require(
      aliases != null && aliases.isObject && aliases.size() <= 1024,
      "Expected bounded field bindings")
    val bindings = aliases
      .fields()
      .asScala
      .map {
        entry =>
          val id = integer(entry.getValue, 0, Int.MaxValue)
          require(ids.contains(id), "Binding refers to a field outside the selected index")
          entry.getKey -> id
      }
      .toMap
    val snapshotId =
      if (!target.has("snapshot")) None
      else {
        val selector = target.get("snapshot")
        only(selector, "kind", "snapshot_id", "tag")
        require(
          selector.has("snapshot_id") && selector.has("tag") && selector.get("tag").isNull,
          "Use an explicit snapshot ID or latest; tags are not supported by this entry"
        )
        text(selector.get("kind")) match {
          case "latest" =>
            require(selector.get("snapshot_id").isNull, "latest cannot include a snapshot ID")
            None
          case "snapshot" =>
            val value = text(selector.get("snapshot_id"))
            require(value.matches("[1-9][0-9]*"), "Snapshot IDs are positive decimal strings")
            val id = value.toLong
            require(id > 0, "Snapshot ID must be positive")
            Some(id)
          case _ => throw new IllegalArgumentException("Unsupported snapshot selector")
        }
      }
    val request = root.get("request")
    only(request, "query", "from", "size", "fetch")
    val from = if (request.has("from")) integer(request.get("from"), 0, 9999) else 0
    val size = if (request.has("size")) integer(request.get("size"), 1, 10000 - from) else 10
    require(from + size <= 10000, "Search window exceeds 10000")
    require(request.has("query"), "Missing query")
    val query = new EsSearchQuery(request.get("query").toString, from + size)
    require(query.fields().asScala.forall(bindings.contains), "Every query field needs a binding")
    val fetch =
      if (request.has("fetch")) text(request.get("fetch")).toUpperCase(java.util.Locale.ROOT)
      else "NONE"
    require(fetch == "NONE" || fetch == "SOURCE", "fetch must be NONE or SOURCE")
    SparkEsSearchRequest(
      text(target.get("name")),
      text(target.get("database")),
      text(target.get("table")),
      snapshotId,
      ids,
      bindings,
      query,
      from,
      size,
      fetch == "SOURCE")
  }
}
