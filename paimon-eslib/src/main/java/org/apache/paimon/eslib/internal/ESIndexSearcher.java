/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.internal;

import org.apache.paimon.eslib.internal.model.FieldIndexConfig;
import org.apache.paimon.eslib.internal.model.FullTextParams;
import org.apache.paimon.eslib.internal.model.FullTextQuerySpec;
import org.apache.paimon.eslib.internal.model.IndexFilter;
import org.apache.paimon.eslib.internal.model.ScalarPredicate;
import org.apache.paimon.eslib.internal.model.SearchResult;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Unified index searcher supporting vector, fulltext, and scalar queries.
 *
 * <p>Usage: load(provider, offsets, configs) → vectorSearch/fullTextSearch/scalarFilter (loop) →
 * close()
 */
public interface ESIndexSearcher extends Closeable {

    /**
     * Load index data. Internally decides what to preload vs lazy-load per field config.
     *
     * @param dataProvider callback to read byte ranges from the archive
     * @param fileOffsets map of filename → [offset, length] in the archive
     * @param fieldConfigs per-field index configurations
     */
    void load(
            ArchiveDataProvider dataProvider,
            Map<String, long[]> fileOffsets,
            Map<String, FieldIndexConfig> fieldConfigs)
            throws IOException;

    /**
     * Load index data with a shared thread pool for parallel cluster search.
     *
     * @param dataProvider callback to read byte ranges from the archive
     * @param fileOffsets map of filename → [offset, length] in the archive
     * @param fieldConfigs per-field index configurations
     * @param searchExecutor shared thread pool for parallel search (null = serial only)
     */
    default void load(
            ArchiveDataProvider dataProvider,
            Map<String, long[]> fileOffsets,
            Map<String, FieldIndexConfig> fieldConfigs,
            ExecutorService searchExecutor)
            throws IOException {
        load(dataProvider, fileOffsets, fieldConfigs);
    }

    /**
     * KNN vector search on a vector field.
     *
     * @param fieldName the vector field to search
     * @param queryVector query vector
     * @param topK number of results to return
     * @param candidateIds pre-filter row IDs (null means no filter)
     * @return search results with IDs and scores
     */
    SearchResult vectorSearch(String fieldName, float[] queryVector, int topK, long[] candidateIds)
            throws IOException;

    /**
     * Full-text search on a text field.
     *
     * @param fieldName the text field to search
     * @param queryText query text
     * @param topK number of results to return
     * @return search results with IDs and BM25 scores
     */
    SearchResult fullTextSearch(String fieldName, String queryText, int topK) throws IOException;

    /**
     * Full-text {@code match} search with explicit parameters (operator, boost, fuzziness,
     * maxExpansions, prefixLength). The query text is analyzed into tokens which are combined per
     * {@link FullTextParams#operator()}; fuzziness &gt; 0 turns each token into a fuzzy query.
     *
     * <p>The default implementation ignores the parameters and falls back to {@link
     * #fullTextSearch(String, String, int)} so existing implementations keep compiling; callers
     * that need the parameters honoured must use an implementation that overrides this method.
     *
     * @param fieldName the text field to search
     * @param queryText query text (analyzed)
     * @param topK number of results to return
     * @param params match parameters
     * @return search results with IDs and BM25 scores
     */
    default SearchResult fullTextSearch(
            String fieldName, String queryText, int topK, FullTextParams params)
            throws IOException {
        return fullTextSearch(fieldName, queryText, topK);
    }

    /**
     * Structured full-text search: runs a {@link FullTextQuerySpec} tree (Match / Phrase / Bool /
     * Boost) against the index. The default implementation only supports a top-level {@link
     * FullTextQuerySpec.Match} (delegating to {@link #fullTextSearch(String, String, int,
     * FullTextParams)}); implementations that can build composite Lucene queries override this.
     *
     * @param spec the query tree
     * @param topK number of results to return
     * @return search results with IDs and BM25 scores
     */
    default SearchResult fullTextSearch(FullTextQuerySpec spec, int topK) throws IOException {
        if (spec instanceof FullTextQuerySpec.Match) {
            FullTextQuerySpec.Match match = (FullTextQuerySpec.Match) spec;
            return fullTextSearch(match.field(), match.text(), topK, match.params());
        }
        throw new UnsupportedOperationException(
                "This ESIndexSearcher does not support structured full-text query "
                        + spec.getClass().getSimpleName());
    }

    /**
     * Scalar filter — returns matching row IDs.
     *
     * @param fieldName the scalar field to filter on
     * @param predicate the filter predicate (eq, range, in, etc.)
     * @return array of matching row IDs
     * @deprecated Use {@link #filter(String, IndexFilter)} instead
     */
    @Deprecated
    long[] scalarFilter(String fieldName, ScalarPredicate predicate) throws IOException;

    /**
     * Unified filter — routes to the correct Lucene query based on filter type. Supports scalar
     * (numeric point / keyword term), text (prefix/wildcard/regexp), and geo (distance / bounding
     * box) queries.
     *
     * @param fieldName the field to filter on
     * @param filter the filter predicate (scalar, text, or geo)
     * @return array of matching row IDs
     */
    long[] filter(String fieldName, IndexFilter filter) throws IOException;

    /**
     * Returns every logical row ID stored in this index. This is used by integrations as a
     * conservative result when a particular shard cannot evaluate a predicate: unioning all rows
     * from that shard avoids false negatives while the engine applies the residual predicate.
     */
    default long[] allRowIds() throws IOException {
        throw new UnsupportedOperationException("This ESIndexSearcher cannot enumerate row IDs");
    }
}
