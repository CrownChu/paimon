/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.internal.model;

/** Built-in text analyzers available in ESLib. */
public enum BuiltinAnalyzer {

    /** Lucene StandardAnalyzer - unicode text segmentation. */
    STANDARD("standard"),

    /** Split by whitespace only. */
    WHITESPACE("whitespace"),

    /** Lucene SimpleAnalyzer - splits on non-letter, lowercases. */
    SIMPLE("simple"),

    /** No tokenization - whole value as single token. */
    KEYWORD("keyword"),

    /** IK smart segmentation for Chinese text. */
    IK_SMART("ik_smart"),

    /** IK max-word segmentation for Chinese text (finest granularity). */
    IK_MAX_WORD("ik_max_word");

    private final String name;

    BuiltinAnalyzer(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static BuiltinAnalyzer fromName(String name) {
        for (BuiltinAnalyzer a : values()) {
            if (a.name.equalsIgnoreCase(name)) {
                return a;
            }
        }
        throw new IllegalArgumentException(
                "Unknown analyzer: "
                        + name
                        + ". Supported: standard, whitespace, simple, keyword, ik_smart, ik_max_word");
    }
}
