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

package org.apache.paimon.globalindex;

import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.TableQueryAuthResult;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.serializer.InternalRowSerializer;
import org.apache.paimon.fs.Path;
import org.apache.paimon.index.DataEvolutionIndexSourceMeta;
import org.apache.paimon.index.GlobalIndexMeta;
import org.apache.paimon.index.IndexFileMeta;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.io.DataOutputSerializer;
import org.apache.paimon.manifest.IndexManifestEntry;
import org.apache.paimon.options.Options;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.SpecialFields;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.ScanMode;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.table.source.snapshot.TimeTravelUtil;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.CloseableIterator;
import org.apache.paimon.utils.Range;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.apache.paimon.globalindex.EsSearchQuery.require;

/** Fixed-snapshot, index-only ES search planning, independent of a particular engine. */
public final class EsIndexSearch {
    private EsIndexSearch() {}

    /** No native library is loaded while planning. The table retains catalog-supplied FileIO. */
    public static Plan plan(FileStoreTable original, List<Integer> indexFields) {
        TableQueryAuthResult.rejectSearchUnderQueryAuth(original);
        require(
                original.coreOptions().dataEvolutionEnabled() && original.primaryKeys().isEmpty(),
                "paimon_es_search requires an append-only data-evolution table");
        require(
                !indexFields.isEmpty() && new HashSet<>(indexFields).size() == indexFields.size(),
                "Select one nonempty index field group without duplicate IDs");
        Snapshot snapshot = TimeTravelUtil.tryTravelOrLatest(original);
        require(snapshot != null, "The table has no committed snapshot");
        FileStoreTable table =
                original.copy(
                        Collections.singletonMap("scan.snapshot-id", Long.toString(snapshot.id())));
        List<DataField> fields = new ArrayList<>();
        for (int id : indexFields) {
            DataField field =
                    table.rowType().getFields().stream()
                            .filter(candidate -> candidate.id() == id)
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Unknown index field ID: " + id));
            fields.add(field);
        }
        List<Shard> shards = new ArrayList<>();
        List<DataFileMeta> liveFiles = files(table, snapshot);
        List<IndexManifestEntry> selectedEntries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (IndexManifestEntry entry :
                table.store()
                        .newIndexFileHandler()
                        .scan(
                                snapshot,
                                value -> "es-index".equals(value.indexFile().indexType()))) {
            IndexFileMeta file = entry.indexFile();
            GlobalIndexMeta global = file.globalIndexMeta();
            if (global == null || !global.getIndexedFieldIds().equals(indexFields)) {
                continue;
            }
            require(
                    shards.size() < 10000 && names.add(file.fileName()),
                    "Too many or duplicate ES index shards");
            require(
                    global.rowRangeStart() >= 0
                            && global.rowRangeEnd() >= global.rowRangeStart()
                            && global.rowRangeEnd() < Long.MAX_VALUE,
                    "Invalid ES index row range");
            Range range = new Range(global.rowRangeStart(), global.rowRangeEnd());
            long sourceId = DataEvolutionIndexSourceMeta.fromIndexFile(file).scanSnapshotId();
            require(sourceId <= snapshot.id(), "Index source snapshot is in the future");
            selectedEntries.add(entry);
            shards.add(
                    new Shard(
                            table,
                            fields,
                            file,
                            table.store().newIndexFileHandler().filePath(entry).toString(),
                            range.from,
                            range.to));
        }
        require(!shards.isEmpty(), "No es-index/ESM1 index matches the selected field group");
        // Reuse Paimon's per-column sequence check. The original construction snapshot may
        // already have expired; checking current manifests does not require retaining it.
        require(
                DataEvolutionGlobalIndexRefreshPlanner.findIndexesToRefresh(
                                table, snapshot, null, selectedEntries, fields)
                        .isEmpty(),
                "ES indexed columns changed since construction; refresh the index");
        shards.sort(Comparator.comparingLong(value -> value.from));
        long previousEnd = -1;
        List<Range> covered = new ArrayList<>();
        for (Shard shard : shards) {
            require(
                    shard.from > previousEnd,
                    "Overlapping ES index generations; rebuild the index");
            previousEnd = shard.to;
            covered.add(new Range(shard.from, shard.to));
        }
        List<Range> live = new ArrayList<>();
        for (DataFileMeta file : liveFiles) {
            require(file.firstRowId() != null, "Data file has no tracked row IDs");
            live.add(file.nonNullRowIdRange());
        }
        List<Range> mergedCovered = Range.sortAndMergeOverlap(covered, true);
        List<Range> mergedLive = Range.sortAndMergeOverlap(live, true);
        require(
                sameRanges(mergedCovered, mergedLive),
                "ES index does not exactly cover the selected snapshot; build missing indexes");
        return new Plan(table, snapshot.id(), shards);
    }

