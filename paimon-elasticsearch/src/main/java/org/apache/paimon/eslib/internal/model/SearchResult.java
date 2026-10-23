/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.internal.model;

import java.util.Objects;

/** Search result containing document IDs and their scores. */
public class SearchResult {

    public final long[] ids;
    public final float[] scores;
    public final int count;

    public SearchResult(long[] ids, float[] scores, int count) {
        this.ids = Objects.requireNonNull(ids, "ids");
        this.scores = Objects.requireNonNull(scores, "scores");
        if (count < 0 || count > ids.length || count > scores.length) {
            throw new IllegalArgumentException(
                    "count must be between 0 and both result-array lengths; got count="
                            + count
                            + ", ids="
                            + ids.length
                            + ", scores="
                            + scores.length);
        }
        this.count = count;
    }

    public static final SearchResult EMPTY = new SearchResult(new long[0], new float[0], 0);
}
