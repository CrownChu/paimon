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

package org.apache.paimon.eslib.index;

import org.apache.paimon.eslib.internal.model.BuiltinAnalyzer;
import org.apache.paimon.eslib.internal.model.FieldIndexConfig;
import org.apache.paimon.eslib.internal.model.ScalarFieldType;
import org.apache.paimon.eslib.internal.model.VectorAlgorithm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned metadata for an archived ESLib index. */
final class ESIndexFileMeta {

    private static final int MAGIC = 0x45534D31; // "ESM1"
    private static final int VERSION = 2;
    private static final int CONFIG_ONLY_VERSION = 1;
    private static final int BACKEND_VERSION = 3;

    private ESIndexFileMeta() {}

    static byte[] write(
            File[] files,
            List<String> indexedFieldNames,
            List<String> indexedFieldTypes,
            Map<String, FieldIndexConfig> fieldConfigs)
            throws IOException {
        return write(
                files,
                indexedFieldNames,
                indexedFieldTypes,
                fieldConfigs,
                ESIndexBuilderFactory.ELASTICPP);
    }

    static byte[] write(
            File[] files,
            List<String> indexedFieldNames,
            List<String> indexedFieldTypes,
            Map<String, FieldIndexConfig> fieldConfigs,
            String backend)
            throws IOException {
        backend = ESIndexBuilderFactory.backend(backend);
        if (files.length == 0) {
            throw new IOException("Cannot write es-index metadata without Lucene files");
        }
        if (indexedFieldNames.isEmpty()) {
            throw new IOException("Cannot write es-index metadata without indexed fields");
        }
        if (indexedFieldTypes.size() != indexedFieldNames.size()) {
            throw new IOException(
                    "ES index field name/type count mismatch: names="
                            + indexedFieldNames.size()
                            + ", types="
                            + indexedFieldTypes.size());
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        // Preserve the existing Java v2 format. Native archives persist their backend in v3.
        boolean nativeBackend = ESIndexBuilderFactory.ELASTICPP.equals(backend);
        out.writeInt(nativeBackend ? BACKEND_VERSION : VERSION);
        if (nativeBackend) {
            writeString(out, backend);
        }
        out.writeInt(indexedFieldNames.size());
        for (int i = 0; i < indexedFieldNames.size(); i++) {
            String fieldName = indexedFieldNames.get(i);
            if (!fieldConfigs.containsKey(fieldName)) {
                throw new IOException(
                        "Missing primary field config in es-index metadata: " + fieldName);
            }
            writeString(out, fieldName);
            writeString(out, indexedFieldTypes.get(i));
        }
        out.writeInt(fieldConfigs.size());
        for (Map.Entry<String, FieldIndexConfig> entry : fieldConfigs.entrySet()) {
            FieldIndexConfig config = entry.getValue();
            writeString(out, entry.getKey());
            writeString(out, config.indexType().name());
            writeNullableEnum(out, config.algorithm());
            out.writeInt(config.dimension());
            writeNullableString(out, config.metric());
            writeNullableEnum(out, config.analyzer());
            writeNullableEnum(out, config.scalarType());
            out.writeInt(config.algorithmParams().size());
            for (Map.Entry<String, String> parameter : config.algorithmParams().entrySet()) {
                writeString(out, parameter.getKey());
                writeString(out, parameter.getValue());
            }
        }

        writeOffsets(out, files);
        out.flush();
        return bytes.toByteArray();
    }

    static Parsed read(byte[] metadata) throws IOException {
        if (metadata == null || metadata.length == 0) {
            return new Parsed(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    false);
        }

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(metadata));
        int first = in.readInt();
        if (first != MAGIC) {
            Map<String, long[]> offsets = readOffsets(in, first);
            requireFullyConsumed(in);
            return new Parsed(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    offsets,
                    false);
        }

        int version = in.readInt();
        if (version != CONFIG_ONLY_VERSION && version != VERSION && version != BACKEND_VERSION) {
            throw new IOException("Unsupported es-index metadata version: " + version);
        }
        String backend = ESIndexBuilderFactory.JAVA;
        if (version == BACKEND_VERSION) {
            try {
                backend = ESIndexBuilderFactory.backend(readString(in));
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid persisted es-index backend", e);
            }
        }

        List<String> indexedFieldNames = new ArrayList<>();
        List<String> indexedFieldTypes = new ArrayList<>();
        if (version >= VERSION) {
            int indexedFieldCount = readCount(in, "indexed field");
            for (int i = 0; i < indexedFieldCount; i++) {
                String fieldName = readString(in);
                if (indexedFieldNames.contains(fieldName)) {
                    throw new IOException(
                            "Duplicate indexed field in es-index metadata: " + fieldName);
                }
                indexedFieldNames.add(fieldName);
                indexedFieldTypes.add(readString(in));
            }
        }

        int configCount = readCount(in, "field config");
        Map<String, FieldIndexConfig> configs = new LinkedHashMap<>();
        for (int i = 0; i < configCount; i++) {
            String fieldName = readString(in);
            FieldIndexConfig.IndexType indexType =
                    parseEnum(FieldIndexConfig.IndexType.class, readString(in), "index type");
            VectorAlgorithm algorithm = readNullableEnum(in, VectorAlgorithm.class, "algorithm");
            int dimension = in.readInt();
            String metric = readNullableString(in);
            BuiltinAnalyzer analyzer = readNullableEnum(in, BuiltinAnalyzer.class, "analyzer");
            ScalarFieldType scalarType = readNullableEnum(in, ScalarFieldType.class, "scalar type");

            int parameterCount = readCount(in, "algorithm parameter");
            Map<String, String> parameters = new LinkedHashMap<>();
            for (int j = 0; j < parameterCount; j++) {
                String parameterName = readString(in);
                String parameterValue = readString(in);
                if (parameters.put(parameterName, parameterValue) != null) {
                    throw new IOException(
                            "Duplicate algorithm parameter in es-index metadata for field '"
                                    + fieldName
                                    + "': "
                                    + parameterName);
                }
            }

            final FieldIndexConfig config;
            try {
                config =
                        FieldIndexConfig.builder(fieldName, indexType)
                                .algorithm(algorithm)
                                .dimension(dimension)
                                .metric(metric)
                                .analyzer(analyzer)
                                .scalarType(scalarType)
                                .algorithmParams(parameters)
                                .build();
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IOException(
                        "Invalid field config in es-index metadata for field '" + fieldName + "'",
                        e);
            }
            if (configs.put(fieldName, config) != null) {
                throw new IOException("Duplicate field config in es-index metadata: " + fieldName);
            }
        }

        int fileCount = readCount(in, "file");
        Map<String, long[]> offsets = readOffsets(in, fileCount);
        requireFullyConsumed(in);
        if (version == CONFIG_ONLY_VERSION) {
            indexedFieldNames = inferIndexedFieldNames(configs);
        }
        for (String fieldName : indexedFieldNames) {
            if (!configs.containsKey(fieldName)) {
                throw new IOException(
                        "Missing primary field config in es-index metadata: " + fieldName);
            }
        }
        return new Parsed(indexedFieldNames, indexedFieldTypes, configs, offsets, true, backend);
    }