    private static boolean sameRanges(List<Range> a, List<Range> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).from != b.get(i).from || a.get(i).to != b.get(i).to) {
                return false;
            }
        }
        return true;
    }

    private static List<DataFileMeta> files(FileStoreTable table, Snapshot snapshot) {
        List<DataFileMeta> files = new ArrayList<>();
        for (Split split :
                table.newSnapshotReader()
                        .withSnapshot(snapshot)
                        .withMode(ScanMode.ALL)
                        .read()
                        .splits()) {
            require(split instanceof DataSplit, "Unexpected ES search data split");
            require(
                    !split.deletionFiles().orElse(Collections.emptyList()).stream()
                            .anyMatch(Objects::nonNull),
                    "This ES search entry does not support snapshots with deletion vectors");
            files.addAll(((DataSplit) split).dataFiles());
            require(files.size() <= 100000, "ES search metadata exceeds its file limit");
        }
        return files;
    }

    /** Serializable values only: readers and native handles are opened and closed by each task. */
    public static final class Shard implements Serializable {
        private static final long serialVersionUID = 1L;
        private final FileStoreTable table;
        private final List<DataField> fields;
        private final long fileSize;
        private final byte[] indexMeta;
        private final String path;
        private final long from;
        private final long to;

        private Shard(
                FileStoreTable table,
                List<DataField> fields,
                IndexFileMeta file,
                String path,
                long from,
                long to) {
            this.table = table;
            this.fields = new ArrayList<>(fields);
            this.fileSize = file.fileSize();
            this.indexMeta = file.globalIndexMeta().indexMeta();
            this.path = path;
            this.from = from;
            this.to = to;
        }

        public List<Hit> search(EsSearchQuery query) throws IOException {
            GlobalIndexer indexer =
                    GlobalIndexer.create("es-index", fields, Options.fromMap(table.options()));
            GlobalIndexIOMeta meta = new GlobalIndexIOMeta(new Path(path), fileSize, indexMeta);
            try (GlobalIndexReader reader =
                    indexer.createReader(
                            value -> table.fileIO().newInputStream(value.filePath()),
                            Collections.singletonList(meta),
                            to - from + 1,
                            null,
                            null)) {
                ScoredGlobalIndexResult result = reader.visitEsSearch(query).join();
                List<Hit> hits = new ArrayList<>();
                for (long relative : result.results()) {
                    require(
                            relative >= 0 && relative <= to - from,
                            "Native ES search returned an invalid relative row ID");
                    float score = result.scoreGetter().score(relative);
                    require(Float.isFinite(score), "Native ES search returned a nonfinite score");
                    hits.add(new Hit(Math.addExact(from, relative), score));
                }
                require(hits.size() <= query.limit(), "Native ES result window overflow");
                return hits;
            }
        }
    }

    /** Native score and absolute Paimon row identity returned by an executor. */
    public static final class Hit implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long rowId;
        public final float score;

        public Hit(long rowId, float score) {
            this.rowId = rowId;
            this.score = score;
        }
    }

    /** Immutable snapshot selection with serializable shard work and optional source fetching. */
    public static final class Plan implements Serializable {
        private static final long serialVersionUID = 1L;
        public final FileStoreTable table;
        public final long snapshotId;
        public final List<Shard> shards;

        private Plan(FileStoreTable table, long snapshotId, List<Shard> shards) {
            this.table = table;
            this.snapshotId = snapshotId;
            this.shards = Collections.unmodifiableList(shards);
        }

        /** Source schema includes the tracked _ROW_ID field used to validate every fetched row. */
        public RowType sourceType() {
            return SpecialFields.rowTypeWithRowId(table.rowType());
        }

        /** Fetch only the globally selected hits from the exact planned snapshot. */
        public Map<Long, byte[]> fetch(List<Hit> hits) throws Exception {
            Map<Long, byte[]> source = new HashMap<>();
            if (hits.isEmpty()) {
                return source;
            }
            require(hits.size() <= EsSearchQuery.MAX_WINDOW, "Source window exceeds its limit");
            Set<Long> expected = new HashSet<>();
            List<Range> ranges = new ArrayList<>();
            for (Hit hit : hits) {
                require(expected.add(hit.rowId), "Duplicate source row ID");
                ranges.add(new Range(hit.rowId, hit.rowId));
            }
            RowType type = sourceType();
            int idPosition = type.getFieldIndex(SpecialFields.ROW_ID.name());
            ReadBuilder builder =
                    table.newReadBuilder()
                            .withReadType(type)
                            .withRowRanges(Range.sortAndMergeOverlap(ranges, true));
            InternalRowSerializer serializer = new InternalRowSerializer(type);
            long bytes = 0;
            try (CloseableIterator<InternalRow> rows =
                    builder.newRead()
                            .createReader(builder.newScan().plan())
                            .toCloseableIterator()) {
                while (rows.hasNext()) {
                    InternalRow row = rows.next();
                    long id = row.getLong(idPosition);
                    if (!expected.contains(id)) {
                        continue;
                    }
                    require(!source.containsKey(id), "Source read repeated a row ID");
                    DataOutputSerializer output = new DataOutputSerializer(1024);
                    serializer.serialize(row, output);
                    bytes = Math.addExact(bytes, output.length());
                    require(bytes <= 32L * 1024 * 1024, "ES search source exceeds 32 MiB");
                    source.put(id, output.getCopyOfBuffer());
                }
            }
            require(
                    source.keySet().equals(expected),
                    "ES search hits are missing from the planned snapshot");
            return source;
        }
    }
}
