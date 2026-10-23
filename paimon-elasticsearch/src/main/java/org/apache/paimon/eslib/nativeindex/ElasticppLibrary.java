/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/** Fixed LP64 layouts; C++ static_assert values are checked before any call. */
interface ElasticppLibrary extends Library {
    Pointer getNativeEsCapabilitiesV1();

    void freeNativeEsCapabilitiesV1(Pointer value);

    Pointer createShardV3(Shard shard, String path, CreateOptions options);

    Pointer updateShardWriteMappingV1(Shard shard, MappingRequest request);

    Pointer bulkShardDocumentsU64V1(Shard shard, BulkRequest request);

    Pointer commitShardV1(Shard shard, ControlRequest request);

    Pointer removeShardV2(Shard shard);

    void freeShardWriteResultV1(Pointer value);

    void freeShardBulkWriteResultV1(Pointer value);

    Pointer sealShardV1(SealRequest request);

    Pointer probeArchivedShardV1(ProbeRequest request);

    Pointer openArchivedShardV1(OpenRequest request);

    Pointer queryArchivedShardV1(long handle, QueryRequest request);

    Pointer closeArchivedShardV1(long handle);

    void freePaimonEsStatusV1(Pointer value);

    void freePaimonEsSealResultV1(Pointer value);

    void freePaimonEsProbeResultV1(Pointer value);

    void freePaimonEsOpenArchivedShardResultV1(Pointer value);

    void freePaimonEsArchivedQueryResultV1(Pointer value);

    abstract class Input extends Structure {
        public int abiVersion = 1;
        public int structSize;

        final void prepare() {
            structSize = size();
            write();
        }
    }

