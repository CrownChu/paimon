/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import org.apache.paimon.eslib.internal.model.BuiltinAnalyzer;
import org.apache.paimon.eslib.internal.model.FieldIndexConfig;
import org.apache.paimon.eslib.internal.model.VectorAlgorithm;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Compiles one explicit native capability set; never changes an algorithm silently. */
final class NativeMapping {
    final Map<String, FieldIndexConfig> fields;
    private final Map<String, String> nativeNames = new TreeMap<>(NativeJson.UTF8_ORDER);
    final byte[] mapping, config;

    NativeMapping(Map<String, FieldIndexConfig> source) throws IOException {
        this(source, NativeIkDictionary.selected(source));
    }

    NativeMapping(Map<String, FieldIndexConfig> source, NativeIkDictionary dictionary)
            throws IOException {
        this(source, dictionary, false);
    }

    static NativeMapping physicalQueryFields(Map<String, FieldIndexConfig> source)
            throws IOException {
        return new NativeMapping(source, NativeIkDictionary.selected(source), true);
    }

    private NativeMapping(
            Map<String, FieldIndexConfig> source,
            NativeIkDictionary dictionary,
            boolean physicalQueryFields)
            throws IOException {
        if (source == null || source.isEmpty() || source.size() > 1024) {
            throw new IllegalArgumentException("Native fields must contain 1..1024 entries");
        }
        Map<String, FieldIndexConfig> selected = new TreeMap<>(NativeJson.UTF8_ORDER);
        Map<String, Object> properties = NativeJson.object(), configs = NativeJson.object();
        if (NativeIkDictionary.required(source)) {
            if (dictionary == null) {
                throw new IOException("Native IK dictionary identity is missing");
            }
            configs.put("__native_ik_dictionary", dictionary.files);
        }
        for (Map.Entry<String, FieldIndexConfig> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("Native field name is missing");
            }
            selected.put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, FieldIndexConfig> entry : selected.entrySet()) {
            String name = entry.getKey();
            FieldIndexConfig field = entry.getValue();
            if (name == null
                    || field == null
                    || !name.equals(field.fieldName())
                    || name.isEmpty()
                    || name.startsWith("_")
                    || name.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException(
                        "Native field names must be nonempty and non-reserved");
            }
            NativeJson.utf8(name);
            // Paimon writes complementary text/keyword columns under dotted logical names.
            // Keep those names in the API/config but store them as independent physical fields.
            String physicalName = name;
            if (!physicalQueryFields && name.indexOf('.') >= 0) {
                String base = "eslib_" + NativeJson.hex(NativeJson.digest(NativeJson.utf8(name)));
                physicalName = base;
                for (int suffix = 1;
                        source.containsKey(physicalName) || nativeNames.containsValue(physicalName);
                        suffix++) {
                    physicalName = base + "_" + suffix;
                }
            }
            nativeNames.put(name, physicalName);
            Map<String, Object> physical = physical(field);
            properties.put(physicalName, physical);
            configs.put(
                    name,
                    NativeJson.object(
                            "type",
                            field.indexType().name(),
                            "algorithm",
                            field.algorithm() == null ? null : field.algorithm().name(),
                            "dimension",
                            field.dimension(),
                            "metric",
                            field.metric(),
                            "parameters",
                            new TreeMap<>(field.algorithmParams()),
                            "analyzer",
                            field.analyzer() == null ? null : field.analyzer().name(),
                            "scalarType",
                            field.scalarType() == null ? null : field.scalarType().name()));
        }
        fields = Collections.unmodifiableMap(selected);
        mapping =
                NativeJson.encode(
                        NativeJson.object(
                                "_source",
                                NativeJson.object("enabled", false),
                                "properties",
                                properties));
        config = NativeJson.encode(configs);
        if (mapping.length > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("Native mapping exceeds 8 MiB");
        }
    }

    String nativeName(String logical) {
        field(logical);
        return nativeNames.get(logical);
    }

    private static Map<String, Object> physical(FieldIndexConfig field) {
        switch (field.indexType()) {
            case VECTOR:
                if (field.algorithm() != VectorAlgorithm.HNSW
                        && field.algorithm() != VectorAlgorithm.INT8_HNSW
                        && field.algorithm() != VectorAlgorithm.DISKBBQ) {
                    throw unsupported(
                            "Native vector support requires HNSW, INT8_HNSW or DISKBBQ; other"
                                    + " algorithms retain their explicit identity");
                }
                if (field.dimension() <= 0 || field.dimension() > 1024) {
                    throw unsupported("Native vector fields support 1..1024 dimensions");
                }
                if (!field.algorithmParams().isEmpty()) {
                    throw unsupported(
                            "Native vector tuning parameters require independent compatibility"
                                    + " validation");
                }
                return NativeJson.object(
                        "dims",
                        field.dimension(),
                        "index",
                        true,
                        "index_options",
                        NativeJson.object(
                                "type",
                                field.algorithm() == VectorAlgorithm.INT8_HNSW
                                        ? "int8_hnsw"
                                        : field.algorithm() == VectorAlgorithm.DISKBBQ
                                                ? "bbq_disk"
                                                : "hnsw"),
                        "similarity",
                        metric(field.metric()),
                        "type",
                        "dense_vector");
            case FULLTEXT:
                BuiltinAnalyzer analyzer =
                        field.analyzer() == null ? BuiltinAnalyzer.STANDARD : field.analyzer();
                return NativeJson.object("analyzer", analyzer.getName(), "type", "text");
            case KEYWORD:
                return NativeJson.object("type", "keyword");
            case DATE:
                return NativeJson.object("format", "epoch_millis", "type", "date");
            case SCALAR:
                if (field.scalarType() == null) {
                    throw new IllegalArgumentException("Native scalar type is missing");
                }
                switch (field.scalarType()) {
                    case INT:
                        return NativeJson.object("coerce", false, "type", "integer");
                    case LONG:
                        return NativeJson.object("coerce", false, "type", "long");
                    case FLOAT:
                        return NativeJson.object("coerce", false, "type", "float");
                    case DOUBLE:
                        return NativeJson.object("coerce", false, "type", "double");
                    case KEYWORD:
                        return NativeJson.object("type", "keyword");
                    case DATE:
                        return NativeJson.object("format", "epoch_millis", "type", "date");
                    default:
                        throw unsupported("Native geo mapping requires its own oracle validation");
                }
            default:
                throw unsupported("Native field type is not enabled: " + field.indexType());
        }
    }

    static String metric(String metric) {
        if ("cosine".equals(metric)) {
            return "cosine";
        }
        if ("l2".equals(metric) || "l2_norm".equals(metric) || "euclidean".equals(metric)) {
            return "l2_norm";
        }
        if ("ip".equals(metric) || "dot_product".equals(metric)) {
            return "dot_product";
        }
        throw unsupported("Native vector metric requires an explicit compatible identity");
    }

    FieldIndexConfig field(String name) {
        FieldIndexConfig value = fields.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Unknown native index field: " + name);
        }
        return value;
    }

    static void requireVector(FieldIndexConfig field, float[] vector) {
        if (field.indexType() != FieldIndexConfig.IndexType.VECTOR
                || vector == null
                || vector.length != field.dimension()) {
            throw new IllegalArgumentException("Native vector field or dimension differs");
        }
        double squared = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Native vectors must be finite");
            }
            squared += (double) value * value;
        }
        if ("cosine".equals(metric(field.metric())) && squared == 0) {
            throw new IllegalArgumentException("Cosine vector must be nonzero");
        }
        if ("dot_product".equals(metric(field.metric())) && Math.abs(squared - 1.0) >= 1e-3) {
            throw new IllegalArgumentException("Native dot-product vectors must be unit length");
        }
    }

    static UnsupportedOperationException unsupported(String message) {
        return new UnsupportedOperationException(message);
    }
}
