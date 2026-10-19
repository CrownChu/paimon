/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.internal.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Configuration for a single field's index. */
public class FieldIndexConfig {

    /** The physical field category stored in the index configuration. */
    public enum IndexType {
        VECTOR,
        FULLTEXT,
        SCALAR,
        KEYWORD,
        GEO_POINT,
        DATE
    }

    private final String fieldName;
    private final IndexType indexType;

    // Vector fields
    private final VectorAlgorithm algorithm;
    private final int dimension;
    private final String metric;
    private final Map<String, String> algorithmParams;

    // Fulltext fields
    private final BuiltinAnalyzer analyzer;

    // Scalar fields
    private final ScalarFieldType scalarType;

    private FieldIndexConfig(Builder builder) {
        this.fieldName = requireFieldName(builder.fieldName);
        this.indexType = Objects.requireNonNull(builder.indexType, "indexType");
        if (builder.dimension < 0
                || (builder.indexType == IndexType.VECTOR && builder.dimension == 0)) {
            throw new IllegalArgumentException(
                    "Vector dimension must be positive for field '"
                            + builder.fieldName
                            + "'; got: "
                            + builder.dimension);
        }
        this.algorithm = builder.algorithm;
        this.dimension = builder.dimension;
        this.metric = builder.metric;
        this.algorithmParams = immutableParameters(builder.algorithmParams);
        this.analyzer = builder.analyzer;
        this.scalarType = builder.scalarType;
    }

    public String fieldName() {
        return fieldName;
    }

    public IndexType indexType() {
        return indexType;
    }

    public VectorAlgorithm getAlgorithm() {
        return algorithm;
    }

    public VectorAlgorithm algorithm() {
        return algorithm;
    }

    public int dimension() {
        return dimension;
    }

    public String metric() {
        return metric;
    }

    public Map<String, String> algorithmParams() {
        return algorithmParams;
    }

    public BuiltinAnalyzer analyzer() {
        return analyzer;
    }

    public ScalarFieldType scalarType() {
        return scalarType;
    }

    public int getIntParam(String key, int defaultValue) {
        String val = algorithmParams.get(key);
        if (val == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Algorithm parameter '"
                            + key
                            + "' for field '"
                            + fieldName
                            + "' must be an integer; got: "
                            + val,
                    e);
        }
    }

    public String getStringParam(String key, String defaultValue) {
        return algorithmParams.getOrDefault(key, defaultValue);
    }

    public static Builder builder(String fieldName, IndexType indexType) {
        return new Builder(fieldName, indexType);
    }

    private static String requireFieldName(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            throw new IllegalArgumentException("fieldName must not be null or empty");
        }
        return fieldName;
    }

    private static Map<String, String> immutableParameters(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> copy = new LinkedHashMap<>(parameters.size());
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "algorithm parameter name");
            String value = Objects.requireNonNull(entry.getValue(), "algorithm parameter value");
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Builds an immutable field configuration. */
    public static class Builder {
        private final String fieldName;
        private final IndexType indexType;
        private VectorAlgorithm algorithm;
        private int dimension;
        private String metric = "l2";
        private Map<String, String> algorithmParams;
        private BuiltinAnalyzer analyzer;
        private ScalarFieldType scalarType;

        Builder(String fieldName, IndexType indexType) {
            this.fieldName = fieldName;
            this.indexType = indexType;
        }

        public Builder algorithm(VectorAlgorithm algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public Builder dimension(int dimension) {
            this.dimension = dimension;
            return this;
        }

        public Builder metric(String metric) {
            this.metric = metric;
            return this;
        }

        public Builder algorithmParams(Map<String, String> params) {
            this.algorithmParams = params;
            return this;
        }

        public Builder analyzer(BuiltinAnalyzer analyzer) {
            this.analyzer = analyzer;
            return this;
        }

        public Builder scalarType(ScalarFieldType scalarType) {
            this.scalarType = scalarType;
            return this;
        }

        public FieldIndexConfig build() {
            return new FieldIndexConfig(this);
        }
    }
}
