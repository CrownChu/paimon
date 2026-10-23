/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;

import com.sun.jna.Memory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Metadata accompanies the archive; it is not materialized as a Lucene file. */
final class NativeIndexManifest {
    static final String NAME = ".eslib-native-v1.json";
    static final int MAX_BYTES = 16 * 1024 * 1024;
    final String identity, mappingSha256, configSha256, nativeCommit, nativeSha256;
    final long rows;
    final byte[] mapping;
    String directorySha256;
    long snapshotId, schemaId, rowStart;
    String partitionSha256, indexColumnsSha256;
    boolean external;
    final Map<String, File> files = new LinkedHashMap<>();

    static final class File {
        final long length;
        final String sha256;

        File(long length, String sha256) {
            this.length = length;
            this.sha256 = sha256;
        }
    }

    NativeIndexManifest(
            String identity,
            long rows,
            String mappingSha256,
            byte[] mapping,
            String configSha256,
            String nativeCommit,
            String nativeSha256) {
        this.identity = identity;
        this.rows = rows;
        this.mappingSha256 = mappingSha256;
        this.mapping = mapping.clone();
        this.configSha256 = configSha256;
        this.nativeCommit = nativeCommit;
        this.nativeSha256 = nativeSha256;
    }

    ElasticppLibrary.Identity identity(Memory name) throws IOException {
        ElasticppLibrary.Identity result = new ElasticppLibrary.Identity();
        result.tableUuid = name;
        result.tableUuidLength = (int) name.size() - 1;
        result.rowCount = rows;
        result.snapshotId = snapshotId;
        result.schemaId = schemaId;
        result.rowStart = rowStart;
        result.mappingSha256 = NativeJson.unhex(mappingSha256);
        result.partitionSha256 =
                partitionSha256 == null
                        ? NativeJson.digest(new byte[0])
                        : NativeJson.unhex(partitionSha256);
        result.indexColumnsSha256 =
                NativeJson.unhex(indexColumnsSha256 == null ? configSha256 : indexColumnsSha256);
        return result;
    }

    byte[] encode() throws IOException {
        if (external) {
            throw new IOException("Paimon metadata must retain its original PES1 envelope");
        }
        Map<String, Object> table = NativeJson.object();
        for (Map.Entry<String, File> file : files.entrySet()) {
            table.put(
                    file.getKey(),
                    NativeJson.object(
                            "length", file.getValue().length, "sha256", file.getValue().sha256));
        }
        byte[] value =
                NativeJson.encode(
                        NativeJson.object(
                                "format",
                                "eslib-native-index-v1",
                                "identity",
                                identity,
                                "rows",
                                rows,
                                "mapping",
                                new String(mapping, java.nio.charset.StandardCharsets.UTF_8),
                                "mappingSha256",
                                mappingSha256,
                                "configSha256",
                                configSha256,
                                "nativeCommit",
                                nativeCommit,
                                "nativeSha256",
                                nativeSha256,
                                "directorySha256",
                                directorySha256,
                                "files",
                                table));
        if (value.length > MAX_BYTES) {
            throw new IOException("Native index metadata exceeds its limit");
        }
        return value;
    }

    static NativeIndexManifest decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Native index metadata exceeds its limit");
        }
        JsonNode node = NativeJson.parse(bytes);
        if (!node.isObject()
                || node.size() != 10
                || !"eslib-native-index-v1".equals(NativeRuntime.text(node, "format"))) {
            throw new IOException(
                    "Native index format differs; legacy assets require their original reader");
        }
        String identity = NativeRuntime.text(node, "identity"),
                mappingText = NativeRuntime.text(node, "mapping");
        NativeClient.require(
                identity.length() <= 1024 && identity.indexOf('\u0000') < 0,
                "Invalid native index identity");
        JsonNode rows = node.get("rows");
        NativeClient.require(
                rows != null
                        && rows.isIntegralNumber()
                        && rows.canConvertToLong()
                        && rows.longValue() >= 0
                        && rows.longValue() <= 2147483519L,
                "Invalid native row span");
        byte[] mapping = NativeJson.utf8(mappingText);
        NativeClient.require(mapping.length <= 8 * 1024 * 1024, "Native mapping exceeds its limit");
        NativeIndexManifest result =
                new NativeIndexManifest(
                        identity,
                        rows.longValue(),
                        NativeRuntime.text(node, "mappingSha256"),
                        mapping,
                        NativeRuntime.text(node, "configSha256"),
                        NativeRuntime.text(node, "nativeCommit"),
                        NativeRuntime.text(node, "nativeSha256"));
        NativeClient.require(result.nativeCommit.matches("[0-9a-f]{40}"), "Invalid native commit");
        NativeJson.unhex(result.configSha256);
        NativeJson.unhex(result.nativeSha256);
        NativeClient.require(
                Arrays.equals(NativeJson.digest(mapping), NativeJson.unhex(result.mappingSha256)),
                "Native mapping digest differs");
        result.directorySha256 = NativeRuntime.text(node, "directorySha256");
        NativeJson.unhex(result.directorySha256);
        JsonNode table = node.get("files");
        NativeClient.require(
                table != null && table.isObject() && !table.isEmpty() && table.size() <= 100000,
                "Invalid native file table");
        Iterator<Map.Entry<String, JsonNode>> iterator = table.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            requireFileName(entry.getKey());
            JsonNode value = entry.getValue(), length = value.get("length");
            NativeClient.require(
                    value.isObject()
                            && value.size() == 2
                            && length != null
                            && length.isIntegralNumber()
                            && length.canConvertToLong()
                            && length.longValue() >= 0,
                    "Invalid native file record");
            String sha = NativeRuntime.text(value, "sha256");
            NativeJson.unhex(sha);
            result.files.put(entry.getKey(), new File(length.longValue(), sha));
        }
        return result;
    }

    static void requireFileName(String name) throws IOException {
        NativeClient.require(
                name != null
                        && !name.isEmpty()
                        && name.length() <= 4096
                        && !name.equals(NAME)
                        && !name.equals(".")
                        && !name.equals("..")
                        && name.indexOf('/') < 0
                        && name.indexOf('\\') < 0
                        && name.indexOf('\u0000') < 0,
                "Unsafe native archive file name");
        NativeJson.utf8(name);
    }
}
