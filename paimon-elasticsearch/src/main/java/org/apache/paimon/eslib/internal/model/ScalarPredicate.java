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

/** Scalar filter predicate for scalar fields. */
public class ScalarPredicate {

    /** Typed scalar comparisons and set predicates. */
    public enum Op {
        EQUAL,
        NOT_EQUAL,
        LESS_THAN,
        LESS_OR_EQUAL,
        GREATER_THAN,
        GREATER_OR_EQUAL,
        IN,
        NOT_IN,
        RANGE
    }

    private final Op op;
    private final Object value;
    private final Object upperValue;
    private final List<Object> inValues;

    private ScalarPredicate(Op op, Object value, Object upperValue, List<Object> inValues) {
        this.op = Objects.requireNonNull(op, "op");
        if (op == Op.IN || op == Op.NOT_IN) {
            Objects.requireNonNull(inValues, "values");
            List<Object> copy = new ArrayList<>(inValues.size());
            for (Object inValue : inValues) {
                copy.add(Objects.requireNonNull(inValue, "IN value"));
            }
            this.inValues = Collections.unmodifiableList(copy);
            this.value = null;
            this.upperValue = null;
        } else {
            this.value = Objects.requireNonNull(value, "value");
            this.upperValue =
                    op == Op.RANGE ? Objects.requireNonNull(upperValue, "upperValue") : null;
            this.inValues = null;
        }
    }

    public Op op() {
        return op;
    }

    public Object value() {
        return value;
    }

    public Object upperValue() {
        return upperValue;
    }

    public List<Object> inValues() {
        return inValues;
    }

    public static ScalarPredicate eq(Object value) {
        return new ScalarPredicate(Op.EQUAL, value, null, null);
    }

    public static ScalarPredicate neq(Object value) {
        return new ScalarPredicate(Op.NOT_EQUAL, value, null, null);
    }

    public static ScalarPredicate lt(Object value) {
        return new ScalarPredicate(Op.LESS_THAN, value, null, null);
    }

    public static ScalarPredicate lte(Object value) {
        return new ScalarPredicate(Op.LESS_OR_EQUAL, value, null, null);
    }

    public static ScalarPredicate gt(Object value) {
        return new ScalarPredicate(Op.GREATER_THAN, value, null, null);
    }

    public static ScalarPredicate gte(Object value) {
        return new ScalarPredicate(Op.GREATER_OR_EQUAL, value, null, null);
    }

    public static ScalarPredicate in(List<Object> values) {
        return new ScalarPredicate(Op.IN, null, null, values);
    }

    public static ScalarPredicate notIn(List<Object> values) {
        return new ScalarPredicate(Op.NOT_IN, null, null, values);
    }

    public static ScalarPredicate range(Object lower, Object upper) {
        return new ScalarPredicate(Op.RANGE, lower, upper, null);
    }
}
