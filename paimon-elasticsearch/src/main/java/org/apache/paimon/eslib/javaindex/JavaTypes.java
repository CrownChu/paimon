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

package org.apache.paimon.eslib.javaindex;

import org.apache.paimon.eslib.internal.ArchiveDataProvider;
import org.apache.paimon.eslib.internal.model.FieldIndexConfig;
import org.apache.paimon.eslib.internal.model.FullTextParams;
import org.apache.paimon.eslib.internal.model.FullTextQuerySpec;
import org.apache.paimon.eslib.internal.model.IndexFilter;
import org.apache.paimon.eslib.internal.model.ScalarPredicate;
import org.apache.paimon.eslib.internal.model.SearchResult;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Explicit conversion at the optional Java backend boundary. */
final class JavaTypes {
    private JavaTypes() {}

    static Map<String, org.elasticsearch.eslib.api.model.FieldIndexConfig> fields(
            Map<String, FieldIndexConfig> fields) {
        Map<String, org.elasticsearch.eslib.api.model.FieldIndexConfig> result =
                new LinkedHashMap<>();
        fields.forEach(
                (name, value) ->
                        result.put(
                                name,
                                org.elasticsearch.eslib.api.model.FieldIndexConfig.builder(
                                                name,
                                                org.elasticsearch.eslib.api.model.FieldIndexConfig
                                                        .IndexType.valueOf(
                                                        value.indexType().name()))
                                        .algorithm(
                                                value.algorithm() == null
                                                        ? null
                                                        : org.elasticsearch.eslib.api.model
                                                                .VectorAlgorithm.valueOf(
                                                                value.algorithm().name()))
                                        .dimension(value.dimension())
                                        .metric(value.metric())
                                        .algorithmParams(value.algorithmParams())
                                        .analyzer(
                                                value.analyzer() == null
                                                        ? null
                                                        : org.elasticsearch.eslib.api.model
                                                                .BuiltinAnalyzer.valueOf(
                                                                value.analyzer().name()))
                                        .scalarType(
                                                value.scalarType() == null
                                                        ? null
                                                        : org.elasticsearch.eslib.api.model
                                                                .ScalarFieldType.valueOf(
                                                                value.scalarType().name()))
                                        .build()));
        return result;
    }

    static org.elasticsearch.eslib.api.ArchiveDataProvider provider(ArchiveDataProvider value) {
        return new org.elasticsearch.eslib.api.ArchiveDataProvider() {
            @Override
            public byte[] readRange(long offset, int length) throws IOException {
                return value.readRange(offset, length);
            }

            @Override
            public org.elasticsearch.eslib.api.ArchiveDataProvider fork() throws IOException {
                return provider(value.fork());
            }

            @Override
            public void close() throws IOException {
                value.close();
            }
        };
    }

    static SearchResult result(org.elasticsearch.eslib.api.model.SearchResult value) {
        return new SearchResult(value.ids, value.scores, value.count);
    }

    static org.elasticsearch.eslib.api.model.FullTextParams params(FullTextParams value) {
        return new org.elasticsearch.eslib.api.model.FullTextParams(
                org.elasticsearch.eslib.api.model.FullTextParams.Operator.valueOf(
                        value.operator().name()),
                value.boost(),
                value.fuzziness(),
                value.maxExpansions(),
                value.prefixLength());
    }

