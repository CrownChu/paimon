/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import org.apache.paimon.eslib.internal.model.FieldIndexConfig;
import org.apache.paimon.eslib.internal.model.FullTextParams;
import org.apache.paimon.eslib.internal.model.FullTextQuerySpec;
import org.apache.paimon.eslib.internal.model.IndexFilter;
import org.apache.paimon.eslib.internal.model.ScalarFieldType;
import org.apache.paimon.eslib.internal.model.ScalarPredicate;
import org.apache.paimon.eslib.internal.model.VectorAlgorithm;

import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeProtocolTest {
    @Test
    void cpuGateRejectsAnOlderCoreBeforeLoadingNativeCode() throws Exception {
        String flags =
                "flags : cx16 lahf_lm popcnt pni ssse3 sse4_1 sse4_2 avx avx2 bmi1 bmi2 f16c fma"
                        + " movbe xsave abm\n";
        NativeRuntime.requireV3Cpu(flags);
        assertThrows(
                IOException.class, () -> NativeRuntime.requireV3Cpu(flags.replace("avx2", "")));
        assertThrows(IOException.class, () -> NativeRuntime.requireV3Cpu(flags + "flags : sse2\n"));
        assertThrows(IOException.class, () -> NativeRuntime.requireV3Cpu("processor : 0\n"));
    }

    @Test
    void scalarNegativePredicatesExcludeMissingFieldsLikeTheJavaBackend() throws Exception {
        NativeQueries queries = new NativeQueries(new NativeMapping(fields()));
        for (ScalarPredicate predicate :
                new ScalarPredicate[] {
                    ScalarPredicate.neq(7L), ScalarPredicate.notIn(Collections.singletonList(7L))
                }) {
            JsonNode query =
                    NativeJson.parse(
                            NativeJson.encode(queries.filter("id", IndexFilter.scalar(predicate))));
            assertEquals(
                    "id", query.path("bool").path("must").path("exists").path("field").asText());
            assertTrue(query.path("bool").has("must_not"));
        }
    }

    static Map<String, FieldIndexConfig> fields() {
        Map<String, FieldIndexConfig> fields = new LinkedHashMap<>();
        fields.put(
                "title",
                FieldIndexConfig.builder("title", FieldIndexConfig.IndexType.FULLTEXT).build());
        fields.put(
                "id",
                FieldIndexConfig.builder("id", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.LONG)
                        .build());
        fields.put(
                "vector",
                FieldIndexConfig.builder("vector", FieldIndexConfig.IndexType.VECTOR)
                        .algorithm(VectorAlgorithm.HNSW)
                        .dimension(2)
                        .metric("l2")
                        .build());
        return fields;
    }

    @Test
    void cHeaderLp64LayoutAndRollbackConstant() throws Exception {
        NativeClient.verifyLayouts();
        assertEquals(2, new ElasticppLibrary.CreateOptions().closeMode);
        assertEquals(1, new ElasticppLibrary.CreateOptions().indexSortById);
    }

    @Test
    void mappingIsStableAcrossMapOrderAndDisablesSource() throws Exception {
        Map<String, FieldIndexConfig> reverse = new LinkedHashMap<>();
        reverse.put("vector", fields().get("vector"));
        reverse.put("id", fields().get("id"));
        reverse.put("title", fields().get("title"));
        NativeMapping first = new NativeMapping(fields()), second = new NativeMapping(reverse);
        assertArrayEquals(first.mapping, second.mapping);
        assertArrayEquals(first.config, second.config);
        JsonNode mapping = NativeJson.parse(first.mapping);
        assertFalse(mapping.path("_source").path("enabled").asBoolean());
        assertEquals("long", mapping.path("properties").path("id").path("type").asText());
        assertEquals(
                "l2_norm", mapping.path("properties").path("vector").path("similarity").asText());
    }

    @Test
    void unsupportedAlgorithmNeverChangesToFloatHnsw() {
        for (VectorAlgorithm algorithm : new VectorAlgorithm[] {VectorAlgorithm.NATIVE}) {
            FieldIndexConfig field =
                    FieldIndexConfig.builder("v", FieldIndexConfig.IndexType.VECTOR)
                            .algorithm(algorithm)
                            .dimension(2)
                            .build();
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> new NativeMapping(Collections.singletonMap("v", field)));
        }
    }

    @Test
    void quantizedAlgorithmsKeepDistinctPhysicalFormatsAndArchiveIdentities() throws Exception {
        byte[] previous = null;
        for (VectorAlgorithm algorithm :
                new VectorAlgorithm[] {
                    VectorAlgorithm.HNSW, VectorAlgorithm.INT8_HNSW, VectorAlgorithm.DISKBBQ
                }) {
            FieldIndexConfig field =
                    FieldIndexConfig.builder("v", FieldIndexConfig.IndexType.VECTOR)
                            .algorithm(algorithm)
                            .dimension(33)
                            .metric("l2")
                            .build();
            NativeMapping mapping = new NativeMapping(Collections.singletonMap("v", field));
            String expected =
                    algorithm == VectorAlgorithm.HNSW
                            ? "hnsw"
                            : algorithm == VectorAlgorithm.INT8_HNSW ? "int8_hnsw" : "bbq_disk";
            assertEquals(
                    expected,
                    NativeJson.parse(mapping.mapping)
                            .path("properties")
                            .path("v")
                            .path("index_options")
                            .path("type")
                            .asText());
            assertEquals(
                    algorithm.name(),
                    NativeJson.parse(mapping.config).path("v").path("algorithm").asText());
            if (previous != null) {
                assertFalse(java.util.Arrays.equals(previous, mapping.config));
            }
            previous = mapping.config;
        }
    }

    @Test
    void malformedUnicodeAndReservedFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> NativeJson.utf8("\ud800"));
        FieldIndexConfig field =
                FieldIndexConfig.builder("_id", FieldIndexConfig.IndexType.KEYWORD).build();
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeMapping(Collections.singletonMap("_id", field)));
    }

    @Test
    void int64PredicateSurvivesJsonBeyondDoublePrecision() throws Exception {
        Object query =
                new NativeQueries(new NativeMapping(fields()))
                        .filter("id", IndexFilter.scalar(ScalarPredicate.eq(9007199254740993L)));
        JsonNode value = NativeJson.parse(NativeJson.encode(query)).path("term").path("id");
        assertTrue(value.isIntegralNumber());
        assertEquals(9007199254740993L, value.longValue());
    }

    @Test
    void rangePreservesInclusiveAndExclusiveBounds() throws Exception {
        NativeQueries queries = new NativeQueries(new NativeMapping(fields()));
        assertEquals(
                7L,
                NativeJson.parse(
                                NativeJson.encode(
                                        queries.filter(
                                                "id", IndexFilter.scalar(ScalarPredicate.lt(7L)))))
                        .path("range")
                        .path("id")
                        .path("lt")
                        .longValue());
        JsonNode range =
                NativeJson.parse(
                                NativeJson.encode(
                                        queries.filter(
                                                "id",
                                                IndexFilter.scalar(ScalarPredicate.range(3L, 9L)))))
                        .path("range")
                        .path("id");
        assertEquals(3L, range.path("gte").longValue());
        assertEquals(9L, range.path("lte").longValue());
    }

    @Test
    void notExistsAndPureNegativeKeepTheExplicitLogicalUniverse() throws Exception {
        NativeQueries queries = new NativeQueries(new NativeMapping(fields()));
        JsonNode notExists =
                NativeJson.parse(
                        NativeJson.encode(queries.filter("title", IndexFilter.notExists())));
        assertTrue(notExists.path("bool").path("must").has("match_all"));
        assertEquals(
                "title",
                notExists.path("bool").path("must_not").path("exists").path("field").asText());
        FullTextQuerySpec negative =
                new FullTextQuerySpec.Bool(
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.singletonList(
                                new FullTextQuerySpec.Match("title", "alpha", null)));
        assertTrue(
                NativeJson.parse(NativeJson.encode(queries.fulltext(negative)))
                        .path("bool")
                        .path("must")
                        .has("match_all"));
    }

    @Test
    void fuzzyAndGeoAreExplicitlyUnsupported() throws Exception {
        NativeQueries queries = new NativeQueries(new NativeMapping(fields()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> queries.filter("title", IndexFilter.TextFilter.fuzzy("alpha")));
        FullTextParams params = new FullTextParams(FullTextParams.Operator.OR, 1.0f, 1, 50, 0);
        assertThrows(
                UnsupportedOperationException.class,
                () -> queries.fulltext(new FullTextQuerySpec.Match("title", "alpha", params)));
    }

    @Test
    void vectorShapeNonfiniteAndCosineZeroAreRejected() {
        FieldIndexConfig vector = fields().get("vector");
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeMapping.requireVector(vector, new float[] {1}));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeMapping.requireVector(vector, new float[] {Float.NaN, 1}));
        FieldIndexConfig cosine =
                FieldIndexConfig.builder("v", FieldIndexConfig.IndexType.VECTOR)
                        .algorithm(VectorAlgorithm.HNSW)
                        .dimension(2)
                        .metric("cosine")
                        .build();
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeMapping.requireVector(cosine, new float[] {0, 0}));
    }

    @Test
    void metadataDetectsTamperedMappingAndUnsafeNames() throws Exception {
        NativeMapping mapping = new NativeMapping(fields());
        NativeIndexManifest manifest =
                new NativeIndexManifest(
                        "eslib-fixture",
                        3,
                        NativeJson.hex(NativeJson.digest(mapping.mapping)),
                        mapping.mapping,
                        NativeJson.hex(NativeJson.digest(mapping.config)),
                        "a".repeat(40),
                        "b".repeat(64));
        manifest.directorySha256 = "c".repeat(64);
        manifest.files.put("segments_1", new NativeIndexManifest.File(10, "d".repeat(64)));
        NativeIndexManifest decoded = NativeIndexManifest.decode(manifest.encode());
        assertEquals(3, decoded.rows);
        assertArrayEquals(mapping.mapping, decoded.mapping);
        byte[] tampered =
                new String(manifest.encode(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("\\\"enabled\\\":false", "\\\"enabled\\\":true")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> NativeIndexManifest.decode(tampered));
        assertThrows(IOException.class, () -> NativeIndexManifest.requireFileName("../segments_1"));
    }

    @Test
    void archiveRejectsOverflowOverlapAndShortReads() throws Exception {
        assertThrows(
                IOException.class,
                () -> NativeESIndexSearcher.range(new long[] {Long.MAX_VALUE, 1}));
        Map<String, long[]> ranges = new LinkedHashMap<>();
        ranges.put("a", new long[] {0, 10});
        ranges.put("b", new long[] {9, 2});
        assertThrows(IOException.class, () -> NativeESIndexSearcher.validateRanges(ranges));
    }

    @Test
    void protocolRejectsDuplicateAndTrailingJson() {
        assertThrows(
                IOException.class,
                () ->
                        NativeJson.parse(
                                "{\"x\":1,\"x\":2}"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(
                IOException.class,
                () -> NativeJson.parse("{} {}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
