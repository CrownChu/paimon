/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.internal.model;

/** Supported vector index algorithms. */
public enum VectorAlgorithm {

    /** IVF + BBQ quantization. Best for very large scale (millions+), low memory. */
    DISKBBQ("diskbbq"),

    /** Standard Lucene HNSW. General purpose, moderate scale. */
    HNSW("hnsw"),

    /** Lucene HNSW backed by scalar-quantized vectors (int8 storage). */
    INT8_HNSW("int8_hnsw"),

    /**
     * Havenask proxima native engine (JNI to C++ library). Supports multiple sub-algorithms via
     * builder_name parameter: HnswBuilder, QcBuilder, LinearBuilder, etc. Requires native library
     * (libha_indexlib.so).
     */
    NATIVE("native");

    private final String name;

    VectorAlgorithm(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static VectorAlgorithm fromName(String name) {
        for (VectorAlgorithm algo : values()) {
            if (algo.name.equalsIgnoreCase(name)) {
                return algo;
            }
        }
        throw new IllegalArgumentException(
                "Unknown vector algorithm: "
                        + name
                        + ". Supported: diskbbq, hnsw, int8_hnsw, native");
    }
}
