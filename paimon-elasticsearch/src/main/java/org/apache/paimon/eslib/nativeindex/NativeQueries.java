/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import org.apache.paimon.eslib.internal.model.FieldIndexConfig;
import org.apache.paimon.eslib.internal.model.FullTextParams;
import org.apache.paimon.eslib.internal.model.FullTextQuerySpec;
import org.apache.paimon.eslib.internal.model.IndexFilter;
import org.apache.paimon.eslib.internal.model.ScalarPredicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts backend-neutral DTOs to native DSL; it does not evaluate or score queries. */
final class NativeQueries {
    private final NativeMapping mapping;

    NativeQueries(NativeMapping mapping) {
        this.mapping = mapping;
    }

    Object fulltext(FullTextQuerySpec spec) {
        return fulltext(spec, 0);
    }

    private Object fulltext(FullTextQuerySpec spec, int depth) {
        if (spec == null || depth >= 64) {
            throw new IllegalArgumentException("Invalid native text query depth");
        }
        if (spec instanceof FullTextQuerySpec.Match) {
            FullTextQuerySpec.Match match = (FullTextQuerySpec.Match) spec;
            textField(match.field());
            FullTextParams params = match.params();
            if (params.fuzziness() != null && params.fuzziness() != 0) {
                throw NativeMapping.unsupported(
                        "Native fuzzy match has not passed the ESLib compatibility matrix");
            }
            NativeJson.utf8(match.text());
            return NativeJson.object(
                    "match",
                    NativeJson.object(
                            mapping.nativeName(match.field()),
                            NativeJson.object(
                                    "query",
                                    match.text(),
                                    "operator",
                                    params.operator() == FullTextParams.Operator.AND ? "and" : "or",
                                    "boost",
                                    params.boost())));
        }
        if (spec instanceof FullTextQuerySpec.Phrase) {
            FullTextQuerySpec.Phrase phrase = (FullTextQuerySpec.Phrase) spec;
            textField(phrase.field());
            NativeJson.utf8(phrase.text());
            return NativeJson.object(
                    "match_phrase",
                    NativeJson.object(
                            mapping.nativeName(phrase.field()),
                            NativeJson.object("query", phrase.text(), "slop", phrase.slop())));
        }
        if (spec instanceof FullTextQuerySpec.Bool) {
            FullTextQuerySpec.Bool bool = (FullTextQuerySpec.Bool) spec;
            Map<String, Object> clauses = NativeJson.object();
            if (!bool.must().isEmpty()) {
                clauses.put("must", children(bool.must(), depth));
            }
            if (!bool.should().isEmpty()) {
                clauses.put("should", children(bool.should(), depth));
            }
            if (!bool.mustNot().isEmpty()) {
                clauses.put("must_not", children(bool.mustNot(), depth));
            }
            if (clauses.isEmpty()) {
                return NativeJson.object("match_all", NativeJson.object());
            }
            if (bool.must().isEmpty() && bool.should().isEmpty()) {
                clauses.put("must", NativeJson.object("match_all", NativeJson.object()));
            }
            return NativeJson.object("bool", clauses);
        }
        if (spec instanceof FullTextQuerySpec.Boost) {
            FullTextQuerySpec.Boost boost = (FullTextQuerySpec.Boost) spec;
            return NativeJson.object(
                    "boosting",
                    NativeJson.object(
                            "positive",
                            fulltext(boost.positive(), depth + 1),
                            "negative",
                            fulltext(boost.negative(), depth + 1),
                            "negative_boost",
                            boost.negativeBoost()));
        }
        throw NativeMapping.unsupported("Native text query type is not enabled");
    }

    private List<Object> children(List<FullTextQuerySpec> values, int depth) {
        if (values.size() > 1024) {
            throw new IllegalArgumentException("Native clause count exceeds its limit");
        }
        List<Object> result = new ArrayList<>();
        for (FullTextQuerySpec spec : values) {
            result.add(fulltext(spec, depth + 1));
        }
        return result;
    }

    private void textField(String field) {
        if (mapping.field(field).indexType() != FieldIndexConfig.IndexType.FULLTEXT) {
            throw new IllegalArgumentException("Native full-text field differs");
        }
    }

