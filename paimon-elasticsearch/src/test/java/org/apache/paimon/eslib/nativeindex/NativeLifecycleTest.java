/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import org.apache.paimon.eslib.internal.ArchiveDataProvider;
import org.apache.paimon.eslib.internal.model.ScalarFieldType;

import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java lifecycle/ABI fixtures. No fixture creates an index or claims C++ correctness. */
class NativeLifecycleTest {
    @TempDir Path temporary;

    @Test
    void finalBuildDrainsBatchesAt127128And129RowsAndFailedSealNeverPublishes() throws Exception {
        for (int count : new int[] {127, 128, 129}) {
            Frame frame = new Frame();
            Path output = temporary.resolve("rows-" + count);
            try (NativeESIndexBuilder builder =
                    new NativeESIndexBuilder(
                            new NativeMapping(NativeProtocolTest.fields()),
                            output,
                            frame.client())) {
                for (int row = 0; row < count; row++) {
                    builder.addScalarField("id", row, (long) row, ScalarFieldType.LONG);
                    builder.finishDocument(row);
                }
                assertEquals(count < 128 ? 0 : 128, frame.rows.size());
                NativeException failure = assertThrows(NativeException.class, builder::build);
                assertEquals(
                        9001, failure.code()); // Deliberately no native seal implementation in this
                // fixture.
                assertEquals(count, frame.rows.size());
                assertEquals(
                        count == 129 ? Arrays.asList(128, 1) : Collections.singletonList(count),
                        frame.batchSizes);
                for (int row = 0; row < count; row++) {
                    assertEquals(row, frame.rows.get(row).longValue());
                    assertEquals(row, frame.documents.get(row).path("id").longValue());
                }
                assertThrows(IllegalStateException.class, builder::getOutputDir);
                assertThrows(IllegalStateException.class, builder::build);
                assertFalse(Files.exists(output.resolve(NativeIndexManifest.NAME)));
            }
            assertEquals(1, frame.removes);
            frame.assertAllResultsFreed();
        }
    }

    @Test
    void buildWithoutFinishDocumentPreservesUnorderedFieldsAndDenseNullHoles() throws Exception {
        Frame frame = new Frame();
        try (NativeESIndexBuilder builder =
                new NativeESIndexBuilder(
                        new NativeMapping(NativeProtocolTest.fields()),
                        temporary.resolve("holes"),
                        frame.client())) {
            builder.addTextField("title", 129, "tail");
            builder.addScalarField("id", 0, 9007199254740993L, ScalarFieldType.LONG);
            builder.addNullDoc(1);
            builder.addNullDoc(1);
            assertThrows(NativeException.class, builder::build);
            assertEquals(130, frame.rows.size());
            assertEquals(9007199254740993L, frame.documents.get(0).path("id").longValue());
            for (int row = 1; row < 129; row++) {
                assertTrue(frame.documents.get(row).isEmpty());
            }
            assertEquals("tail", frame.documents.get(129).path("title").asText());
        }
        assertEquals(1, frame.removes);
        frame.assertAllResultsFreed();
    }

    @Test
    void completedRowsCannotBeRewrittenAndRollbackCloseIsIdempotent() throws Exception {
        Frame frame = new Frame();
        NativeESIndexBuilder builder =
                new NativeESIndexBuilder(
                        new NativeMapping(NativeProtocolTest.fields()),
                        temporary.resolve("abort"),
                        frame.client());
        builder.addNullDoc(0);
        builder.finishDocument(0);
        builder.addNullDoc(0);
        assertThrows(
                IllegalArgumentException.class, () -> builder.addTextField("title", 0, "rewrite"));
        builder.close();
        builder.close();
        assertTrue(frame.rows.isEmpty());
        assertEquals(1, frame.removes);
        assertThrows(IllegalStateException.class, () -> builder.addNullDoc(1));
        frame.assertAllResultsFreed();
    }

    @Test
    void mappingFailureRollsBackTheCreatedShardAndFreesBothResults() throws Exception {
        Frame frame = new Frame();
        frame.failMapping = true;
        assertThrows(
                NativeException.class,
                () ->
                        new NativeESIndexBuilder(
                                new NativeMapping(NativeProtocolTest.fields()),
                                temporary.resolve("mapping-failed"),
                                frame.client()));
        assertEquals(1, frame.creates);
        assertEquals(1, frame.removes);
        assertTrue(frame.rows.isEmpty());
        frame.assertAllResultsFreed();
    }

