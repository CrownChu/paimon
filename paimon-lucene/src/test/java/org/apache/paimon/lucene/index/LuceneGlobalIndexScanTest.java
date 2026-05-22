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

import org.apache.paimon.CoreOptions;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericArray;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.PositionOutputStream;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.globalindex.GlobalIndexResult;
import org.apache.paimon.globalindex.ResultEntry;
import org.apache.paimon.globalindex.io.GlobalIndexFileWriter;
import org.apache.paimon.index.GlobalIndexMeta;
import org.apache.paimon.index.IndexFileMeta;
import org.apache.paimon.io.CompactIncrement;
import org.apache.paimon.io.DataIncrement;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.FileStoreTableFactory;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.table.sink.CommitMessageImpl;
import org.apache.paimon.table.sink.StreamTableWrite;
import org.apache.paimon.table.source.FullTextSearchBuilder;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.TableScan;
import org.apache.paimon.table.source.VectorSearchBuilder;
import org.apache.paimon.types.ArrayType;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test for Lucene global index with a real Paimon table. Creates a FileStoreTable,
 * writes data, builds Lucene index, commits it, then queries via VectorSearchBuilder and
 * FullTextSearchBuilder.
 */
public class LuceneGlobalIndexScanTest {

    @TempDir java.nio.file.Path tempDir;

    private FileIO fileIO;
    private String commitUser;

    @BeforeEach
    public void setup() {
        fileIO = new LocalFileIO();
        commitUser = UUID.randomUUID().toString();
    }

    @Test
    public void testVectorSearchScanEndToEnd() throws Exception {
        Path tablePath = new Path(tempDir.toString(), "vec_table");
        SchemaManager schemaManager = new SchemaManager(fileIO, tablePath);

        Schema schema =
                Schema.newBuilder()
                        .column("id", DataTypes.INT())
                        .column("vec", new ArrayType(DataTypes.FLOAT()))
                        .option(CoreOptions.BUCKET.key(), "-1")
                        .option("data-evolution.enabled", "true")
                        .option("row-tracking.enabled", "true")
                        .option("file.format", "avro")
                        .build();

        TableSchema tableSchema = schemaManager.createTable(schema);
        FileStoreTable table = FileStoreTableFactory.create(fileIO, tablePath, tableSchema);
        RowType rowType = table.rowType();

        float[][] vectors =
                new float[][] {
                    new float[] {1.0f, 0.0f},
                    new float[] {0.95f, 0.1f},
                    new float[] {0.1f, 0.95f},
                    new float[] {0.98f, 0.05f},
                    new float[] {0.0f, 1.0f},
                    new float[] {0.05f, 0.98f}
                };

        StreamTableWrite write = table.newWrite(commitUser);
        for (int i = 0; i < vectors.length; i++) {
            write.write(GenericRow.of(i, new GenericArray(vectors[i])));
        }
        List<CommitMessage> messages = write.prepareCommit(false, 0);
        table.newCommit(commitUser).commit(0, messages);
        write.close();

        List<IndexFileMeta> indexFiles = buildVectorIndex(table, rowType, vectors, "vec");
        commitIndex(table, indexFiles);

        VectorSearchBuilder vectorBuilder =
                table.newVectorSearchBuilder()
                        .withVector(new float[] {0.85f, 0.15f})
                        .withLimit(3)
                        .withVectorColumn("vec");
        GlobalIndexResult indexResult =
                vectorBuilder.newVectorRead().read(vectorBuilder.newVectorScan().scan());

        ReadBuilder readBuilder = table.newReadBuilder();
        TableScan scan = readBuilder.newScan().withGlobalIndexResult(indexResult);
        List<Integer> ids = new ArrayList<>();
        readBuilder
                .newRead()
                .createReader(scan.plan())
                .forEachRemaining(row -> ids.add(row.getInt(0)));

        assertThat(ids).isNotEmpty();
        assertThat(ids.size()).isLessThanOrEqualTo(3);
    }