    @Structure.FieldOrder({"index", "shardId"})
    class Shard extends Structure {
        public Pointer index;
        public long shardId;
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "openMode",
        "closeMode",
        "ramBufferBytes",
        "maxDocumentBytes",
        "maxBufferedDocs",
        "maxThreadStates",
        "maxPendingOperations",
        "reserved0",
        "maxPendingBytes",
        "documentIdMode",
        "indexSortById",
        "optionFlags",
        "reserved1",
        "reserved"
    })
    class CreateOptions extends Input {
        public int openMode = 1, closeMode = 2;
        public long ramBufferBytes, maxDocumentBytes = 8L * 1024 * 1024;
        public int maxBufferedDocs, maxThreadStates = 1, maxPendingOperations = 1, reserved0;
        public long maxPendingBytes = 16L * 1024 * 1024;
        public int documentIdMode = 1, indexSortById = 1, optionFlags = 1, reserved1;
        public long[] reserved = new long[2];

        public CreateOptions() {
            abiVersion = 2;
        }
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "generation",
        "expectedCurrentGeneration",
        "mappingSource",
        "mappingSourceLength",
        "reserved"
    })
    class MappingRequest extends Input {
        public long generation = 1, expectedCurrentGeneration;
        public Pointer mappingSource;
        public long mappingSourceLength;
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "operationType",
        "reserved0",
        "documentId",
        "source",
        "sourceLength",
        "mappingGeneration",
        "ifSequenceNumber",
        "ifPrimaryTerm",
        "reserved"
    })
    class BulkItem extends Input {
        public int operationType, reserved0;
        public long documentId;
        public Pointer source;
        public long sourceLength, mappingGeneration = 1, ifSequenceNumber = -2, ifPrimaryTerm;
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "operationId",
        "operationIdLength",
        "itemCount",
        "items",
        "itemStructSize",
        "durability",
        "refreshPolicy",
        "reserved0",
        "timeoutMillis",
        "reserved"
    })
    class BulkRequest extends Input {
        public Pointer operationId;
        public int operationIdLength, itemCount;
        public Pointer items;
        public int itemStructSize, durability, refreshPolicy, reserved0;
        public long timeoutMillis = 30000;
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "operationId",
        "operationIdLength",
        "reserved0",
        "timeoutMillis",
        "reserved"
    })
    class ControlRequest extends Input {
        public Pointer operationId;
        public int operationIdLength, reserved0;
        public long timeoutMillis = 30000;
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "success",
        "errorCode",
        "state",
        "visibility",
        "flags",
        "reserved0",
        "operationToken",
        "sequenceNumber",
        "executeTimeUs",
        "exceptionType",
        "exceptionTypeLength",
        "reserved1",
        "exceptionMessage",
        "exceptionMessageLength",
        "reserved2",
        "reserved"
    })
    class WriteResult extends Structure {
        public int abiVersion, structSize, success, errorCode, state, visibility, flags, reserved0;
        public long operationToken, sequenceNumber, executeTimeUs;
        public Pointer exceptionType;
        public int exceptionTypeLength, reserved1;
        public Pointer exceptionMessage;
        public int exceptionMessageLength, reserved2;
        public long[] reserved = new long[4];

        public WriteResult() {}

        public WriteResult(Pointer p) {
            super(p);
            read();
        }
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "summary",
        "itemCount",
        "itemResults",
        "reserved"
    })
    class BulkResult extends Structure {
        public int abiVersion, structSize;
        public WriteResult summary;
        public long itemCount;
        public Pointer itemResults;
        public long[] reserved = new long[4];

        public BulkResult() {
            summary = new WriteResult();
        }

        public BulkResult(Pointer p) {
            super(p);
            summary = new WriteResult();
            read();
        }
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "success",
        "errorCode",
        "retryable",
        "mutationStarted",
        "rebuildRequired",
        "reserved0",
        "diagnostic",
        "diagnosticLength",
        "reserved1",
        "reserved"
    })
    class Status extends Structure {
        public int abiVersion,
                structSize,
                success,
                errorCode,
                retryable,
                mutationStarted,
                rebuildRequired,
                reserved0;
        public Pointer diagnostic;
        public int diagnosticLength, reserved1;
        public long[] reserved = new long[4];

        public Status() {}

        public Status(Pointer p) {
            super(p);
            read();
        }
    }

    @Structure.FieldOrder({
        "status",
        "minimumAbiVersion",
        "maximumAbiVersion",
        "capabilityFlags",
        "maximumVectorDimensions",
        "reserved0",
        "gitCommit",
        "gitCommitLength",
        "reserved1",
        "buildInfo",
        "buildInfoLength",
        "reserved2",
        "luceneVersion",
        "luceneVersionLength",
        "reserved3",
        "targetTriple",
        "targetTripleLength",
        "reserved4",
        "minimumGlibc",
        "minimumGlibcLength",
        "reserved5",
        "sharedObjectSha256",
        "reserved"
    })
    class Capabilities extends Structure {
        public Status status;
        public int minimumAbiVersion, maximumAbiVersion;
        public long capabilityFlags;
        public int maximumVectorDimensions, reserved0;
        public Pointer gitCommit;
        public int gitCommitLength, reserved1;
        public Pointer buildInfo;
        public int buildInfoLength, reserved2;
        public Pointer luceneVersion;
        public int luceneVersionLength, reserved3;
        public Pointer targetTriple;
        public int targetTripleLength, reserved4;
        public Pointer minimumGlibc;
        public int minimumGlibcLength, reserved5;
        public byte[] sharedObjectSha256 = new byte[32];
        public long[] reserved = new long[4];

        public Capabilities() {
            status = new Status();
        }

        public Capabilities(Pointer p) {
            super(p);
            status = new Status();
            read();
        }
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "tableUuid",
        "tableUuidLength",
        "reserved0",
        "snapshotId",
        "schemaId",
        "rowStart",
        "rowCount",
        "partitionSha256",
        "indexColumnsSha256",
        "mappingSha256",
        "reserved"
    })
    class Identity extends Input {
        public Pointer tableUuid;
        public int tableUuidLength, reserved0;
        public long snapshotId = 1, schemaId, rowStart, rowCount;
        public byte[] partitionSha256 = new byte[32],
                indexColumnsSha256 = new byte[32],
                mappingSha256 = new byte[32];
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "shard",
        "localPath",
        "localPathLength",
        "reserved0",
        "identity",
        "timeoutMillis",
        "reserved"
    })
    class SealRequest extends Input {
        public Pointer shard, localPath;
        public int localPathLength, reserved0;
        public Pointer identity;
        public long timeoutMillis = 30000;
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({"name", "nameLength", "reserved0", "length", "sha256", "reserved"})
    class FileMeta extends Structure {
        public Pointer name;
        public int nameLength, reserved0;
        public long length;
        public byte[] sha256 = new byte[32];
        public long[] reserved = new long[2];

        public FileMeta() {}

        public FileMeta(Pointer p) {
            super(p);
            read();
        }
    }

    @Structure.FieldOrder({
        "status",
        "commitGeneration",
        "maxDoc",
        "numDocs",
        "numDeletedDocs",
        "directorySha256",
        "fileCount",
        "files",
        "reserved"
    })
    class SealResult extends Structure {
        public Status status;
        public long commitGeneration, maxDoc, numDocs, numDeletedDocs;
        public byte[] directorySha256 = new byte[32];
        public long fileCount;
        public Pointer files;
        public long[] reserved = new long[4];

        public SealResult() {
            status = new Status();
        }

        public SealResult(Pointer p) {
            super(p);
            status = new Status();
            read();
        }
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "localPath",
        "localPathLength",
        "reserved0",
        "identity",
        "expectedDirectorySha256",
        "reserved"
    })
    class ProbeRequest extends Input {
        public Pointer localPath;
        public int localPathLength, reserved0;
        public Pointer identity;
        public byte[] expectedDirectorySha256 = new byte[32];
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({
        "status",
        "commitGeneration",
        "maxDoc",
        "numDocs",
        "numDeletedDocs",
        "directorySha256",
        "reserved"
    })
    class ProbeResult extends Structure {
        public Status status;
        public long commitGeneration, maxDoc, numDocs, numDeletedDocs;
        public byte[] directorySha256 = new byte[32];
        public long[] reserved = new long[4];

        public ProbeResult() {
            status = new Status();
        }

        public ProbeResult(Pointer p) {
            super(p);
            status = new Status();
            read();
        }
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "localPath",
        "localPathLength",
        "reserved0",
        "identity",
        "expectedDirectorySha256",
        "mappingSource",
        "mappingSourceLength",
        "reserved"
    })
    class OpenRequest extends Input {
        public Pointer localPath;
        public int localPathLength, reserved0;
        public Pointer identity;
        public byte[] expectedDirectorySha256 = new byte[32];
        public Pointer mappingSource;
        public long mappingSourceLength;
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({"status", "handle", "maxDoc", "commitGeneration", "reserved"})
    class OpenResult extends Structure {
        public Status status;
        public long handle, maxDoc, commitGeneration;
        public long[] reserved = new long[4];

        public OpenResult() {
            status = new Status();
        }

        public OpenResult(Pointer p) {
            super(p);
            status = new Status();
            read();
        }
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "searchSourceJson",
        "searchSourceJsonLength",
        "timeoutMillis",
        "maxResultRows",
        "maxResultBytes",
        "sortMode",
        "reserved0",
        "reserved"
    })
    class QueryRequest extends Input {
        public Pointer searchSourceJson;
        public long searchSourceJsonLength, timeoutMillis = 30000, maxResultRows, maxResultBytes;
        public int sortMode, reserved0;
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({"rowId", "score", "reserved0", "reserved"})
    class Hit extends Structure {
        public long rowId;
        public float score;
        public int reserved0;
        public long[] reserved = new long[2];

        public Hit() {}

        public Hit(Pointer p) {
            super(p);
            read();
        }
    }

    @Structure.FieldOrder({
        "status",
        "complete",
        "timedOut",
        "terminatedEarly",
        "approximated",
        "totalHitsRelation",
        "reserved0",
        "totalHits",
        "hitCount",
        "hits",
        "resultBytes",
        "reserved"
    })
    class QueryResult extends Structure {
        public Status status;
        public int complete, timedOut, terminatedEarly, approximated, totalHitsRelation, reserved0;
        public long totalHits, hitCount;
        public Pointer hits;
        public long resultBytes;
        public long[] reserved = new long[4];

        public QueryResult() {
            status = new Status();
        }

        public QueryResult(Pointer p) {
            super(p);
            status = new Status();
            read();
        }
    }
}
