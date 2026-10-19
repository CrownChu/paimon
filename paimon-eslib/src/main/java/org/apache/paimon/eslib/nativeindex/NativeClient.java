/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import org.apache.paimon.eslib.internal.model.SearchResult;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Sole owner of every returned native allocation; no implicit mutation retry. */
final class NativeClient {
    static final String[] REQUIRED_SYMBOLS = {
        "getNativeEsCapabilitiesV1",
        "freeNativeEsCapabilitiesV1",
        "createShardV3",
        "updateShardWriteMappingV1",
        "bulkShardDocumentsU64V1",
        "commitShardV1",
        "removeShardV2",
        "freeShardWriteResultV1",
        "freeShardBulkWriteResultV1",
        "sealShardV1",
        "probeArchivedShardV1",
        "openArchivedShardV1",
        "queryArchivedShardV1",
        "closeArchivedShardV1",
        "freePaimonEsStatusV1",
        "freePaimonEsSealResultV1",
        "freePaimonEsProbeResultV1",
        "freePaimonEsOpenArchivedShardResultV1",
        "freePaimonEsArchivedQueryResultV1"
    };
    final ElasticppLibrary api;
    final String commit, sha256;
    final int maximumDimensions;

    NativeClient(ElasticppLibrary api, String commit, String sha256, int maximumDimensions) {
        this.api = api;
        this.commit = commit;
        this.sha256 = sha256;
        this.maximumDimensions = maximumDimensions;
    }

    static void verifyLayouts() throws IOException {
        require(Native.POINTER_SIZE == 8 && Native.LONG_SIZE == 8, "Native API requires LP64");
        Object[][] layouts = {
            {new ElasticppLibrary.Shard(), 16},
            {new ElasticppLibrary.CreateOptions(), 88},
            {new ElasticppLibrary.MappingRequest(), 72},
            {new ElasticppLibrary.BulkItem(), 96},
            {new ElasticppLibrary.BulkRequest(), 88},
            {new ElasticppLibrary.ControlRequest(), 64},
            {new ElasticppLibrary.WriteResult(), 120},
            {new ElasticppLibrary.BulkResult(), 176},
            {new ElasticppLibrary.Status(), 80},
            {new ElasticppLibrary.Capabilities(), 248},
            {new ElasticppLibrary.Identity(), 184},
            {new ElasticppLibrary.SealRequest(), 80},
            {new ElasticppLibrary.FileMeta(), 72},
            {new ElasticppLibrary.SealResult(), 192},
            {new ElasticppLibrary.ProbeRequest(), 96},
            {new ElasticppLibrary.ProbeResult(), 176},
            {new ElasticppLibrary.OpenRequest(), 112},
            {new ElasticppLibrary.OpenResult(), 136},
            {new ElasticppLibrary.QueryRequest(), 88},
            {new ElasticppLibrary.Hit(), 32},
            {new ElasticppLibrary.QueryResult(), 168}
        };
        for (Object[] layout : layouts) {
            require(
                    ((Structure) layout[0]).size() == (Integer) layout[1],
                    "C/JNA layout differs: " + layout[0].getClass().getSimpleName());
        }
    }

    private static ElasticppLibrary.Shard shard(Memory name) {
        ElasticppLibrary.Shard shard = new ElasticppLibrary.Shard();
        shard.index = name;
        shard.write();
        return shard;
    }

    void create(String index, Path directory, byte[] mapping) throws IOException {
        try (Memory name = bytes(NativeJson.utf8(index));
                Memory json = bytes(mapping)) {
            ElasticppLibrary.Shard shard = shard(name);
            ElasticppLibrary.CreateOptions options = new ElasticppLibrary.CreateOptions();
            options.prepare();
            write(
                    api.createShardV3(shard, directory.toAbsolutePath().toString(), options),
                    "create shard");
            ElasticppLibrary.MappingRequest request = new ElasticppLibrary.MappingRequest();
            request.mappingSource = json;
            request.mappingSourceLength = mapping.length;
            request.prepare();
            write(api.updateShardWriteMappingV1(shard, request), "install mapping");
        }
    }