    @Test
    public void testFullTextSearchScanEndToEnd() throws Exception {
        Path tablePath = new Path(tempDir.toString(), "text_table");
        SchemaManager schemaManager = new SchemaManager(fileIO, tablePath);

        Schema schema =
                Schema.newBuilder()
                        .column("id", DataTypes.INT())
                        .column("content", DataTypes.STRING())
                        .option(CoreOptions.BUCKET.key(), "-1")
                        .option("data-evolution.enabled", "true")
                        .option("row-tracking.enabled", "true")
                        .option("file.format", "avro")
                        .build();

        TableSchema tableSchema = schemaManager.createTable(schema);
        FileStoreTable table = FileStoreTableFactory.create(fileIO, tablePath, tableSchema);
        RowType rowType = table.rowType();

        String[] texts =
                new String[] {
                    "Apache Paimon is a lake storage format",
                    "Lucene provides full text search capabilities",
                    "Vector search enables similarity queries",
                    "Paimon supports global index for efficient queries",
                    "Apache Flink integrates well with Paimon"
                };

        StreamTableWrite write = table.newWrite(commitUser);
        for (int i = 0; i < texts.length; i++) {
            write.write(GenericRow.of(i, BinaryString.fromString(texts[i])));
        }
        List<CommitMessage> messages = write.prepareCommit(false, 0);
        table.newCommit(commitUser).commit(0, messages);
        write.close();

        List<IndexFileMeta> indexFiles = buildFullTextIndex(table, rowType, texts, "content");
        commitIndex(table, indexFiles);

        FullTextSearchBuilder ftBuilder =
                table.newFullTextSearchBuilder()
                        .withQueryText("Paimon")
                        .withLimit(10)
                        .withTextColumn("content");
        GlobalIndexResult indexResult = ftBuilder.executeLocal();

        ReadBuilder readBuilder = table.newReadBuilder();
        TableScan scan = readBuilder.newScan().withGlobalIndexResult(indexResult);
        List<Integer> ids = new ArrayList<>();
        readBuilder
                .newRead()
                .createReader(scan.plan())
                .forEachRemaining(row -> ids.add(row.getInt(0)));

        assertThat(ids).isNotEmpty();
        assertThat(ids).contains(0);
        assertThat(ids).contains(3);
    }

    @Test
    public void testMultiColumnScanEndToEnd() throws Exception {
        Path tablePath = new Path(tempDir.toString(), "multi_table");
        SchemaManager schemaManager = new SchemaManager(fileIO, tablePath);

        Schema schema =
                Schema.newBuilder()
                        .column("id", DataTypes.INT())
                        .column("title", DataTypes.STRING())
                        .column("embedding", new ArrayType(DataTypes.FLOAT()))
                        .option(CoreOptions.BUCKET.key(), "-1")
                        .option("data-evolution.enabled", "true")
                        .option("row-tracking.enabled", "true")
                        .option("file.format", "avro")
                        .build();

        TableSchema tableSchema = schemaManager.createTable(schema);
        FileStoreTable table = FileStoreTableFactory.create(fileIO, tablePath, tableSchema);
        RowType rowType = table.rowType();

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

        StreamTableWrite write = table.newWrite(commitUser);
        for (int i = 0; i < titles.length; i++) {
            write.write(
                    GenericRow.of(
                            i,
                            BinaryString.fromString(titles[i]),
                            new GenericArray(embeddings[i])));
        }
        List<CommitMessage> messages = write.prepareCommit(false, 0);
        table.newCommit(commitUser).commit(0, messages);
        write.close();

        List<IndexFileMeta> indexFiles =
                buildMultiColumnIndex(table, rowType, titles, embeddings, "title", "embedding");
        commitIndex(table, indexFiles);

        VectorSearchBuilder vecBuilder =
                table.newVectorSearchBuilder()
                        .withVector(new float[] {1.0f, 0.0f})
                        .withLimit(3)
                        .withVectorColumn("embedding");
        GlobalIndexResult vecResult = vecBuilder.executeLocal();

        ReadBuilder readBuilder = table.newReadBuilder();
        TableScan scan = readBuilder.newScan().withGlobalIndexResult(vecResult);
        List<Integer> vecIds = new ArrayList<>();
        readBuilder
                .newRead()
                .createReader(scan.plan())
                .forEachRemaining(row -> vecIds.add(row.getInt(0)));

        assertThat(vecIds).isNotEmpty();
        assertThat(vecIds.size()).isLessThanOrEqualTo(3);

        FullTextSearchBuilder ftBuilder =
                table.newFullTextSearchBuilder()
                        .withQueryText("Paimon")
                        .withLimit(10)
                        .withTextColumn("title");
        GlobalIndexResult ftResult = ftBuilder.executeLocal();

        ReadBuilder readBuilder2 = table.newReadBuilder();
        TableScan scan2 = readBuilder2.newScan().withGlobalIndexResult(ftResult);
        List<Integer> ftIds = new ArrayList<>();
        readBuilder2
                .newRead()
                .createReader(scan2.plan())
                .forEachRemaining(row -> ftIds.add(row.getInt(0)));

        assertThat(ftIds).isNotEmpty();
        assertThat(ftIds).contains(0);
        assertThat(ftIds).contains(3);
    }