    static org.elasticsearch.eslib.api.model.FullTextQuerySpec text(FullTextQuerySpec value) {
        if (value instanceof FullTextQuerySpec.Match) {
            FullTextQuerySpec.Match q = (FullTextQuerySpec.Match) value;
            return new org.elasticsearch.eslib.api.model.FullTextQuerySpec.Match(
                    q.field(), q.text(), params(q.params()));
        }
        if (value instanceof FullTextQuerySpec.Phrase) {
            FullTextQuerySpec.Phrase q = (FullTextQuerySpec.Phrase) value;
            return new org.elasticsearch.eslib.api.model.FullTextQuerySpec.Phrase(
                    q.field(), q.text(), q.slop());
        }
        if (value instanceof FullTextQuerySpec.Bool) {
            FullTextQuerySpec.Bool q = (FullTextQuerySpec.Bool) value;
            return new org.elasticsearch.eslib.api.model.FullTextQuerySpec.Bool(
                    texts(q.must()), texts(q.should()), texts(q.mustNot()));
        }
        if (value instanceof FullTextQuerySpec.Boost) {
            FullTextQuerySpec.Boost q = (FullTextQuerySpec.Boost) value;
            return new org.elasticsearch.eslib.api.model.FullTextQuerySpec.Boost(
                    text(q.positive()), text(q.negative()), q.negativeBoost());
        }
        throw new IllegalArgumentException("Unsupported text query: " + value.getClass().getName());
    }

    private static List<org.elasticsearch.eslib.api.model.FullTextQuerySpec> texts(
            List<FullTextQuerySpec> values) {
        return values.stream().map(JavaTypes::text).collect(Collectors.toList());
    }

    static org.elasticsearch.eslib.api.model.ScalarPredicate predicate(ScalarPredicate value) {
        switch (value.op()) {
            case EQUAL:
                return org.elasticsearch.eslib.api.model.ScalarPredicate.eq(value.value());
            case NOT_EQUAL:
                return org.elasticsearch.eslib.api.model.ScalarPredicate.neq(value.value());
            case LESS_THAN:
                return org.elasticsearch.eslib.api.model.ScalarPredicate.lt(value.value());
            case LESS_OR_EQUAL:
                return org.elasticsearch.eslib.api.model.ScalarPredicate.lte(value.value());
            case GREATER_THAN:
                return org.elasticsearch.eslib.api.model.ScalarPredicate.gt(value.value());
            case GREATER_OR_EQUAL:
                return org.elasticsearch.eslib.api.model.ScalarPredicate.gte(value.value());
            case IN:
                return org.elasticsearch.eslib.api.model.ScalarPredicate.in(value.inValues());
            case NOT_IN:
                return org.elasticsearch.eslib.api.model.ScalarPredicate.notIn(value.inValues());
            case RANGE:
                return org.elasticsearch.eslib.api.model.ScalarPredicate.range(
                        value.value(), value.upperValue());
            default:
                throw new IllegalArgumentException("Unsupported scalar operation: " + value.op());
        }
    }

    static org.elasticsearch.eslib.api.model.IndexFilter filter(IndexFilter value) {
        if (value instanceof IndexFilter.ScalarFilter) {
            return org.elasticsearch.eslib.api.model.IndexFilter.scalar(
                    predicate(((IndexFilter.ScalarFilter) value).predicate()));
        }
        if (value instanceof IndexFilter.TextFilter) {
            IndexFilter.TextFilter q = (IndexFilter.TextFilter) value;
            return org.elasticsearch.eslib.api.model.IndexFilter.text(
                    org.elasticsearch.eslib.api.model.IndexFilter.TextFilter.TextOp.valueOf(
                            q.op().name()),
                    q.value());
        }
        if (value instanceof IndexFilter.ExistsFilter) {
            return ((IndexFilter.ExistsFilter) value).mustExist()
                    ? org.elasticsearch.eslib.api.model.IndexFilter.exists()
                    : org.elasticsearch.eslib.api.model.IndexFilter.notExists();
        }
        if (value instanceof IndexFilter.GeoFilter) {
            IndexFilter.GeoFilter q = (IndexFilter.GeoFilter) value;
            return q.op() == IndexFilter.GeoFilter.GeoOp.DISTANCE
                    ? org.elasticsearch.eslib.api.model.IndexFilter.geoDistance(
                            q.lat(), q.lon(), q.radiusMeters())
                    : org.elasticsearch.eslib.api.model.IndexFilter.geoBoundingBox(
                            q.minLat(), q.maxLat(), q.minLon(), q.maxLon());
        }
        throw new IllegalArgumentException("Unsupported filter: " + value.getClass().getName());
    }
}