    /**
     * Version 1 did not store the ordered primary-field list. Its writer emitted each primary
     * config immediately followed by its generated multi-fields, so recover that order for indexes
     * created before the explicit mapping was added.
     */
    private static List<String> inferIndexedFieldNames(Map<String, FieldIndexConfig> fieldConfigs) {
        List<Map.Entry<String, FieldIndexConfig>> entries =
                new ArrayList<>(fieldConfigs.entrySet());
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < entries.size(); ) {
            Map.Entry<String, FieldIndexConfig> primary = entries.get(i++);
            String fieldName = primary.getKey();
            fields.add(fieldName);
            if (primary.getValue().indexType() == FieldIndexConfig.IndexType.FULLTEXT
                    && i < entries.size()
                    && entries.get(i)
                            .getKey()
                            .equals(fieldName + ESIndexOptions.KEYWORD_SUBFIELD_SUFFIX)) {
                i++;
            } else if (primary.getValue().indexType() == FieldIndexConfig.IndexType.KEYWORD
                    && i < entries.size()
                    && entries.get(i)
                            .getKey()
                            .equals(fieldName + ESIndexOptions.FULLTEXT_SUBFIELD_SUFFIX)) {
                i++;
            }
            if (i < entries.size()
                    && entries.get(i)
                            .getKey()
                            .equals(fieldName + ESIndexOptions.ARRAY_PRESENCE_SUBFIELD_SUFFIX)) {
                i++;
            }
        }
        return fields;
    }

    private static void writeOffsets(DataOutputStream out, File[] files) throws IOException {
        out.writeInt(files.length);
        long offset = 4;
        for (File file : files) {
            byte[] name = file.getName().getBytes(StandardCharsets.UTF_8);
            long length = file.length();
            long dataOffset = offset + 4 + name.length + 8;
            out.writeInt(name.length);
            out.write(name);
            out.writeLong(dataOffset);
            out.writeLong(length);
            offset = dataOffset + length;
        }
    }

    private static Map<String, long[]> readOffsets(DataInputStream in, int fileCount)
            throws IOException {
        if (fileCount < 0) {
            throw new IOException("Negative file count in es-index metadata: " + fileCount);
        }
        Map<String, long[]> offsets = new LinkedHashMap<>();
        for (int i = 0; i < fileCount; i++) {
            String name = readString(in);
            long offset = in.readLong();
            long length = in.readLong();
            if (offset < 0 || length < 0 || offset > Long.MAX_VALUE - length) {
                throw new IOException("Invalid file offset/length in es-index metadata: " + name);
            }
            if (offsets.put(name, new long[] {offset, length}) != null) {
                throw new IOException("Duplicate file offset in es-index metadata: " + name);
            }
        }
        return offsets;
    }

    private static void requireFullyConsumed(DataInputStream in) throws IOException {
        if (in.available() != 0) {
            throw new IOException("Trailing bytes in es-index metadata: " + in.available());
        }
    }

    private static int readCount(DataInputStream in, String description) throws IOException {
        int count = in.readInt();
        if (count < 0) {
            throw new IOException(
                    "Negative " + description + " count in es-index metadata: " + count);
        }
        return count;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) {
            throw new EOFException("Invalid string length in es-index metadata: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeNullableString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            writeString(out, value);
        }
    }

    private static String readNullableString(DataInputStream in) throws IOException {
        return in.readBoolean() ? readString(in) : null;
    }

    private static void writeNullableEnum(DataOutputStream out, Enum<?> value) throws IOException {
        writeNullableString(out, value == null ? null : value.name());
    }

    private static <E extends Enum<E>> E readNullableEnum(
            DataInputStream in, Class<E> enumClass, String description) throws IOException {
        String value = readNullableString(in);
        return value == null ? null : parseEnum(enumClass, value, description);
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> enumClass, String value, String description) throws IOException {
        try {
            return Enum.valueOf(enumClass, value);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unknown " + description + " in es-index metadata: " + value, e);
        }
    }

    static final class Parsed {
        private final List<String> indexedFieldNames;
        private final List<String> indexedFieldTypes;
        private final Map<String, FieldIndexConfig> fieldConfigs;
        private final Map<String, long[]> fileOffsets;
        private final boolean hasFieldConfigs;
        private final String backend;

        private Parsed(
                List<String> indexedFieldNames,
                List<String> indexedFieldTypes,
                Map<String, FieldIndexConfig> fieldConfigs,
                Map<String, long[]> fileOffsets,
                boolean hasFieldConfigs) {
            this(
                    indexedFieldNames,
                    indexedFieldTypes,
                    fieldConfigs,
                    fileOffsets,
                    hasFieldConfigs,
                    ESIndexBuilderFactory.JAVA);
        }

        private Parsed(
                List<String> indexedFieldNames,
                List<String> indexedFieldTypes,
                Map<String, FieldIndexConfig> fieldConfigs,
                Map<String, long[]> fileOffsets,
                boolean hasFieldConfigs,
                String backend) {
            this.indexedFieldNames = indexedFieldNames;
            this.indexedFieldTypes = indexedFieldTypes;
            this.fieldConfigs = fieldConfigs;
            this.fileOffsets = fileOffsets;
            this.hasFieldConfigs = hasFieldConfigs;
            this.backend = backend;
        }

        List<String> indexedFieldNames() {
            return indexedFieldNames;
        }

        List<String> indexedFieldTypes() {
            return indexedFieldTypes;
        }

        Map<String, FieldIndexConfig> fieldConfigs() {
            return fieldConfigs;
        }

        Map<String, long[]> fileOffsets() {
            return fileOffsets;
        }

        boolean hasFieldConfigs() {
            return hasFieldConfigs;
        }

        String backend() {
            return backend;
        }
    }
}