    private List<IndexFileMeta> buildVectorIndex(
            FileStoreTable table, RowType rowType, float[][] vectors, String fieldName)
            throws Exception {
        Path indexDir = table.store().pathFactory().indexPath();
        if (!fileIO.exists(indexDir)) {
            fileIO.mkdirs(indexDir);
        }

        Options options = new Options(table.options());
        options.setInteger(LuceneIndexOptions.KEY_DIMENSION, vectors[0].length);
        LuceneIndexOptions indexOptions = new LuceneIndexOptions(options);

        GlobalIndexFileWriter fileWriter = createIndexFileWriter(indexDir);
        LuceneGlobalIndexWriter writer =
                new LuceneGlobalIndexWriter(
                        fileWriter,
                        Collections.singletonList(
                                rowType.getFields().get(rowType.getFieldIndex(fieldName))),
                        indexOptions);

        for (float[] vec : vectors) {
            writer.write(vec);
        }

        List<ResultEntry> entries = writer.finish();
        int fieldId = rowType.getFieldIndex(fieldName);

        List<IndexFileMeta> metas = new ArrayList<>();
        for (ResultEntry entry : entries) {
            long fileSize = fileIO.getFileSize(new Path(indexDir, entry.fileName()));
            GlobalIndexMeta globalMeta =
                    new GlobalIndexMeta(0, vectors.length - 1, fieldId, null, entry.meta());
            metas.add(
                    new IndexFileMeta(
                            LuceneGlobalIndexerFactory.IDENTIFIER,
                            entry.fileName(),
                            fileSize,
                            entry.rowCount(),
                            globalMeta,
                            (String) null));
        }
        return metas;
    }

    private List<IndexFileMeta> buildFullTextIndex(
            FileStoreTable table, RowType rowType, String[] texts, String fieldName)
            throws Exception {
        Path indexDir = table.store().pathFactory().indexPath();
        if (!fileIO.exists(indexDir)) {
            fileIO.mkdirs(indexDir);
        }

        Options options = new Options(table.options());
        LuceneIndexOptions indexOptions = new LuceneIndexOptions(options);

        GlobalIndexFileWriter fileWriter = createIndexFileWriter(indexDir);
        LuceneGlobalIndexWriter writer =
                new LuceneGlobalIndexWriter(
                        fileWriter,
                        Collections.singletonList(
                                rowType.getFields().get(rowType.getFieldIndex(fieldName))),
                        indexOptions);

        for (String text : texts) {
            writer.write(BinaryString.fromString(text));
        }

        List<ResultEntry> entries = writer.finish();
        int fieldId = rowType.getFieldIndex(fieldName);

        List<IndexFileMeta> metas = new ArrayList<>();
        for (ResultEntry entry : entries) {
            long fileSize = fileIO.getFileSize(new Path(indexDir, entry.fileName()));
            GlobalIndexMeta globalMeta =
                    new GlobalIndexMeta(0, texts.length - 1, fieldId, null, entry.meta());
            metas.add(
                    new IndexFileMeta(
                            LuceneGlobalIndexerFactory.IDENTIFIER,
                            entry.fileName(),
                            fileSize,
                            entry.rowCount(),
                            globalMeta,
                            (String) null));
        }
        return metas;
    }

