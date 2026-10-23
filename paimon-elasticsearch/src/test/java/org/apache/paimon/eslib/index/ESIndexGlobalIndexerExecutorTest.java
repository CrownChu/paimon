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

import org.apache.paimon.fs.Path;
import org.apache.paimon.globalindex.GlobalIndexIOMeta;
import org.apache.paimon.globalindex.GlobalIndexReader;
import org.apache.paimon.options.Options;
import org.apache.paimon.predicate.FullTextSearch;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataTypes;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies that Paimon's caller-owned executor is used for the outer reader future. */
class ESIndexGlobalIndexerExecutorTest {

    private static final List<DataField> FIELDS =
            Arrays.asList(new DataField(0, "k", DataTypes.STRING()));

    private static List<GlobalIndexIOMeta> oneFile() throws IOException {
        java.nio.file.Path segment = java.nio.file.Files.createTempFile("segments_", ".test");
        try {
            byte[] metadata =
                    ESIndexFileMeta.write(
                            new java.io.File[] {segment.toFile()},
                            List.of("k"),
                            List.of("STRING"),
                            new ESIndexOptions(FIELDS, new Options()).getFieldConfigs());
            long size =
                    ESIndexFileMeta.read(metadata)
                            .fileOffsets()
                            .get(segment.getFileName().toString())[0];
            return List.of(new GlobalIndexIOMeta(new Path("/tmp/none.index"), size, metadata));
        } finally {
            java.nio.file.Files.delete(segment);
        }
    }

    @Test
    void callerExecutorIsPassedToReader() throws IOException {
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            ESIndexGlobalIndexer indexer = new ESIndexGlobalIndexer(FIELDS, new Options());
            GlobalIndexReader reader =
                    indexer.createReader(meta -> null, oneFile(), 1, null, caller);
            assertSame(
                    caller,
                    ((ESIndexGlobalIndexReader) reader).queryExecutor(),
                    "outer reader futures must use Paimon's caller executor");
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void inlineValidationFailuresCompleteTheReturnedFutureExceptionally() throws Exception {
        ESIndexGlobalIndexer indexer = new ESIndexGlobalIndexer(FIELDS, new Options());
        ESIndexGlobalIndexReader reader =
                (ESIndexGlobalIndexReader)
                        indexer.createReader(meta -> null, oneFile(), 1, null, null);
        try {
            CompletableFuture<?> future =
                    reader.visitFullTextSearch(new FullTextSearch("k", "{not-json", 1));
            assertThrows(CompletionException.class, future::join);
        } finally {
            reader.close();
        }
    }
}