    void bulk(String index, String operation, long firstRow, List<byte[]> documents)
            throws IOException {
        if (documents.isEmpty()) {
            return;
        }
        List<Memory> owned = new ArrayList<>();
        try (Memory name = bytes(NativeJson.utf8(index));
                Memory op = bytes(NativeJson.utf8(operation))) {
            ElasticppLibrary.BulkItem[] items =
                    (ElasticppLibrary.BulkItem[])
                            new ElasticppLibrary.BulkItem().toArray(documents.size());
            for (int i = 0; i < items.length; i++) {
                Memory value = bytes(documents.get(i));
                owned.add(value);
                // JNA toArray auto-reads subsequent structures from zeroed
                // contiguous storage, replacing their field initializers.
                items[i].abiVersion = 1;
                items[i].mappingGeneration = 1;
                items[i].ifSequenceNumber = -2;
                items[i].ifPrimaryTerm = 0;
                items[i].documentId = Math.addExact(firstRow, i);
                items[i].source = value;
                items[i].sourceLength = documents.get(i).length;
                items[i].prepare();
            }
            ElasticppLibrary.BulkRequest request = new ElasticppLibrary.BulkRequest();
            request.operationId = op;
            request.operationIdLength = (int) op.size() - 1;
            request.itemCount = items.length;
            request.items = items[0].getPointer();
            request.itemStructSize = items[0].size();
            request.prepare();
            Pointer pointer = api.bulkShardDocumentsU64V1(shard(name), request);
            require(pointer != null, "Native bulk allocation failed");
            try {
                requireHeader(pointer, new ElasticppLibrary.BulkResult().size());
                ElasticppLibrary.BulkResult result = new ElasticppLibrary.BulkResult(pointer);
                require(
                        result.abiVersion == 1 && result.structSize == result.size(),
                        "Native bulk ABI differs");
                requireWrite(result.summary, "bulk");
                require(
                        result.itemCount == documents.size() && result.itemResults != null,
                        "Native bulk is incomplete");
                int stride = new ElasticppLibrary.WriteResult().size();
                for (int i = 0; i < items.length; i++) {
                    requireWrite(
                            new ElasticppLibrary.WriteResult(
                                    result.itemResults.share((long) i * stride)),
                            "bulk item");
                }
            } finally {
                api.freeShardBulkWriteResultV1(pointer);
            }
        } finally {
            for (Memory memory : owned) {
                memory.close();
            }
        }
    }

    void commit(String index, String operation) throws IOException {
        try (Memory name = bytes(NativeJson.utf8(index));
                Memory op = bytes(NativeJson.utf8(operation))) {
            ElasticppLibrary.ControlRequest request = new ElasticppLibrary.ControlRequest();
            request.operationId = op;
            request.operationIdLength = (int) op.size() - 1;
            request.prepare();
            write(api.commitShardV1(shard(name), request), "commit");
        }
    }

    NativeIndexManifest seal(
            String index, Path directory, long rows, byte[] mapping, byte[] fieldConfig)
            throws IOException {
        NativeIndexManifest manifest =
                new NativeIndexManifest(
                        index,
                        rows,
                        NativeJson.hex(NativeJson.digest(mapping)),
                        mapping,
                        NativeJson.hex(NativeJson.digest(fieldConfig)),
                        commit,
                        sha256);
        try (Memory name = bytes(NativeJson.utf8(index));
                Memory path = bytes(NativeJson.utf8(directory.toAbsolutePath().toString()))) {
            ElasticppLibrary.Identity identity = manifest.identity(name);
            identity.prepare();
            ElasticppLibrary.Shard identifier = shard(name);
            ElasticppLibrary.SealRequest request = new ElasticppLibrary.SealRequest();
            request.shard = identifier.getPointer();
            request.localPath = path;
            request.localPathLength = (int) path.size() - 1;
            request.identity = identity.getPointer();
            request.prepare();
            Pointer pointer = api.sealShardV1(request);
            require(pointer != null, "Native seal allocation failed");
            try {
                requireHeader(pointer, new ElasticppLibrary.Status().size());
                ElasticppLibrary.SealResult result = new ElasticppLibrary.SealResult(pointer);
                requireStatus(result.status, "seal");
                require(
                        result.maxDoc == rows
                                && result.numDocs == rows
                                && result.numDeletedDocs == 0,
                        "Native row/doc mapping differs");
                require(
                        result.fileCount >= 1 && result.fileCount <= 100000 && result.files != null,
                        "Native seal file table differs");
                manifest.directorySha256 = NativeJson.hex(result.directorySha256);
                int stride = new ElasticppLibrary.FileMeta().size();
                for (int i = 0; i < result.fileCount; i++) {
                    ElasticppLibrary.FileMeta file =
                            new ElasticppLibrary.FileMeta(result.files.share((long) i * stride));
                    String fileName = string(file.name, file.nameLength, 4096);
                    NativeIndexManifest.requireFileName(fileName);
                    require(file.length >= 0, "Negative native file length");
                    require(
                            manifest.files.put(
                                            fileName,
                                            new NativeIndexManifest.File(
                                                    file.length, NativeJson.hex(file.sha256)))
                                    == null,
                            "Native file table repeats a name");
                }
            } finally {
                api.freePaimonEsSealResultV1(pointer);
            }
        }
        return manifest;
    }