    Object filter(String field, IndexFilter filter) {
        field = mapping.nativeName(field);
        if (filter == null) {
            throw new IllegalArgumentException("Missing native filter");
        }
        if (filter instanceof IndexFilter.ScalarFilter) {
            return scalar(field, ((IndexFilter.ScalarFilter) filter).predicate());
        }
        if (filter instanceof IndexFilter.ExistsFilter) {
            Object exists = NativeJson.object("exists", NativeJson.object("field", field));
            return ((IndexFilter.ExistsFilter) filter).mustExist() ? exists : negate(exists);
        }
        if (filter instanceof IndexFilter.TextFilter) {
            IndexFilter.TextFilter text = (IndexFilter.TextFilter) filter;
            NativeJson.utf8(text.value());
            switch (text.op()) {
                case TERM:
                    return NativeJson.object("term", NativeJson.object(field, text.value()));
                case PREFIX:
                    return NativeJson.object("prefix", NativeJson.object(field, text.value()));
                case WILDCARD:
                    return NativeJson.object("wildcard", NativeJson.object(field, text.value()));
                case MATCH:
                    return NativeJson.object("match", NativeJson.object(field, text.value()));
                default:
                    throw NativeMapping.unsupported(
                            "Native text filter is not validated: " + text.op());
            }
        }
        throw NativeMapping.unsupported("Native geo filter is not enabled by this bundle");
    }

    private static Object scalar(String field, ScalarPredicate predicate) {
        switch (predicate.op()) {
            case EQUAL:
                return NativeJson.object(
                        "term", NativeJson.object(field, scalarValue(predicate.value())));
            case NOT_EQUAL:
                return existingAndNot(
                        field,
                        NativeJson.object(
                                "term", NativeJson.object(field, scalarValue(predicate.value()))));
            case IN:
            case NOT_IN:
                if (predicate.inValues().size() > 1000000) {
                    throw new IllegalArgumentException("Native IN exceeds its limit");
                }
                List<Object> values = new ArrayList<>();
                for (Object value : predicate.inValues()) {
                    values.add(scalarValue(value));
                }
                Object terms = NativeJson.object("terms", NativeJson.object(field, values));
                return predicate.op() == ScalarPredicate.Op.NOT_IN
                        ? existingAndNot(field, terms)
                        : terms;
            case RANGE:
                return NativeJson.object(
                        "range",
                        NativeJson.object(
                                field,
                                NativeJson.object(
                                        "gte",
                                        scalarValue(predicate.value()),
                                        "lte",
                                        scalarValue(predicate.upperValue()))));
            default:
                String bound;
                switch (predicate.op()) {
                    case LESS_THAN:
                        bound = "lt";
                        break;
                    case LESS_OR_EQUAL:
                        bound = "lte";
                        break;
                    case GREATER_THAN:
                        bound = "gt";
                        break;
                    case GREATER_OR_EQUAL:
                        bound = "gte";
                        break;
                    default:
                        throw NativeMapping.unsupported("Native scalar predicate is not enabled");
                }
                return NativeJson.object(
                        "range",
                        NativeJson.object(
                                field, NativeJson.object(bound, scalarValue(predicate.value()))));
        }
    }

    static Object scalarValue(Object value) {
        if (value instanceof String) {
            NativeJson.utf8((String) value);
            return value;
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Float && Float.isFinite((Float) value)) {
            return value;
        }
        if (value instanceof Double && Double.isFinite((Double) value)) {
            return value;
        }
        throw new IllegalArgumentException(
                "Native predicate value must have an explicit finite scalar type");
    }

    private static Object negate(Object query) {
        return NativeJson.object(
                "bool",
                NativeJson.object(
                        "must",
                        NativeJson.object("match_all", NativeJson.object()),
                        "must_not",
                        query));
    }

    // DefaultESIndexSearcher.existingAndNot excludes NULL/missing fields.
    private static Object existingAndNot(String field, Object query) {
        return NativeJson.object(
                "bool",
                NativeJson.object(
                        "must",
                        NativeJson.object("exists", NativeJson.object("field", field)),
                        "must_not",
                        query));
    }
}
