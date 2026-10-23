/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A caller-owned Paimon sealed-shard descriptor. The caller decodes PES1 and keeps its original
 * archive offsets; this adapter does not invent a PES1/PEQ1/PQR1 query envelope.
 */
public final class NativeArchiveMetadata {
    /** A sealed file length and content identity. */
    public static final class File {
        private final long length;
        private final String sha256;

        public File(long length, String sha256) {
            if (length < 0) {
                throw new IllegalArgumentException("Negative native file length");
            }
            requireDigest(sha256);
            this.length = length;
            this.sha256 = sha256;
        }

        public long length() {
            return length;
        }

        public String sha256() {
            return sha256;
        }
    }

    private final String tableUuid, partitionSha256, indexColumnsSha256, nativeCommit, nativeSha256;
    private final long snapshotId, schemaId, rowStart, rows;
    private final byte[] mapping;
    private final Map<String, File> files;

    public NativeArchiveMetadata(
            String tableUuid,
            long snapshotId,
            long schemaId,
            long rowStart,
            long rows,
            String partitionSha256,
            String indexColumnsSha256,
            byte[] canonicalMapping,
            String nativeCommit,
            String nativeSha256,
            Map<String, File> files)
            throws IOException {
        NativeClient.require(
                tableUuid != null
                        && !tableUuid.isEmpty()
                        && NativeJson.utf8(tableUuid).length <= 1024
                        && tableUuid.indexOf('\0') < 0,
                "Invalid native table identity");
        NativeClient.require(
                snapshotId >= 0
                        && schemaId >= 0
                        && rowStart >= 0
                        && rows >= 0
                        && rows <= 2147483519L
                        && rowStart <= Long.MAX_VALUE - rows,
                "Invalid native snapshot or signed row span");
        NativeClient.require(
                canonicalMapping != null && canonicalMapping.length <= 8 * 1024 * 1024,
                "Invalid native mapping length");
        NativeClient.require(
                nativeCommit != null && nativeCommit.matches("[0-9a-f]{40}"),
                "Invalid immutable native commit");
        requireDigest(partitionSha256);
        requireDigest(indexColumnsSha256);
        requireDigest(nativeSha256);
        NativeClient.require(
                files != null && !files.isEmpty() && files.size() <= 100000,
                "Invalid native sealed file count");
        Map<String, File> selected = new TreeMap<>(NativeJson.UTF8_ORDER);
        for (Map.Entry<String, File> entry : files.entrySet()) {
            NativeIndexManifest.requireFileName(entry.getKey());
            NativeClient.require(entry.getValue() != null, "Missing native sealed file identity");
            selected.put(entry.getKey(), entry.getValue());
        }
        this.tableUuid = tableUuid;
        this.snapshotId = snapshotId;
        this.schemaId = schemaId;
        this.rowStart = rowStart;
        this.rows = rows;
        this.partitionSha256 = partitionSha256;
        this.indexColumnsSha256 = indexColumnsSha256;
        this.mapping = canonicalMapping.clone();
        this.nativeCommit = nativeCommit;
        this.nativeSha256 = nativeSha256;
        this.files = Collections.unmodifiableMap(selected);
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Native identity requires a SHA-256 digest");
        }
    }

    NativeIndexManifest manifest(NativeMapping queryMapping) throws IOException {
        JsonNode actual = NativeJson.parse(mapping),
                expected = NativeJson.parse(queryMapping.mapping);
        NativeClient.require(
                actual.isObject()
                        && actual.path("_source").path("enabled").isBoolean()
                        && !actual.path("_source").path("enabled").booleanValue(),
                "Paimon native mapping must explicitly disable source");
        JsonNode actualFields = actual.get("properties");
        NativeClient.require(
                actualFields != null && actualFields.isObject(), "Missing Paimon physical fields");
        for (String field : queryMapping.fields.keySet()) {
            String physical = queryMapping.nativeName(field);
            JsonNode selected = actualFields.get(physical);
            if (selected == null && physical.indexOf('.') >= 0) {
                String[] parts = physical.split("\\.", -1);
                selected = actualFields.get(parts[0]);
                for (int i = 1; i < parts.length && selected != null; i++) {
                    selected = selected.path("fields").get(parts[i]);
                }
            }
            requirePhysicalField(expected.path("properties").get(physical), selected);
        }
        NativeIndexManifest value =
                new NativeIndexManifest(
                        tableUuid,
                        rows,
                        NativeJson.hex(NativeJson.digest(mapping)),
                        mapping,
                        NativeJson.hex(NativeJson.digest(queryMapping.config)),
                        nativeCommit,
                        nativeSha256);
        value.snapshotId = snapshotId;
        value.schemaId = schemaId;
        value.rowStart = rowStart;
        value.partitionSha256 = partitionSha256;
        value.indexColumnsSha256 = indexColumnsSha256;
        value.external = true;
        // PaimonElasticsearch.cpp inspectDirectory: UTF-8 order, big-endian lengths and SHA-256.
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        digest.update("elasticpp.paimon.directory.v1".getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, File> entry : files.entrySet()) {
            byte[] name = NativeJson.utf8(entry.getKey());
            digest.update(ByteBuffer.allocate(4).putInt(name.length).array());
            digest.update(name);
            digest.update(ByteBuffer.allocate(8).putLong(entry.getValue().length).array());
            digest.update(NativeJson.unhex(entry.getValue().sha256));
            value.files.put(
                    entry.getKey(),
                    new NativeIndexManifest.File(entry.getValue().length, entry.getValue().sha256));
        }
        value.directorySha256 = NativeJson.hex(digest.digest());
        return value;
    }

    private static void requirePhysicalField(JsonNode expected, JsonNode actual)
            throws IOException {
        NativeClient.require(
                actual != null && actual.isObject(), "Query field is absent from Paimon mapping");
        ObjectNode required = expected.deepCopy(), selected = actual.deepCopy();
        // Coercion affects writes only. Imported indexes retain their original canonical mapping.
        // A parent's complementary fields do not change queries against that parent; a directly
        // queried subfield is checked separately. Reject all other unqualified mapping differences.
        NativeClient.require(
                !selected.has("coerce") || selected.get("coerce").isBoolean(),
                "Invalid Paimon numeric coercion setting");
        required.remove("coerce");
        selected.remove("coerce");
        selected.remove("fields");
        NativeClient.require(
                required.equals(selected), "Paimon physical field configuration differs");
    }
}