    long open(Path directory, NativeIndexManifest manifest) throws IOException {
        try (Memory name = bytes(NativeJson.utf8(manifest.identity));
                Memory path = bytes(NativeJson.utf8(directory.toAbsolutePath().toString()));
                Memory mapping = bytes(manifest.mapping)) {
            ElasticppLibrary.Identity identity = manifest.identity(name);
            identity.prepare();
            ElasticppLibrary.OpenRequest request = new ElasticppLibrary.OpenRequest();
            request.localPath = path;
            request.localPathLength = (int) path.size() - 1;
            request.identity = identity.getPointer();
            request.expectedDirectorySha256 = NativeJson.unhex(manifest.directorySha256);
            request.mappingSource = mapping;
            request.mappingSourceLength = manifest.mapping.length;
            request.prepare();
            Pointer pointer = api.openArchivedShardV1(request);
            require(pointer != null, "Native reader allocation failed");
            long handle = 0;
            try {
                requireHeader(pointer, new ElasticppLibrary.Status().size());
                ElasticppLibrary.OpenResult result = new ElasticppLibrary.OpenResult(pointer);
                handle = result.handle;
                requireStatus(result.status, "open archived reader");
                require(
                        handle != 0 && result.maxDoc == manifest.rows,
                        "Native reader row count differs");
                return handle;
            } catch (IOException | RuntimeException | Error failure) {
                if (handle != 0) {
                    try {
                        close(handle);
                    } catch (Throwable cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            } finally {
                api.freePaimonEsOpenArchivedShardResultV1(pointer);
            }
        }
    }

    SearchResult query(long handle, byte[] source, int limit, boolean docOrder) throws IOException {
        require(
                limit > 0 && limit <= 1000000 && source.length <= 16 * 1024 * 1024,
                "Native query limits exceeded");
        try (Memory json = bytes(source)) {
            ElasticppLibrary.QueryRequest request = new ElasticppLibrary.QueryRequest();
            request.searchSourceJson = json;
            request.searchSourceJsonLength = source.length;
            request.maxResultRows = limit;
            request.maxResultBytes = Math.multiplyExact((long) limit, 32L);
            request.sortMode = docOrder ? 1 : 0;
            request.prepare();
            Pointer pointer = api.queryArchivedShardV1(handle, request);
            require(pointer != null, "Native query allocation failed");
            try {
                requireHeader(pointer, new ElasticppLibrary.Status().size());
                ElasticppLibrary.QueryResult result = new ElasticppLibrary.QueryResult(pointer);
                requireStatus(result.status, "query");
                require(
                        result.complete == 1
                                && result.timedOut == 0
                                && result.terminatedEarly == 0
                                && result.approximated == 0
                                && result.totalHitsRelation == 0
                                && result.totalHits >= 0
                                && result.hitCount >= 0
                                && result.hitCount <= limit
                                && result.hitCount == Math.min(result.totalHits, limit)
                                && result.resultBytes == result.hitCount * 32L,
                        "Native query returned incomplete results");
                require(result.hitCount == 0 || result.hits != null, "Native hits are missing");
                int count = Math.toIntExact(result.hitCount);
                long[] ids = new long[count];
                float[] scores = new float[count];
                for (int i = 0; i < count; i++) {
                    ElasticppLibrary.Hit hit =
                            new ElasticppLibrary.Hit(result.hits.share((long) i * 32L));
                    require(hit.rowId >= 0 && Float.isFinite(hit.score), "Invalid native hit");
                    ids[i] = hit.rowId;
                    scores[i] = hit.score;
                }
                return new SearchResult(ids, scores, count);
            } finally {
                api.freePaimonEsArchivedQueryResultV1(pointer);
            }
        }
    }

    void remove(String index) throws IOException {
        try (Memory name = bytes(NativeJson.utf8(index))) {
            write(api.removeShardV2(shard(name)), "remove shard");
        }
    }

    void close(long handle) throws IOException {
        Pointer pointer = api.closeArchivedShardV1(handle);
        require(pointer != null, "Native close allocation failed");
        try {
            requireHeader(pointer, new ElasticppLibrary.Status().size());
            requireStatus(new ElasticppLibrary.Status(pointer), "close archived reader");
        } finally {
            api.freePaimonEsStatusV1(pointer);
        }
    }

    private void write(Pointer pointer, String operation) throws IOException {
        require(pointer != null, "Native write allocation failed");
        try {
            requireHeader(pointer, new ElasticppLibrary.WriteResult().size());
            requireWrite(new ElasticppLibrary.WriteResult(pointer), operation);
        } finally {
            api.freeShardWriteResultV1(pointer);
        }
    }

    static void requireWrite(ElasticppLibrary.WriteResult result, String operation)
            throws IOException {
        require(
                result.abiVersion == 1
                        && result.structSize == result.size()
                        && result.reserved0 == 0,
                "Native write result ABI differs");
        require(
                (result.success == 0 || result.success == 1)
                        && result.errorCode >= 0
                        && (result.success == 1) == (result.errorCode == 0),
                "Native write result status differs");
        if (result.success != 1) {
            throw new NativeException(
                    result.errorCode, operation, false, (result.flags & 16) != 0, true);
        }
    }

    static void requireHeader(Pointer pointer, int expectedSize) throws IOException {
        require(
                pointer != null && pointer.getInt(0) == 1 && pointer.getInt(4) == expectedSize,
                "Native result header ABI differs");
    }

    static void requireStatus(ElasticppLibrary.Status result, String operation) throws IOException {
        require(
                result.abiVersion == 1
                        && result.structSize == result.size()
                        && result.reserved0 == 0
                        && result.reserved1 == 0,
                "Native result ABI differs");
        for (long reserved : result.reserved) {
            require(reserved == 0, "Native reserved result differs");
        }
        require(
                (result.success == 0 || result.success == 1)
                        && result.errorCode >= 0
                        && (result.success == 1) == (result.errorCode == 0)
                        && (result.retryable == 0 || result.retryable == 1)
                        && (result.mutationStarted == 0 || result.mutationStarted == 1)
                        && (result.rebuildRequired == 0 || result.rebuildRequired == 1),
                "Native status flags differ");
        if (result.success != 1) {
            throw new NativeException(
                    result.errorCode,
                    operation,
                    result.retryable == 1,
                    result.mutationStarted == 1,
                    result.rebuildRequired == 1);
        }
    }

    static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }

    static Memory bytes(byte[] bytes) {
        Memory memory = new Memory(bytes.length + 1L);
        memory.write(0, bytes, 0, bytes.length);
        memory.setByte(bytes.length, (byte) 0);
        return memory;
    }

    static String string(Pointer pointer, int length, int maximum) throws IOException {
        require(
                pointer != null && length >= 0 && length <= maximum,
                "Invalid native string boundary");
        byte[] bytes = pointer.getByteArray(0, length);
        String text = new String(bytes, StandardCharsets.UTF_8);
        require(Arrays.equals(bytes, NativeJson.utf8(text)), "Invalid native UTF-8");
        return text;
    }
}
