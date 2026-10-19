/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.internal.model;

import java.util.Objects;

/**
 * Unified filter predicate hierarchy for all field types. Each subclass maps to a different Lucene
 * query strategy internally.
 */
public abstract class IndexFilter {

    /** The supported native filter families. */
    public enum FilterType {
        SCALAR,
        TEXT,
        GEO,
        EXISTS
    }

    public abstract FilterType filterType();

    // =================== Scalar filters (numeric point / keyword term) ===================

    /** A typed numeric or exact-value predicate. */
    public static class ScalarFilter extends IndexFilter {
        private final ScalarPredicate predicate;

        public ScalarFilter(ScalarPredicate predicate) {
            this.predicate = Objects.requireNonNull(predicate, "predicate");
        }

        @Override
        public FilterType filterType() {
            return FilterType.SCALAR;
        }

        public ScalarPredicate predicate() {
            return predicate;
        }
    }

    // =================== Text filters (analyzed / keyword queries) ===================

    /** A predicate over the indexed text or keyword value. */
    public static class TextFilter extends IndexFilter {
        /** Text predicate operations preserved by query translation. */
        public enum TextOp {
            TERM,
            PREFIX,
            WILDCARD,
            REGEXP,
            FUZZY,
            MATCH
        }

        private final TextOp op;
        private final String value;

        private TextFilter(TextOp op, String value) {
            this.op = Objects.requireNonNull(op, "op");
            this.value = Objects.requireNonNull(value, "value");
        }

        @Override
        public FilterType filterType() {
            return FilterType.TEXT;
        }

        public TextOp op() {
            return op;
        }

        public String value() {
            return value;
        }

        public static TextFilter term(String value) {
            return new TextFilter(TextOp.TERM, value);
        }

        public static TextFilter prefix(String value) {
            return new TextFilter(TextOp.PREFIX, value);
        }

        public static TextFilter wildcard(String pattern) {
            return new TextFilter(TextOp.WILDCARD, pattern);
        }

        public static TextFilter regexp(String pattern) {
            return new TextFilter(TextOp.REGEXP, pattern);
        }

        public static TextFilter fuzzy(String value) {
            return new TextFilter(TextOp.FUZZY, value);
        }

        public static TextFilter match(String queryText) {
            return new TextFilter(TextOp.MATCH, queryText);
        }
    }

    // =================== Geo filters (lat/lon bounding box, distance) ===================

    /** A geographic predicate requiring an explicit native capability. */
    public static class GeoFilter extends IndexFilter {
        /** The supported geographic predicate shapes. */
        public enum GeoOp {
            BOUNDING_BOX,
            DISTANCE
        }

        private final GeoOp op;
        private final double lat;
        private final double lon;
        private final double radiusMeters;
        private final double minLat;
        private final double maxLat;
        private final double minLon;
        private final double maxLon;

        private GeoFilter(
                GeoOp op,
                double lat,
                double lon,
                double radiusMeters,
                double minLat,
                double maxLat,
                double minLon,
                double maxLon) {
            this.op = Objects.requireNonNull(op, "op");
            if (op == GeoOp.DISTANCE) {
                validateLatitude(lat);
                validateLongitude(lon);
                if (!Double.isFinite(radiusMeters) || radiusMeters < 0.0d) {
                    throw new IllegalArgumentException(
                            "radiusMeters must be finite and non-negative; got: " + radiusMeters);
                }
            } else {
                validateLatitude(minLat);
                validateLatitude(maxLat);
                validateLongitude(minLon);
                validateLongitude(maxLon);
                if (minLat > maxLat) {
                    throw new IllegalArgumentException(
                            "minLat must not exceed maxLat: " + minLat + " > " + maxLat);
                }
            }
            this.lat = lat;
            this.lon = lon;
            this.radiusMeters = radiusMeters;
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLon = minLon;
            this.maxLon = maxLon;
        }

        @Override
        public FilterType filterType() {
            return FilterType.GEO;
        }

        public GeoOp op() {
            return op;
        }

        public double lat() {
            return lat;
        }

        public double lon() {
            return lon;
        }

        public double radiusMeters() {
            return radiusMeters;
        }

        public double minLat() {
            return minLat;
        }

        public double maxLat() {
            return maxLat;
        }

        public double minLon() {
            return minLon;
        }

        public double maxLon() {
            return maxLon;
        }

        public static GeoFilter distance(double lat, double lon, double radiusMeters) {
            return new GeoFilter(GeoOp.DISTANCE, lat, lon, radiusMeters, 0, 0, 0, 0);
        }

        public static GeoFilter boundingBox(
                double minLat, double maxLat, double minLon, double maxLon) {
            return new GeoFilter(GeoOp.BOUNDING_BOX, 0, 0, 0, minLat, maxLat, minLon, maxLon);
        }

        private static void validateLatitude(double latitude) {
            if (!Double.isFinite(latitude) || latitude < -90.0d || latitude > 90.0d) {
                throw new IllegalArgumentException("Invalid latitude: " + latitude);
            }
        }

        private static void validateLongitude(double longitude) {
            if (!Double.isFinite(longitude) || longitude < -180.0d || longitude > 180.0d) {
                throw new IllegalArgumentException("Invalid longitude: " + longitude);
            }
        }
    }

    // =================== Convenience factory methods ===================

    public static ScalarFilter scalar(ScalarPredicate predicate) {
        return new ScalarFilter(predicate);
    }

    public static TextFilter text(TextFilter.TextOp op, String value) {
        return new TextFilter(op, value);
    }

    public static GeoFilter geoDistance(double lat, double lon, double radiusMeters) {
        return GeoFilter.distance(lat, lon, radiusMeters);
    }

    public static GeoFilter geoBoundingBox(
            double minLat, double maxLat, double minLon, double maxLon) {
        return GeoFilter.boundingBox(minLat, maxLat, minLon, maxLon);
    }

    // =================== Exists filter (field not null) ===================

    /** Tests whether the indexed field has a value. */
    public static class ExistsFilter extends IndexFilter {
        private final boolean mustExist;

        private ExistsFilter(boolean mustExist) {
            this.mustExist = mustExist;
        }

        @Override
        public FilterType filterType() {
            return FilterType.EXISTS;
        }

        public boolean mustExist() {
            return mustExist;
        }
    }

    public static ExistsFilter exists() {
        return new ExistsFilter(true);
    }

    public static ExistsFilter notExists() {
        return new ExistsFilter(false);
    }
}
