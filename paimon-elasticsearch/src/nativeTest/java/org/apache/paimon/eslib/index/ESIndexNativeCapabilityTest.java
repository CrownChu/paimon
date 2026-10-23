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

package org.apache.paimon.eslib.index;

import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.fs.PositionOutputStream;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.globalindex.GlobalIndexIOMeta;
import org.apache.paimon.globalindex.GlobalIndexResult;
import org.apache.paimon.globalindex.ResultEntry;
import org.apache.paimon.globalindex.ScoredGlobalIndexResult;
import org.apache.paimon.globalindex.io.GlobalIndexFileWriter;
import org.apache.paimon.options.Options;
import org.apache.paimon.predicate.FieldRef;
import org.apache.paimon.predicate.FullTextSearch;
import org.apache.paimon.predicate.VectorSearch;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataTypes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real native capability acceptance through Paimon's writer, archive and reader. */
class ESIndexNativeCapabilityTest {

    private static ESIndexOptions nativeOptions(List<DataField> fields, Options options) {
        options.setString("global-index.es-index.backend", "elasticpp");
        return new ESIndexOptions(fields, options);
    }

    @TempDir Path directory;

    @Test
    void corruptSealedFileFailsBeforeNativeOpen() throws Exception {
        List<DataField> fields = List.of(new DataField(0, "id", DataTypes.INT()));
        ESIndexOptions options = nativeOptions(fields, new Options());
        ResultEntry entry;
        try (ESIndexGlobalIndexWriter writer = writer(fields, options)) {
            writer.write(7, 0);
            entry = writer.finish().get(0);
        }
        long[] range =
                ESIndexFileMeta.read(entry.meta()).fileOffsets().entrySet().stream()
                        .filter(file -> file.getKey().endsWith(".cfs"))
                        .findFirst()
                        .orElseThrow()
                        .getValue();
        try (java.io.RandomAccessFile archive =
                new java.io.RandomAccessFile(directory.resolve(entry.fileName()).toFile(), "rw")) {
            long position = range[0] + range[1] / 2;
            archive.seek(position);
            int value = archive.readUnsignedByte();
            archive.seek(position);
            archive.writeByte(value ^ 1);
        }
        try (ESIndexGlobalIndexReader reader = reader(entry, fields, options)) {
            assertThatThrownBy(
                            () ->
                                    reader.visitEqual(new FieldRef(0, "id", DataTypes.INT()), 7)
                                            .join())
                    .hasRootCauseMessage("Native archive file checksum differs");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"hnsw", "int8_hnsw", "diskbbq"})
    void nativeVectorAlgorithmsReturnIndependentL2GroundTruth(String algorithm) throws Exception {
        List<DataField> fields =
                List.of(new DataField(0, "embedding", DataTypes.VECTOR(33, DataTypes.FLOAT())));
        ESIndexOptions options =
                nativeOptions(
                        fields,
                        Options.fromMap(
                                Map.of(
                                        "global-index.es-index.fields.embedding.algorithm",
                                        algorithm,
                                        "global-index.es-index.fields.embedding.metric",
                                        "l2")));
        ResultEntry entry;
        try (ESIndexGlobalIndexWriter writer = writer(fields, options)) {
            // The first coordinate is the row id; exact squared L2 from the origin is row^2.
            for (int row = 0; row < 128; row++) {
                float[] vector = new float[33];
                vector[0] = row;
                writer.write(vector, row);
            }
            writer.write(null, 128);
            entry = writer.finish().get(0);
        }
        assertThat(ESIndexFileMeta.read(entry.meta()).backend()).isEqualTo("elasticpp");
        try (ESIndexGlobalIndexReader reader = reader(entry, fields, options)) {
            ScoredGlobalIndexResult result =
                    reader.visitVectorSearch(new VectorSearch(new float[33], 5, "embedding"))
                            .join()
                            .orElseThrow();
            assertThat(rows(result)).containsExactlyInAnyOrder(0L, 1L, 2L, 3L, 4L);
            for (long row : rows(result)) {
                assertThat(result.scoreGetter().score(row))
                        .isCloseTo(
                                1.0f / (1 + row * row),
                                org.assertj.core.data.Offset.offset(0.00001f));
            }
            GlobalIndexResult missing =
                    reader.visitIsNull(new FieldRef(0, "embedding", fields.get(0).type()))
                            .join()
                            .orElseThrow();
            assertThat(rows(missing)).containsExactly(128L);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ik_smart", "ik_max_word"})
    void nativeIkModesUseThePinnedDictionary(String analyzer) throws Exception {
        List<DataField> fields = List.of(new DataField(0, "text", DataTypes.STRING()));
        ESIndexOptions options =
                nativeOptions(
                        fields,
                        Options.fromMap(
                                Map.of("global-index.es-index.fields.text.analyzer", analyzer)));
        ResultEntry entry;
        try (ESIndexGlobalIndexWriter writer = writer(fields, options)) {
            writer.writeRow(0, GenericRow.of(BinaryString.fromString("中华人民共和国")));
            writer.writeRow(1, GenericRow.of(BinaryString.fromString("人民")));
            writer.writeRow(2, GenericRow.of((Object) null));
            entry = writer.finish().get(0);
        }
        try (ESIndexGlobalIndexReader reader = reader(entry, fields, options)) {
            Optional<ScoredGlobalIndexResult> shortWord =
                    reader.visitFullTextSearch(
                                    new FullTextSearch(
                                            "text", "{\"match\":{\"query\":\"中华\"}}", 10))
                            .join();
            Set<Long> actual =
                    shortWord.map(ESIndexNativeCapabilityTest::rows).orElseGet(HashSet::new);
            assertThat(actual).isEqualTo(analyzer.equals("ik_smart") ? Set.of() : Set.of(0L));
            ScoredGlobalIndexResult people =
                    reader.visitFullTextSearch(
                                    new FullTextSearch(
                                            "text", "{\"match\":{\"query\":\"人民\"}}", 10))
                            .join()
                            .orElseThrow();
            assertThat(rows(people))
                    .isEqualTo(analyzer.equals("ik_smart") ? Set.of(1L) : Set.of(0L, 1L));
            assertThat(
                            rows(
                                    reader.visitIsNull(new FieldRef(0, "text", DataTypes.STRING()))
                                            .join()
                                            .orElseThrow()))
                    .containsExactly(2L);
        }
    }

    private ESIndexGlobalIndexWriter writer(List<DataField> fields, ESIndexOptions options)
            throws IOException {
        return new ESIndexGlobalIndexWriter(
                new GlobalIndexFileWriter() {
                    @Override
                    public String newFileName(String prefix) {
                        return prefix + "-" + UUID.randomUUID();
                    }

                    @Override
                    public PositionOutputStream newOutputStream(String name) throws IOException {
                        return LocalFileIO.create()
                                .newOutputStream(
                                        new org.apache.paimon.fs.Path(
                                                directory.resolve(name).toString()),
                                        false);
                    }
                },
                fields,
                options);
    }

    private ESIndexGlobalIndexReader reader(
            ResultEntry entry, List<DataField> fields, ESIndexOptions options) throws IOException {
        Path archive = directory.resolve(entry.fileName());
        GlobalIndexIOMeta meta =
                new GlobalIndexIOMeta(
                        new org.apache.paimon.fs.Path(archive.toString()),
                        Files.size(archive),
                        entry.meta());
        return new ESIndexGlobalIndexReader(
                file -> LocalFileIO.create().newInputStream(file.filePath()),
                List.of(meta),
                fields,
                options);
    }

    private static Set<Long> rows(GlobalIndexResult result) {
        Set<Long> rows = new HashSet<>();
        for (long row : result.results()) {
            rows.add(row);
        }
        return rows;
    }
}