    private List<IndexFileMeta> buildMultiColumnIndex(
            FileStoreTable table,
            RowType rowType,
            String[] texts,
            float[][] vectors,
            String textFieldName,
            String vecFieldName)
            throws Exception {
        Path indexDir = table.store().pathFactory().indexPath();
        if (!fileIO.exists(indexDir)) {
            fileIO.mkdirs(indexDir);
        }

        Options options = new Options(table.options());
        options.setInteger(LuceneIndexOptions.KEY_DIMENSION, vectors[0].length);
        LuceneIndexOptions indexOptions = new LuceneIndexOptions(options);

        org.apache.paimon.types.DataField textField =
                rowType.getFields().get(rowType.getFieldIndex(textFieldName));
        org.apache.paimon.types.DataField vecField =
                rowType.getFields().get(rowType.getFieldIndex(vecFieldName));
        java.util.List<org.apache.paimon.types.DataField> fields =
                java.util.Arrays.asList(textField, vecField);

        GlobalIndexFileWriter fileWriter = createIndexFileWriter(indexDir);
        LuceneGlobalIndexWriter writer =
                new LuceneGlobalIndexWriter(fileWriter, fields, indexOptions);

        for (int i = 0; i < texts.length; i++) {
            GenericRow row = new GenericRow(2);
            row.setField(0, BinaryString.fromString(texts[i]));
            row.setField(1, new GenericArray(vectors[i]));
            writer.write(row);
        }

        List<ResultEntry> entries = writer.finish();
        int textFieldId = rowType.getFieldIndex(textFieldName);
        int vecFieldId = rowType.getFieldIndex(vecFieldName);

        List<IndexFileMeta> metas = new ArrayList<>();
        for (ResultEntry entry : entries) {
            long fileSize = fileIO.getFileSize(new Path(indexDir, entry.fileName()));
            GlobalIndexMeta globalMeta =
                    new GlobalIndexMeta(
                            0, texts.length - 1, textFieldId, new int[] {vecFieldId}, entry.meta());
            metas.add(
                    new IndexFileMeta(
                            LuceneGlobalIndexerFactory.IDENTIFIER,
                            entry.fileName(),
                            fileSize,
                            entry.rowCount(),
                            globalMeta,
                            (String) null));
        }
        return metas;
    }

    private GlobalIndexFileWriter createIndexFileWriter(Path indexDir) {
        return new GlobalIndexFileWriter() {
            @Override
            public String newFileName(String prefix) {
                return prefix + "-" + UUID.randomUUID();
            }

            @Override
            public PositionOutputStream newOutputStream(String fileName) throws IOException {
                return fileIO.newOutputStream(new Path(indexDir, fileName), false);
            }
        };
    }

    private void commitIndex(FileStoreTable table, List<IndexFileMeta> indexFiles) {
        DataIncrement dataIncrement = DataIncrement.indexIncrement(indexFiles);
        CommitMessage message =
                new CommitMessageImpl(
                        BinaryRow.EMPTY_ROW,
                        0,
                        1,
                        dataIncrement,
                        CompactIncrement.emptyIncrement());
        table.newCommit(commitUser).commit(1, Collections.singletonList(message));
    }
}
