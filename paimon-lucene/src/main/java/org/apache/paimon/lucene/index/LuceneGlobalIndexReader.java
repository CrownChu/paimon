/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.lucene.index;

import org.apache.paimon.fs.SeekableInputStream;
import org.apache.paimon.globalindex.GlobalIndexIOMeta;
import org.apache.paimon.globalindex.GlobalIndexReader;
import org.apache.paimon.globalindex.GlobalIndexResult;
import org.apache.paimon.globalindex.ScoredGlobalIndexResult;
import org.apache.paimon.globalindex.io.GlobalIndexFileReader;
import org.apache.paimon.predicate.FieldRef;
import org.apache.paimon.predicate.FullTextSearch;
import org.apache.paimon.predicate.VectorSearch;
import org.apache.paimon.types.DataField;
import org.apache.paimon.utils.RoaringNavigableMap64;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lucene global index reader supporting vector search and full-text search.
 *
 * <p>Reads a single archive file, unpacks it to a temp directory, and opens a Lucene {@link
 * DirectoryReader} for searching.
 */
public class LuceneGlobalIndexReader implements GlobalIndexReader {

    private static final String ROW_ID_FIELD = "_row_id";

    private final GlobalIndexFileReader fileReader;
    private final List<GlobalIndexIOMeta> ioMetas;
    private final List<DataField> fields;
    private final LuceneIndexOptions options;

    private java.nio.file.Path tempDir;
    private FSDirectory directory;
    private DirectoryReader directoryReader;
    private IndexSearcher searcher;

    public LuceneGlobalIndexReader(
            GlobalIndexFileReader fileReader,
            List<GlobalIndexIOMeta> ioMetas,
            List<DataField> fields,
            LuceneIndexOptions options)
            throws IOException {
        this.fileReader = fileReader;
        this.ioMetas = ioMetas;
        this.fields = fields;
        this.options = options;
    }

    @Override
    public Optional<ScoredGlobalIndexResult> visitVectorSearch(VectorSearch vectorSearch) {
        try {
            ensureLoaded();
            KnnFloatVectorQuery query =
                    new KnnFloatVectorQuery(
                            vectorSearch.fieldName(), vectorSearch.vector(), vectorSearch.limit());
            TopDocs topDocs = searcher.search(query, vectorSearch.limit());
            return Optional.of(toScoredResult(topDocs));
        } catch (IOException e) {
            throw new RuntimeException("Failed to execute vector search", e);
        }
    }

    @Override
    public Optional<ScoredGlobalIndexResult> visitFullTextSearch(FullTextSearch fullTextSearch) {
        try {
            ensureLoaded();
            QueryParser parser =
                    new QueryParser(fullTextSearch.fieldName(), new StandardAnalyzer());
            Query query = parser.parse(fullTextSearch.queryText());
            TopDocs topDocs = searcher.search(query, fullTextSearch.limit());
            return Optional.of(toScoredResult(topDocs));
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute full-text search", e);
        }
    }

    private ScoredGlobalIndexResult toScoredResult(TopDocs topDocs) throws IOException {
        RoaringNavigableMap64 rowIds = new RoaringNavigableMap64();
        Map<Long, Float> scoreMap = new HashMap<>();

        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            Document doc = searcher.storedFields().document(scoreDoc.doc);
            long rowId = doc.getField(ROW_ID_FIELD).numericValue().longValue();
            rowIds.add(rowId);
            scoreMap.put(rowId, scoreDoc.score);
        }

