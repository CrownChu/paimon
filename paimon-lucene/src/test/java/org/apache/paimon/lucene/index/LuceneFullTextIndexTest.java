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

import org.apache.paimon.data.BinaryString;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.PositionOutputStream;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.globalindex.GlobalIndexIOMeta;
import org.apache.paimon.globalindex.GlobalIndexReader;
import org.apache.paimon.globalindex.GlobalIndexSingletonWriter;
import org.apache.paimon.globalindex.GlobalIndexWriter;
import org.apache.paimon.globalindex.ResultEntry;
import org.apache.paimon.globalindex.ScoredGlobalIndexResult;
import org.apache.paimon.globalindex.io.GlobalIndexFileReader;
import org.apache.paimon.globalindex.io.GlobalIndexFileWriter;
import org.apache.paimon.options.Options;
import org.apache.paimon.predicate.FullTextSearch;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.VarCharType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Test for Lucene full-text index (single column). */
public class LuceneFullTextIndexTest {

    @TempDir java.nio.file.Path tempDir;

    private FileIO fileIO;
    private Path indexPath;

    @BeforeEach
    public void setup() {
        fileIO = new LocalFileIO();
        indexPath = new Path(tempDir.toString());
    }

    @AfterEach
    public void cleanup() throws IOException {
        if (fileIO != null) {
            fileIO.delete(indexPath, true);
        }
    }

    private GlobalIndexFileWriter createFileWriter(Path path) {
        return new GlobalIndexFileWriter() {
            @Override
            public String newFileName(String prefix) {
                return prefix + "-" + UUID.randomUUID();
            }

            @Override
            public PositionOutputStream newOutputStream(String fileName) throws IOException {
                return fileIO.newOutputStream(new Path(path, fileName), false);
            }
        };
    }

    private GlobalIndexFileReader createFileReader(Path path) {
        return meta -> fileIO.newInputStream(new Path(path, meta.filePath()));
    }

    private List<GlobalIndexIOMeta> toIOMetas(List<ResultEntry> results, Path path)
            throws IOException {
        assertThat(results).hasSize(1);
        ResultEntry result = results.get(0);
        Path filePath = new Path(path, result.fileName());
        return Collections.singletonList(
                new GlobalIndexIOMeta(filePath, fileIO.getFileSize(filePath), result.meta()));
    }

    @Test
    public void testFullTextSearchEndToEnd() throws IOException {
        DataField textField = new DataField(0, "content", new VarCharType(Integer.MAX_VALUE));
        Options options = new Options();
        LuceneGlobalIndexer indexer =
                (LuceneGlobalIndexer) new LuceneGlobalIndexerFactory().create(textField, options);

        GlobalIndexFileWriter fileWriter = createFileWriter(indexPath);
        GlobalIndexWriter writer = indexer.createWriter(fileWriter);

        String[] texts =
                new String[] {
                    "Apache Paimon is a lake storage format",
                    "Lucene provides full text search capabilities",
                    "Vector search enables similarity queries",
                    "Paimon supports global index for efficient queries",
                    "Apache Flink integrates well with Paimon"
                };

        GlobalIndexSingletonWriter singleWriter = (GlobalIndexSingletonWriter) writer;
        for (String text : texts) {
            singleWriter.write(BinaryString.fromString(text));
        }

        List<ResultEntry> results = writer.finish();
        assertThat(results).hasSize(1);

        List<GlobalIndexIOMeta> metas = toIOMetas(results, indexPath);
        GlobalIndexFileReader fileReader = createFileReader(indexPath);

        try (GlobalIndexReader reader = indexer.createReader(fileReader, metas)) {
            FullTextSearch search = new FullTextSearch("Paimon", 10, "content");
            ScoredGlobalIndexResult result = reader.visitFullTextSearch(search).get();
            assertThat(result.results().getLongCardinality()).isGreaterThanOrEqualTo(2);
            assertThat(result.results().contains(0L)).isTrue();
            assertThat(result.results().contains(3L)).isTrue();
        }
    }

    @Test
    public void testFullTextSearchWithScore() throws IOException {
        DataField textField = new DataField(0, "content", new VarCharType(Integer.MAX_VALUE));
        Options options = new Options();
        LuceneGlobalIndexer indexer =
                (LuceneGlobalIndexer) new LuceneGlobalIndexerFactory().create(textField, options);

        GlobalIndexFileWriter fileWriter = createFileWriter(indexPath);
        GlobalIndexWriter writer = indexer.createWriter(fileWriter);

        GlobalIndexSingletonWriter singleWriter = (GlobalIndexSingletonWriter) writer;
        singleWriter.write(BinaryString.fromString("hello world"));
        singleWriter.write(BinaryString.fromString("goodbye world"));
        singleWriter.write(BinaryString.fromString("hello hello hello"));

        List<ResultEntry> results = writer.finish();
        List<GlobalIndexIOMeta> metas = toIOMetas(results, indexPath);
        GlobalIndexFileReader fileReader = createFileReader(indexPath);

        try (GlobalIndexReader reader = indexer.createReader(fileReader, metas)) {
            FullTextSearch search = new FullTextSearch("hello", 10, "content");
            ScoredGlobalIndexResult result = reader.visitFullTextSearch(search).get();
            assertThat(result.results().getLongCardinality()).isGreaterThanOrEqualTo(1);
            assertThat(result.results().contains(0L)).isTrue();
            assertThat(result.results().contains(2L)).isTrue();

            float scoreRow0 = result.scoreGetter().score(0L);
            float scoreRow2 = result.scoreGetter().score(2L);
            assertThat(scoreRow0).isGreaterThan(0f);
            assertThat(scoreRow2).isGreaterThan(0f);
        }
    }

    @Test
    public void testFullTextSearchNoResults() throws IOException {
        DataField textField = new DataField(0, "content", new VarCharType(Integer.MAX_VALUE));
        Options options = new Options();
        LuceneGlobalIndexer indexer =
                (LuceneGlobalIndexer) new LuceneGlobalIndexerFactory().create(textField, options);

        GlobalIndexFileWriter fileWriter = createFileWriter(indexPath);
        GlobalIndexWriter writer = indexer.createWriter(fileWriter);

        GlobalIndexSingletonWriter singleWriter = (GlobalIndexSingletonWriter) writer;
        singleWriter.write(BinaryString.fromString("hello world"));
        singleWriter.write(BinaryString.fromString("goodbye world"));

        List<ResultEntry> results = writer.finish();
        List<GlobalIndexIOMeta> metas = toIOMetas(results, indexPath);
        GlobalIndexFileReader fileReader = createFileReader(indexPath);

        try (GlobalIndexReader reader = indexer.createReader(fileReader, metas)) {
            FullTextSearch search = new FullTextSearch("nonexistent", 10, "content");
            ScoredGlobalIndexResult result = reader.visitFullTextSearch(search).get();
            assertThat(result.results().getLongCardinality()).isEqualTo(0);
        }
    }
}
