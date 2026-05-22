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

import org.apache.paimon.data.BinaryArray;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.PositionOutputStream;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.globalindex.GlobalIndexIOMeta;
import org.apache.paimon.globalindex.GlobalIndexMultiColumnWriter;
import org.apache.paimon.globalindex.GlobalIndexReader;
import org.apache.paimon.globalindex.GlobalIndexWriter;
import org.apache.paimon.globalindex.GlobalIndexer;
import org.apache.paimon.globalindex.GlobalIndexerFactory;
import org.apache.paimon.globalindex.ResultEntry;
import org.apache.paimon.globalindex.ScoredGlobalIndexResult;
import org.apache.paimon.globalindex.io.GlobalIndexFileReader;
import org.apache.paimon.globalindex.io.GlobalIndexFileWriter;
import org.apache.paimon.options.Options;
import org.apache.paimon.predicate.FullTextSearch;
import org.apache.paimon.predicate.VectorSearch;
import org.apache.paimon.types.ArrayType;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.FloatType;
import org.apache.paimon.types.VarCharType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Test for Lucene multi-column index (text + vector in one index). */
public class LuceneMultiColumnIndexTest {

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
    public void testMultiColumnTextAndVectorIndex() throws IOException {
        DataField textField = new DataField(0, "title", new VarCharType(Integer.MAX_VALUE));
        DataField vecField = new DataField(1, "embedding", new ArrayType(new FloatType()));
        List<DataField> fields = Arrays.asList(textField, vecField);

        Options options = new Options();
        options.setInteger(LuceneIndexOptions.KEY_DIMENSION, 2);

        GlobalIndexerFactory factory = new LuceneGlobalIndexerFactory();
        GlobalIndexer indexer = factory.create(fields, options);

        GlobalIndexFileWriter fileWriter = createFileWriter(indexPath);
        GlobalIndexWriter writer = indexer.createWriter(fileWriter);
        assertThat(writer).isInstanceOf(GlobalIndexMultiColumnWriter.class);

        GlobalIndexMultiColumnWriter multiWriter = (GlobalIndexMultiColumnWriter) writer;

        String[] titles =
                new String[] {
                    "Apache Paimon lake storage",
                    "Lucene text search engine",
                    "Vector similarity search",
                    "Paimon global index system",
                    "Flink streaming with Paimon"
                };
        float[][] embeddings =
                new float[][] {
                    new float[] {1.0f, 0.0f},
                    new float[] {0.95f, 0.1f},
                    new float[] {0.1f, 0.95f},
                    new float[] {0.98f, 0.05f},
                    new float[] {0.0f, 1.0f}
                };

        for (int i = 0; i < titles.length; i++) {
            InternalRow row = createRow(titles[i], embeddings[i]);
            multiWriter.write(row);
        }

        List<ResultEntry> results = writer.finish();
        assertThat(results).hasSize(1);

        List<GlobalIndexIOMeta> metas = toIOMetas(results, indexPath);
        GlobalIndexFileReader fileReader = createFileReader(indexPath);

        try (GlobalIndexReader reader = indexer.createReader(fileReader, metas)) {
            FullTextSearch textSearch = new FullTextSearch("Paimon", 10, "title");
            ScoredGlobalIndexResult textResult = reader.visitFullTextSearch(textSearch).get();
            assertThat(textResult.results().getLongCardinality()).isGreaterThanOrEqualTo(2);
            assertThat(textResult.results().contains(0L)).isTrue();
            assertThat(textResult.results().contains(3L)).isTrue();

            VectorSearch vecSearch = new VectorSearch(embeddings[0], 3, "embedding");
            ScoredGlobalIndexResult vecResult = reader.visitVectorSearch(vecSearch).get();
            assertThat(vecResult.results().getLongCardinality()).isEqualTo(3);
            assertThat(vecResult.results().contains(0L)).isTrue();
            float score = vecResult.scoreGetter().score(0L);
            assertThat(score).isGreaterThan(0f);
        }
    }

    @Test
    public void testMultiColumnViaFactoryCreate() throws IOException {
        DataField textField = new DataField(0, "desc", new VarCharType(Integer.MAX_VALUE));
        DataField vecField = new DataField(1, "vec", new ArrayType(new FloatType()));
        List<DataField> fields = Arrays.asList(textField, vecField);

        Options options = new Options();
        options.setInteger(LuceneIndexOptions.KEY_DIMENSION, 2);

        GlobalIndexer indexer = GlobalIndexer.create("lucene", fields, options);

        GlobalIndexFileWriter fileWriter = createFileWriter(indexPath);
        GlobalIndexWriter writer = indexer.createWriter(fileWriter);

        GlobalIndexMultiColumnWriter multiWriter = (GlobalIndexMultiColumnWriter) writer;
        multiWriter.write(createRow("hello world", new float[] {1.0f, 0.0f}));
        multiWriter.write(createRow("hello paimon", new float[] {0.9f, 0.1f}));
        multiWriter.write(createRow("goodbye world", new float[] {0.0f, 1.0f}));

        List<ResultEntry> results = writer.finish();
        List<GlobalIndexIOMeta> metas = toIOMetas(results, indexPath);
        GlobalIndexFileReader fileReader = createFileReader(indexPath);

        try (GlobalIndexReader reader = indexer.createReader(fileReader, metas)) {
            FullTextSearch search = new FullTextSearch("hello", 10, "desc");
            ScoredGlobalIndexResult result = reader.visitFullTextSearch(search).get();
            assertThat(result.results().getLongCardinality()).isEqualTo(2);
            assertThat(result.results().contains(0L)).isTrue();
            assertThat(result.results().contains(1L)).isTrue();

            VectorSearch vecSearch = new VectorSearch(new float[] {1.0f, 0.0f}, 2, "vec");
            ScoredGlobalIndexResult vecResult = reader.visitVectorSearch(vecSearch).get();
            assertThat(vecResult.results().getLongCardinality()).isEqualTo(2);
            assertThat(vecResult.results().contains(0L)).isTrue();
        }
    }

    private InternalRow createRow(String text, float[] vector) {
        GenericRow row = new GenericRow(2);
        row.setField(0, BinaryString.fromString(text));
        row.setField(1, BinaryArray.fromPrimitiveArray(vector));
        return row;
    }
}
