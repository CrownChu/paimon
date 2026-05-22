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

import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.PositionOutputStream;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.globalindex.GlobalIndexIOMeta;
import org.apache.paimon.globalindex.GlobalIndexReader;
import org.apache.paimon.globalindex.GlobalIndexWriter;
import org.apache.paimon.globalindex.ResultEntry;
import org.apache.paimon.globalindex.ScoredGlobalIndexResult;
import org.apache.paimon.globalindex.io.GlobalIndexFileReader;
import org.apache.paimon.globalindex.io.GlobalIndexFileWriter;
import org.apache.paimon.options.Options;
import org.apache.paimon.predicate.VectorSearch;
import org.apache.paimon.types.ArrayType;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.FloatType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Test for Lucene vector index (single column). */
public class LuceneGlobalIndexTest {

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
    public void testVectorSearchEndToEnd() throws IOException {
        int dimension = 2;
        Options options = new Options();
        options.setInteger(LuceneIndexOptions.KEY_DIMENSION, dimension);

        DataField vecField = new DataField(0, "vec", new ArrayType(new FloatType()));
        LuceneGlobalIndexerFactory factory = new LuceneGlobalIndexerFactory();
        LuceneGlobalIndexer indexer = (LuceneGlobalIndexer) factory.create(vecField, options);

        GlobalIndexFileWriter fileWriter = createFileWriter(indexPath);
        GlobalIndexWriter writer = indexer.createWriter(fileWriter);

        float[][] vectors =
                new float[][] {
                    new float[] {1.0f, 0.0f},
                    new float[] {0.95f, 0.1f},
                    new float[] {0.1f, 0.95f},
                    new float[] {0.98f, 0.05f},
                    new float[] {0.0f, 1.0f},
                    new float[] {0.05f, 0.98f}
                };

        for (float[] vec : vectors) {
            ((org.apache.paimon.globalindex.GlobalIndexSingletonWriter) writer).write(vec);
        }

        List<ResultEntry> results = writer.finish();
        assertThat(results).hasSize(1);

        List<GlobalIndexIOMeta> metas = toIOMetas(results, indexPath);
        GlobalIndexFileReader fileReader = createFileReader(indexPath);

        try (GlobalIndexReader reader = indexer.createReader(fileReader, metas)) {
            VectorSearch search = new VectorSearch(vectors[0], 3, "vec");
            ScoredGlobalIndexResult result = reader.visitVectorSearch(search).get();
            assertThat(result.results().getLongCardinality()).isEqualTo(3);
            assertThat(result.results().contains(0L)).isTrue();
            float score = result.scoreGetter().score(0L);
            assertThat(score).isNotNaN();
            assertThat(score).isGreaterThan(0f);
        }
    }

    @Test
    public void testVectorSearchTopKScoreOrder() throws IOException {
        int dimension = 2;
        Options options = new Options();
        options.setInteger(LuceneIndexOptions.KEY_DIMENSION, dimension);

        DataField vecField = new DataField(0, "vec", new ArrayType(new FloatType()));
        LuceneGlobalIndexer indexer =
                (LuceneGlobalIndexer) new LuceneGlobalIndexerFactory().create(vecField, options);

        GlobalIndexFileWriter fileWriter = createFileWriter(indexPath);
        GlobalIndexWriter writer = indexer.createWriter(fileWriter);

        float[][] vectors =
                new float[][] {
                    new float[] {1.0f, 0.0f},
                    new float[] {0.95f, 0.1f},
                    new float[] {0.1f, 0.95f},
                    new float[] {0.98f, 0.05f},
                    new float[] {0.0f, 1.0f},
                    new float[] {0.05f, 0.98f}
                };

        for (float[] vec : vectors) {
            ((org.apache.paimon.globalindex.GlobalIndexSingletonWriter) writer).write(vec);
        }

        List<ResultEntry> results = writer.finish();
        List<GlobalIndexIOMeta> metas = toIOMetas(results, indexPath);
        GlobalIndexFileReader fileReader = createFileReader(indexPath);

        try (GlobalIndexReader reader = indexer.createReader(fileReader, metas)) {
            VectorSearch search = new VectorSearch(vectors[0], 3, "vec");
            ScoredGlobalIndexResult result = reader.visitVectorSearch(search).get();
            assertThat(result.results().contains(0L)).isTrue();
            float scoreRow0 = result.scoreGetter().score(0L);
            float scoreRow3 = result.scoreGetter().score(3L);
            assertThat(scoreRow0).isGreaterThanOrEqualTo(scoreRow3);
        }
    }
}
