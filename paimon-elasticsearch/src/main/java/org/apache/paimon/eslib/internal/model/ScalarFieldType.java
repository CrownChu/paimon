/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.internal.model;

/** Supported scalar field types for range/term filtering. */
public enum ScalarFieldType {
    INT("int"),
    LONG("long"),
    FLOAT("float"),
    DOUBLE("double"),
    KEYWORD("keyword"),
    DATE("date"),
    GEO_POINT("geo_point");

    private final String name;

    ScalarFieldType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static ScalarFieldType fromName(String name) {
        for (ScalarFieldType type : values()) {
            if (type.name.equalsIgnoreCase(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "Unknown scalar field type: "
                        + name
                        + ". Supported: int, long, float, double, keyword");
    }
}
