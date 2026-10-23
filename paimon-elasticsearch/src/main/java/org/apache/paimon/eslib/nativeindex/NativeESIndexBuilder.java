/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import org.apache.paimon.eslib.internal.ESIndexBuilder;
import org.apache.paimon.eslib.internal.model.FieldIndexConfig;
import org.apache.paimon.eslib.internal.model.ScalarFieldType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Single-owner native builder. Completed rows stream in bounded batches to C++. */
public final class NativeESIndexBuilder implements ESIndexBuilder {
    private final NativeMapping mapping;
    private final NativeClient client;
    private final Path output;
    private final String index = "eslib-" + UUID.randomUUID();
    private final TreeMap<Long, Map<String, Object>> pending = new TreeMap<>();
    private final List<byte[]> batch = new ArrayList<>(128);
    private long nextRow, maximumRow = -1, firstBatchRow, batchBytes, sequence;
    private boolean nativeCreated, built, closed, failed;

    public NativeESIndexBuilder(Map<String, FieldIndexConfig> fields) throws IOException {
        this(new NativeMapping(fields), null, null);
    }

    public NativeESIndexBuilder(Map<String, FieldIndexConfig> fields, Path output)
            throws IOException {
        this(new NativeMapping(fields), output, null);
    }

    NativeESIndexBuilder(NativeMapping mapping, Path output, NativeClient injected)
            throws IOException {
        this.mapping = mapping;
        this.output =
                output == null
                        ? Files.createTempDirectory("eslib-native-index-")
                        : output.toAbsolutePath().normalize();
        Files.createDirectories(this.output);
        try (java.util.stream.Stream<Path> files = Files.list(this.output)) {
            if (files.findAny().isPresent()) {
                throw new IOException("Native output directory must be empty");
            }
        }
        client = injected == null ? NativeRuntime.load().client() : injected;
        try {
            nativeCreated = true;
            client.create(index, this.output, mapping.mapping);
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            try {
                close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private Map<String, Object> document(long row) {
        requireWriting();
        if (row < nextRow || row < 0 || row >= 2147483519L) {
            throw new IllegalArgumentException(
                    "Native row is negative, out of range or already completed");
        }
        maximumRow = Math.max(maximumRow, row);
        return pending.computeIfAbsent(row, ignored -> NativeJson.object());
    }

    private void value(String field, long row, Object value) {
        field = mapping.nativeName(field);
        Map<String, Object> document = document(row);
        if (document.containsKey(field)) {
            throw new IllegalArgumentException(
                    "Native duplicate field values require explicit multi-value compatibility");
        }
        document.put(field, value);
    }

    @Override
    public void addVector(String field, long row, float[] vector) {
        FieldIndexConfig config = mapping.field(field);
        NativeMapping.requireVector(config, vector);
        value(field, row, vector.clone());
    }

    @Override
    public void addTextField(String field, long row, String text) {
        if (mapping.field(field).indexType() != FieldIndexConfig.IndexType.FULLTEXT
                || text == null) {
            throw new IllegalArgumentException("Native text field or value differs");
        }
        NativeJson.utf8(text);
        value(field, row, text);
    }

    @Override
    public void addScalarField(String field, long row, Object value, ScalarFieldType type) {
        FieldIndexConfig config = mapping.field(field);
        ScalarFieldType expected =
                config.indexType() == FieldIndexConfig.IndexType.KEYWORD
                        ? ScalarFieldType.KEYWORD
                        : config.indexType() == FieldIndexConfig.IndexType.DATE
                                ? ScalarFieldType.DATE
                                : config.scalarType();
        if (type == null
                || expected != type
                || value == null
                || config.indexType() == FieldIndexConfig.IndexType.VECTOR
                || config.indexType() == FieldIndexConfig.IndexType.FULLTEXT) {
            throw new IllegalArgumentException("Native scalar type differs");
        }
        if (value instanceof Object[]) {
            List<Object> values = new ArrayList<>();
            for (Object element : (Object[]) value) {
                if (element != null) {
                    values.add(normalizeScalar(element, type));
                }
            }
            value(field, row, values);
        } else {
            value(field, row, normalizeScalar(value, type));
        }
    }

    private static Object normalizeScalar(Object value, ScalarFieldType type) {
        Object normalized;
        switch (type) {
            case INT:
                if (!(value instanceof Integer
                        || value instanceof Short
                        || value instanceof Byte)) {
                    throw new IllegalArgumentException(
                            "Native INT requires a 32-bit integral value");
                }
                normalized = ((Number) value).intValue();
                break;
            case LONG:
            case DATE:
                if (!(value instanceof Long
                        || value instanceof Integer
                        || value instanceof Short
                        || value instanceof Byte)) {
                    throw new IllegalArgumentException(
                            "Native LONG/DATE requires an integral value");
                }
                normalized = ((Number) value).longValue();
                break;
            case FLOAT:
                if (!(value instanceof Number) || !Float.isFinite(((Number) value).floatValue())) {
                    throw new IllegalArgumentException("Native FLOAT requires a finite value");
                }
                normalized = ((Number) value).floatValue();
                break;
            case DOUBLE:
                if (!(value instanceof Number)
                        || !Double.isFinite(((Number) value).doubleValue())) {
                    throw new IllegalArgumentException("Native DOUBLE requires a finite value");
                }
                normalized = ((Number) value).doubleValue();
                break;
            case KEYWORD:
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException("Native keyword requires text");
                }
                NativeJson.utf8((String) value);
                normalized = value;
                break;
            default:
                throw NativeMapping.unsupported("Native scalar value is not enabled: " + type);
        }
        return normalized;
    }

    @Override
    public void addNullDoc(long row) {
        requireWriting();
        if (row >= 0 && row < nextRow) {
            return;
        } // Re-registering a completed NULL slot is idempotent.
        document(row);
    }

    @Override
    public void finishDocument(long row) throws IOException {
        requireWriting();
        if (row < nextRow || row < 0 || row > maximumRow) {
            throw new IllegalArgumentException("Native completed row is not pending");
        }
        if (!pending.headMap(row, false).isEmpty()) {
            throw new IllegalArgumentException(
                    "finishDocument requires non-decreasing completed rows");
        }
        try {
            emitThrough(row);
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    private void emitThrough(long last) throws IOException {
        while (nextRow <= last) {
            Map<String, Object> document = pending.remove(nextRow);
            byte[] bytes = NativeJson.encode(document == null ? NativeJson.object() : document);
            if (bytes.length > 8 * 1024 * 1024) {
                throw new IOException("Native document exceeds 8 MiB");
            }
            if (!batch.isEmpty()
                    && (batch.size() == 128 || batchBytes + bytes.length > 4L * 1024 * 1024)) {
                flush();
            }
            if (batch.isEmpty()) {
                firstBatchRow = nextRow;
            }
            batch.add(bytes);
            batchBytes += bytes.length;
            nextRow++;
            if (batch.size() == 128 || batchBytes >= 4L * 1024 * 1024) {
                flush();
            }
        }
    }

    private void flush() throws IOException {
        if (batch.isEmpty()) {
            return;
        }
        client.bulk(index, "bulk-" + sequence++, firstBatchRow, batch);
        batch.clear();
        batchBytes = 0;
    }

    @Override
    public void build() throws IOException {
        if (built && !closed) {
            return;
        }
        requireWriting();
        try {
            emitThrough(maximumRow);
            flush();
            client.commit(index, "commit-" + sequence++);
            NativeIndexManifest manifest =
                    client.seal(index, output, nextRow, mapping.mapping, mapping.config);
            nativeCreated = false;
            Files.write(
                    output.resolve(NativeIndexManifest.NAME),
                    manifest.encode(),
                    StandardOpenOption.CREATE_NEW);
            built = true;
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    @Override
    public Path getOutputDir() {
        if (!built) {
            throw new IllegalStateException("Native index is not built");
        }
        return output;
    }

    private void requireWriting() {
        if (closed || failed || built) {
            throw new IllegalStateException("Native builder is closed, failed or sealed");
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        if (nativeCreated) {
            client.remove(index);
            nativeCreated = false;
        }
        pending.clear();
        batch.clear();
        closed = true;
        // Published files belong to the caller and are never deleted by builder close.
    }
}
