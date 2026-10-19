/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.internal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A typed, backend-neutral description of a full-text query that the ESLib searcher turns into a
 * Lucene query. Callers build this tree from their own query model (e.g. Paimon's FullTextQuery) so
 * ESLib never has to parse a wire format such as the JSON DSL.
 *
 * <p>Supported shapes:
 *
 * <ul>
 *   <li>{@link Match} — analyzed match on a single field (honours {@link FullTextParams}).
 *   <li>{@link Phrase} — phrase match with slop on a single field (needs indexed positions).
 *   <li>{@link Bool} — boolean combination of sub-queries (must / should / must-not).
 *   <li>{@link Boost} — a positive sub-query whose score is demoted by matches of a negative one.
 * </ul>
 *
 * <p>MultiMatch has no dedicated shape: a caller expands it into a {@link Bool} of per-field {@link
 * Match} clauses before handing it over.
 */
public abstract class FullTextQuerySpec {

    private FullTextQuerySpec() {}

    /** Analyzed match query on a single field. */
    public static final class Match extends FullTextQuerySpec {
        private final String field;
        private final String text;
        private final FullTextParams params;

        public Match(String field, String text, FullTextParams params) {
            this.field = requireField(field);
            this.text = Objects.requireNonNull(text, "text");
            this.params = params == null ? FullTextParams.defaults() : params;
        }

        public String field() {
            return field;
        }

        public String text() {
            return text;
        }

        public FullTextParams params() {
            return params;
        }
    }

    /** Phrase query with slop on a single field. */
    public static final class Phrase extends FullTextQuerySpec {
        private final String field;
        private final String text;
        private final int slop;

        public Phrase(String field, String text, int slop) {
            if (slop < 0) {
                throw new IllegalArgumentException("slop must be non-negative; got: " + slop);
            }
            this.field = requireField(field);
            this.text = Objects.requireNonNull(text, "text");
            this.slop = slop;
        }

        public String field() {
            return field;
        }

        public String text() {
            return text;
        }

        public int slop() {
            return slop;
        }
    }

    /** Boolean combination of sub-queries. */
    public static final class Bool extends FullTextQuerySpec {
        private final List<FullTextQuerySpec> must;
        private final List<FullTextQuerySpec> should;
        private final List<FullTextQuerySpec> mustNot;

        public Bool(
                List<FullTextQuerySpec> must,
                List<FullTextQuerySpec> should,
                List<FullTextQuerySpec> mustNot) {
            this.must = immutableSpecs(must, "must");
            this.should = immutableSpecs(should, "should");
            this.mustNot = immutableSpecs(mustNot, "mustNot");
        }

        public List<FullTextQuerySpec> must() {
            return must;
        }

        public List<FullTextQuerySpec> should() {
            return should;
        }

        public List<FullTextQuerySpec> mustNot() {
            return mustNot;
        }
    }

    /** A positive sub-query whose matching docs are demoted when they also match the negative. */
    public static final class Boost extends FullTextQuerySpec {
        private final FullTextQuerySpec positive;
        private final FullTextQuerySpec negative;
        private final float negativeBoost;

        public Boost(FullTextQuerySpec positive, FullTextQuerySpec negative, float negativeBoost) {
            if (!Float.isFinite(negativeBoost) || negativeBoost < 0.0f || negativeBoost > 1.0f) {
                throw new IllegalArgumentException(
                        "negativeBoost must be finite and between 0 and 1; got: " + negativeBoost);
            }
            this.positive = Objects.requireNonNull(positive, "positive");
            this.negative = Objects.requireNonNull(negative, "negative");
            this.negativeBoost = negativeBoost;
        }

        public FullTextQuerySpec positive() {
            return positive;
        }

        public FullTextQuerySpec negative() {
            return negative;
        }

        public float negativeBoost() {
            return negativeBoost;
        }
    }

    private static String requireField(String field) {
        if (field == null || field.isEmpty()) {
            throw new IllegalArgumentException("field must not be null or empty");
        }
        return field;
    }

    private static List<FullTextQuerySpec> immutableSpecs(
            List<FullTextQuerySpec> specs, String name) {
        if (specs == null || specs.isEmpty()) {
            return Collections.emptyList();
        }
        List<FullTextQuerySpec> copy = new ArrayList<>(specs.size());
        for (FullTextQuerySpec spec : specs) {
            copy.add(Objects.requireNonNull(spec, name + " clause"));
        }
        return Collections.unmodifiableList(copy);
    }
}