        return ScoredGlobalIndexResult.create(
                () -> rowIds, rowId -> scoreMap.getOrDefault(rowId, 0f));
    }

    private void ensureLoaded() throws IOException {
        if (directoryReader == null) {
            synchronized (this) {
                if (directoryReader == null) {
                    tempDir = Files.createTempDirectory("lucene-reader-");
                    for (GlobalIndexIOMeta meta : ioMetas) {
                        try (SeekableInputStream in = fileReader.getInputStream(meta)) {
                            readArchive(in);
                        }
                    }
                    directory = FSDirectory.open(tempDir);
                    directoryReader = DirectoryReader.open(directory);
                    searcher = new IndexSearcher(directoryReader);
                }
            }
        }
    }

    private void readArchive(SeekableInputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int fileCount = dis.readInt();
        for (int i = 0; i < fileCount; i++) {
            int nameLen = dis.readInt();
            byte[] nameBytes = new byte[nameLen];
            dis.readFully(nameBytes);
            String name = new String(nameBytes, "UTF-8");

            long fileLength = dis.readLong();

            java.nio.file.Path filePath = tempDir.resolve(name);
            try (java.io.OutputStream fos = Files.newOutputStream(filePath)) {
                byte[] buffer = new byte[8192];
                long remaining = fileLength;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    dis.readFully(buffer, 0, toRead);
                    fos.write(buffer, 0, toRead);
                    remaining -= toRead;
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        Throwable firstException = null;

        if (directoryReader != null) {
            try {
                directoryReader.close();
            } catch (Throwable t) {
                firstException = t;
            }
            directoryReader = null;
        }

        if (directory != null) {
            try {
                directory.close();
            } catch (Throwable t) {
                if (firstException == null) {
                    firstException = t;
                }
            }
            directory = null;
        }

        cleanupTempDir();

        if (firstException != null) {
            if (firstException instanceof IOException) {
                throw (IOException) firstException;
            }
            throw new RuntimeException("Failed to close Lucene reader", firstException);
        }
    }

    private void cleanupTempDir() {
        if (tempDir != null) {
            try {
                java.io.File[] files = tempDir.toFile().listFiles();
                if (files != null) {
                    for (java.io.File f : files) {
                        f.delete();
                    }
                }
                tempDir.toFile().delete();
            } catch (Exception ignored) {
            }
            tempDir = null;
        }
    }

    // =================== unsupported visitor methods =====================

    @Override
    public Optional<GlobalIndexResult> visitIsNotNull(FieldRef fieldRef) {
        return Optional.empty();
    }

    @Override
    public Optional<GlobalIndexResult> visitIsNull(FieldRef fieldRef) {
        return Optional.empty();
    }

    @Override
    public Optional<GlobalIndexResult> visitStartsWith(FieldRef fieldRef, Object literal) {
        return Optional.empty();
    }

    @Override
    public Optional<GlobalIndexResult> visitEndsWith(FieldRef fieldRef, Object literal) {
        return Optional.empty();
    }

    @Override
    public Optional<GlobalIndexResult> visitContains(FieldRef fieldRef, Object literal) {
        return Optional.empty();
    }

    @Override
    public Optional<GlobalIndexResult> visitLike(FieldRef fieldRef, Object literal) {
        return Optional.empty();
    }

    @Override
    public Optional<GlobalIndexResult> visitLessThan(FieldRef fieldRef, Object literal) {
        return Optional.empty();
    }

    @Override
    public Optional<GlobalIndexResult> visitGreaterOrEqual(FieldRef fieldRef, Object literal) {
        return Optional.empty();
    }

    @Override
    public Optional<GlobalIndexResult> visitNotEqual(FieldRef fieldRef, Object literal) {
        return Optional.empty();
    }

    @Override
    public Optional<GlobalIndexResult> visitLessOrEqual(FieldRef fieldRef, Object literal) {
        return Optional.empty();
    }

    @Override
    public Optional<GlobalIndexResult> visitEqual(FieldRef fieldRef, Object literal) {
        return Optional.empty();
    }

    @Override
    public Optional<GlobalIndexResult> visitGreaterThan(FieldRef fieldRef, Object literal) {
        return Optional.empty();
    }

    @Override
    public Optional<GlobalIndexResult> visitIn(FieldRef fieldRef, List<Object> literals) {
        return Optional.empty();
    }

    @Override
    public Optional<GlobalIndexResult> visitNotIn(FieldRef fieldRef, List<Object> literals) {
        return Optional.empty();
    }
}
