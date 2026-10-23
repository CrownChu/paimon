/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.internal;

import org.apache.paimon.eslib.internal.model.FieldIndexConfig;
import org.apache.paimon.eslib.internal.model.ScalarFieldType;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Unified index builder supporting vector, fulltext, and scalar fields.
 *
 * <p>Usage: create(configs) → addVector/addTextField/addScalarField (loop) → build() →
 * getOutputDir() → close()
 */
public interface ESIndexBuilder extends Closeable {

    /**
     * Add a vector value for the given field and document.
     *
     * @param fieldName field name (must match a vector-type config)
     * @param docId document ordinal (starting from 0)
     * @param vector float vector
     */
    void addVector(String fieldName, long docId, float[] vector) throws IOException;

    /**
     * Add a text value for full-text indexing.
     *
     * @param fieldName field name (must match a fulltext-type config)
     * @param docId document ordinal
     * @param text text content to be analyzed and indexed
     */
    void addTextField(String fieldName, long docId, String text) throws IOException;

    /**
     * Add a scalar value for range/term filtering.
     *
     * @param fieldName field name (must match a scalar-type config)
     * @param docId document ordinal
     * @param value the scalar value (Integer, Long, Float, Double, or String)
     * @param scalarType the scalar field type
     */
    void addScalarField(String fieldName, long docId, Object value, ScalarFieldType scalarType)
            throws IOException;

    /**
     * Register an empty document occupying {@code docId}'s slot, with no indexed field. Used for
     * null rows so the docId&lt;-&gt;rowId mapping stays dense (the read side maps positionally via
     * {@code rangeFrom + docId}) and the field is absent (so {@code notExists}/IS NULL can see it).
     * Idempotent per docId; {@code flushPendingDocs} is the back-stop for any docId never passed
     * here.
     *
     * @param docId document ordinal (shard-relative row id)
     */
    void addNullDoc(long docId) throws IOException;

    /**
     * Finish all fields for {@code docId} and make the document eligible for immediate flushing.
     * Callers that add documents in non-decreasing row-id order should invoke this once per row so
     * the builder does not retain every Lucene {@code Document} until {@link #build()}. The default
     * no-op keeps older builder implementations and callers source-compatible; {@link #build()}
     * remains the final back-stop.
     */
    default void finishDocument(long docId) throws IOException {}

    /**
     * Build the index. Force-merges to a single segment internally. Call after all documents have
     * been added.
     */
    void build() throws IOException;

    /**
     * Get the output directory containing all built index files (Lucene segment files). Valid only
     * after build() completes.
     */
    Path getOutputDir();

    /** Factory method to create a builder with field configurations. */
    static ESIndexBuilder create(Map<String, FieldIndexConfig> fieldConfigs) throws IOException {
        throw new UnsupportedOperationException("Use the Paimon ESIndexBuilderFactory");
    }
}
