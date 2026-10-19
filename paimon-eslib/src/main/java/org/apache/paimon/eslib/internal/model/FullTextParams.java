/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.internal.model;

/**
 * Parameters for a full-text {@code match} query, mirroring the knobs of an Elasticsearch match
 * query so a caller can request non-default operator / scoring / fuzzy behaviour.
 *
 * <ul>
 *   <li>{@code operator} — how analyzed tokens are combined: OR (any) or AND (all).
 *   <li>{@code boost} — score multiplier for this query (1.0 = no boost).
 *   <li>{@code fuzziness} — max Levenshtein edit distance per token (null or 0 = exact, -1 = AUTO).
 *   <li>{@code maxExpansions} — terms-enum expansion cap for fuzzy matching.
 *   <li>{@code prefixLength} — leading characters that must match exactly before fuzzy matching.
 * </ul>
 */
public final class FullTextParams {

    /** Sentinel for Elasticsearch-style AUTO fuzziness (0/1/2 edits by analyzed token length). */
    public static final int AUTO_FUZZINESS = -1;

    /** Combination operator for the analyzed query tokens. */
    public enum Operator {
        OR,
        AND
    }

    private final Operator operator;
    private final float boost;
    private final Integer fuzziness;
    private final int maxExpansions;
    private final int prefixLength;

    public FullTextParams(
            Operator operator,
            float boost,
            Integer fuzziness,
            int maxExpansions,
            int prefixLength) {
        this.operator = operator == null ? Operator.OR : operator;
        if (!Float.isFinite(boost) || boost < 0.0f) {
            throw new IllegalArgumentException(
                    "boost must be finite and non-negative; got: " + boost);
        }
        this.boost = boost;
        if (fuzziness != null && fuzziness != AUTO_FUZZINESS && (fuzziness < 0 || fuzziness > 2)) {
            throw new IllegalArgumentException(
                    "fuzziness must be 0, 1, 2, or AUTO; got: " + fuzziness);
        }
        if (maxExpansions <= 0) {
            throw new IllegalArgumentException(
                    "maxExpansions must be positive; got: " + maxExpansions);
        }
        if (prefixLength < 0) {
            throw new IllegalArgumentException(
                    "prefixLength must be non-negative; got: " + prefixLength);
        }
        this.fuzziness = fuzziness;
        this.maxExpansions = maxExpansions;
        this.prefixLength = prefixLength;
    }

    /** Default match parameters: OR, no boost, exact (no fuzziness), maxExpansions=50. */
    public static FullTextParams defaults() {
        return new FullTextParams(Operator.OR, 1.0f, 0, 50, 0);
    }

    public Operator operator() {
        return operator;
    }

    public float boost() {
        return boost;
    }

    public Integer fuzziness() {
        return fuzziness;
    }

    public int maxExpansions() {
        return maxExpansions;
    }

    public int prefixLength() {
        return prefixLength;
    }
}