    @Test
    void successfulBulkSummaryCannotHideARejectedItemOrTriggerRetry() throws Exception {
        Frame frame = new Frame();
        frame.failedItem = 1;
        NativeException failure =
                assertThrows(
                        NativeException.class,
                        () ->
                                frame.client()
                                        .bulk(
                                                "fixture",
                                                "bulk-fixture",
                                                0,
                                                Arrays.asList(
                                                        "{}".getBytes(StandardCharsets.UTF_8),
                                                        "{}".getBytes(StandardCharsets.UTF_8))));
        assertEquals(29, failure.code());
        assertTrue(failure.mutationStarted());
        assertEquals(1, frame.batchSizes.size());
        frame.assertAllResultsFreed();
    }

    @Test
    void invalidOpenRowCountClosesTheReturnedHandleAndFreesBothResults() throws Exception {
        Frame frame = new Frame();
        NativeIndexManifest manifest = manifest(new NativeMapping(NativeProtocolTest.fields()), 2);
        assertThrows(IOException.class, () -> frame.client().open(temporary, manifest));
        assertEquals(Collections.singletonList(41L), frame.closedHandles);
        frame.assertAllResultsFreed();
    }

    @Test
    void corruptedArchiveClosesProviderBeforeAnyNativeOpenAndCloseCanBeRepeated() throws Exception {
        Frame frame = new Frame();
        NativeMapping mapping = new NativeMapping(NativeProtocolTest.fields());
        NativeIndexManifest manifest = manifest(mapping, 2);
        manifest.files.put(
                "segments_1",
                new NativeIndexManifest.File(
                        3,
                        NativeJson.hex(NativeJson.digest("abc".getBytes(StandardCharsets.UTF_8)))));
        byte[] metadata = manifest.encode();
        byte[] archive = Arrays.copyOf(metadata, metadata.length + 3);
        System.arraycopy("xyz".getBytes(StandardCharsets.UTF_8), 0, archive, metadata.length, 3);
        Map<String, long[]> offsets = new LinkedHashMap<>();
        offsets.put(NativeIndexManifest.NAME, new long[] {0, metadata.length});
        offsets.put("segments_1", new long[] {metadata.length, 3});
        FixtureArchive provider = new FixtureArchive(archive);
        NativeESIndexSearcher searcher = new NativeESIndexSearcher(frame.client());
        IOException failure =
                assertThrows(
                        IOException.class,
                        () -> searcher.load(provider, offsets, NativeProtocolTest.fields()));
        assertTrue(failure.getMessage().contains("checksum"));
        assertEquals(0, frame.opens);
        assertEquals(1, provider.closes);
        searcher.close();
        assertEquals(1, provider.closes);
        frame.assertAllResultsFreed();
    }

    private static NativeIndexManifest manifest(NativeMapping mapping, long rows) {
        NativeIndexManifest value =
                new NativeIndexManifest(
                        "eslib-fixture",
                        rows,
                        NativeJson.hex(NativeJson.digest(mapping.mapping)),
                        mapping.mapping,
                        NativeJson.hex(NativeJson.digest(mapping.config)),
                        "a".repeat(40),
                        "b".repeat(64));
        value.directorySha256 = "c".repeat(64);
        return value;
    }

    private static final class FixtureArchive implements ArchiveDataProvider {
        final byte[] bytes;
        int closes;

        FixtureArchive(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public byte[] readRange(long offset, int length) throws IOException {
            if (closes != 0 || offset < 0 || offset > bytes.length - length) {
                throw new IOException("Invalid fixture range");
            }
            return Arrays.copyOfRange(bytes, (int) offset, (int) offset + length);
        }

        @Override
        public ArchiveDataProvider fork() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closes++;
        }
    }

    private static final class Frame {
        final List<Structure> retained = new ArrayList<>();
        final Set<Pointer> results = new HashSet<>(), released = new HashSet<>();
        final List<Long> rows = new ArrayList<>(), closedHandles = new ArrayList<>();
        final List<JsonNode> documents = new ArrayList<>();
        final List<Integer> batchSizes = new ArrayList<>();
        boolean failMapping;
        int failedItem = -1, creates, removes, opens;

        NativeClient client() {
            ElasticppLibrary api =
                    (ElasticppLibrary)
                            Proxy.newProxyInstance(
                                    ElasticppLibrary.class.getClassLoader(),
                                    new Class<?>[] {ElasticppLibrary.class},
                                    (object, method, args) -> invoke(method.getName(), args));
            return new NativeClient(api, "a".repeat(40), "b".repeat(64), 1024);
        }

        Object invoke(String name, Object[] args) throws Exception {
            if (name.startsWith("free")) {
                Pointer pointer = (Pointer) args[0];
                assertTrue(results.contains(pointer), "Freeing an unknown result");
                assertTrue(released.add(pointer), "Double free");
                return null;
            }
            switch (name) {
                case "createShardV3":
                    creates++;
                    ElasticppLibrary.CreateOptions options =
                            (ElasticppLibrary.CreateOptions) args[2];
                    options.read();
                    assertEquals(2, options.closeMode);
                    assertEquals(1, options.documentIdMode);
                    assertEquals(1, options.indexSortById);
                    return writeResult(0);
                case "updateShardWriteMappingV1":
                    return writeResult(failMapping ? 7 : 0);
                case "commitShardV1":
                    return writeResult(0);
                case "removeShardV2":
                    removes++;
                    return writeResult(0);
                case "bulkShardDocumentsU64V1":
                    return bulk((ElasticppLibrary.BulkRequest) args[1]);
                case "sealShardV1":
                    ElasticppLibrary.SealResult seal = new ElasticppLibrary.SealResult();
                    status(seal.status, 9001);
                    return result(seal);
                case "openArchivedShardV1":
                    opens++;
                    ElasticppLibrary.OpenResult open = new ElasticppLibrary.OpenResult();
                    status(open.status, 0);
                    open.handle = 41;
                    open.maxDoc = 3;
                    return result(open);
                case "closeArchivedShardV1":
                    closedHandles.add((Long) args[0]);
                    ElasticppLibrary.Status close = new ElasticppLibrary.Status();
                    status(close, 0);
                    return result(close);
                default:
                    throw new AssertionError("Unexpected fixture operation: " + name);
            }
        }

        Pointer bulk(ElasticppLibrary.BulkRequest request) throws Exception {
            request.read();
            batchSizes.add(request.itemCount);
            assertEquals(96, request.itemStructSize);
            for (int i = 0; i < request.itemCount; i++) {
                ElasticppLibrary.BulkItem item =
                        Structure.newInstance(
                                ElasticppLibrary.BulkItem.class,
                                request.items.share((long) i * request.itemStructSize));
                item.read();
                assertEquals(1, item.abiVersion, "ABI version for bulk item " + i);
                assertEquals(96, item.structSize);
                assertEquals(1, item.mappingGeneration);
                assertEquals(-2, item.ifSequenceNumber);
                assertEquals(0, item.ifPrimaryTerm);
                rows.add(item.documentId);
                documents.add(
                        NativeJson.parse(
                                item.source.getByteArray(0, Math.toIntExact(item.sourceLength))));
            }
            ElasticppLibrary.WriteResult[] items =
                    (ElasticppLibrary.WriteResult[])
                            new ElasticppLibrary.WriteResult().toArray(request.itemCount);
            for (int i = 0; i < items.length; i++) {
                writeStatus(items[i], i == failedItem ? 29 : 0);
                items[i].write();
                retained.add(items[i]);
            }
            ElasticppLibrary.BulkResult result = new ElasticppLibrary.BulkResult();
            result.abiVersion = 1;
            result.structSize = result.size();
            writeStatus(result.summary, 0);
            result.itemCount = items.length;
            result.itemResults = items[0].getPointer();
            return result(result);
        }

        Pointer writeResult(int code) {
            ElasticppLibrary.WriteResult value = new ElasticppLibrary.WriteResult();
            writeStatus(value, code);
            return result(value);
        }

        static void writeStatus(ElasticppLibrary.WriteResult value, int code) {
            value.abiVersion = 1;
            value.structSize = value.size();
            value.success = code == 0 ? 1 : 0;
            value.errorCode = code;
            value.flags = 16;
        }

        static void status(ElasticppLibrary.Status value, int code) {
            value.abiVersion = 1;
            value.structSize = value.size();
            value.success = code == 0 ? 1 : 0;
            value.errorCode = code;
        }

        Pointer result(Structure value) {
            value.write();
            retained.add(value);
            Pointer pointer = value.getPointer();
            assertTrue(results.add(pointer));
            return pointer;
        }

        void assertAllResultsFreed() {
            assertEquals(results, released);
        }
    }
}
