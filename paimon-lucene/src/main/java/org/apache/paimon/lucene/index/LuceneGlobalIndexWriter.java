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
import org.apache.paimon.data.InternalArray;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.InternalVector;
import org.apache.paimon.fs.PositionOutputStream;
import org.apache.paimon.globalindex.GlobalIndexMultiColumnWriter;
import org.apache.paimon.globalindex.GlobalIndexSingletonWriter;
import org.apache.paimon.globalindex.ResultEntry;
import org.apache.paimon.globalindex.io.GlobalIndexFileWriter;
import org.apache.paimon.types.ArrayType;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypeRoot;
import org.apache.paimon.types.VectorType;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

/**
 * Lucene global index writer supporting multi-column (text + vector) indexing.
 *
 * <p>Writes Lucene documents to a temp directory, then packs them into a single archive file via
 * {@link GlobalIndexFileWriter} on {@link #finish()}.
 */
public class LuceneGlobalIndexWriter
        implements GlobalIndexMultiColumnWriter, GlobalIndexSingletonWriter, Closeable {

    private static final String FILE_NAME_PREFIX = "lucene";
    private static final String ROW_ID_FIELD = "_row_id";

    private final GlobalIndexFileWriter fileWriter;
    private final List<DataField> fields;
    private final LuceneIndexOptions options;

    private java.nio.file.Path tempDir;
    private FSDirectory directory;
    private IndexWriter indexWriter;
    private long rowCount;
    private boolean closed;

    public LuceneGlobalIndexWriter(
            GlobalIndexFileWriter fileWriter, List<DataField> fields, LuceneIndexOptions options)
            throws IOException {
        this.fileWriter = fileWriter;
        this.fields = fields;
        this.options = options;
        this.rowCount = 0;
        this.closed = false;

        this.tempDir = Files.createTempDirectory("lucene-index-");
        this.directory = FSDirectory.open(tempDir);
        IndexWriterConfig config = new IndexWriterConfig();
        this.indexWriter = new IndexWriter(directory, config);
    }

    @Override
    public void write(InternalRow row) {
        if (row == null) {
            return;
        }
        try {
            Document doc = new Document();
            doc.add(new StoredField(ROW_ID_FIELD, rowCount));

            for (int i = 0; i < fields.size(); i++) {
                DataField field = fields.get(i);
                addFieldToDocument(doc, field, row, i);
            }

            indexWriter.addDocument(doc);
            rowCount++;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write document to Lucene index", e);
        }
    }

    @Override
    public void write(Object key) {
        if (key == null) {
            return;
        }
        try {
            Document doc = new Document();
            doc.add(new StoredField(ROW_ID_FIELD, rowCount));

            DataField field = fields.get(0);
            if (isTextField(field.type())) {
                String text = key instanceof BinaryString ? key.toString() : String.valueOf(key);
                doc.add(
                        new TextField(
                                field.name(), text, org.apache.lucene.document.Field.Store.NO));
            } else if (isVectorField(field.type())) {
                float[] vector = toFloatArray(key, field.type());
                doc.add(
                        new KnnFloatVectorField(
                                field.name(), vector, toSimilarityFunction(options.similarity())));
            }

            indexWriter.addDocument(doc);
            rowCount++;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write document to Lucene index", e);
        }
    }

    @Override
    public List<ResultEntry> finish() {
        try {
            if (rowCount == 0) {
                return Collections.emptyList();
            }

            indexWriter.commit();
            indexWriter.close();
            indexWriter = null;

            String fileName = fileWriter.newFileName(FILE_NAME_PREFIX);
            try (PositionOutputStream out = fileWriter.newOutputStream(fileName)) {
                writeArchive(out);
            }

            return Collections.singletonList(new ResultEntry(fileName, rowCount, null));
        } catch (IOException e) {
            throw new RuntimeException("Failed to finish Lucene index", e);
        } finally {
            cleanupTempDir();
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                if (indexWriter != null) {
                    indexWriter.close();
                    indexWriter = null;
                }
            } catch (IOException ignored) {
            }
            if (directory != null) {
                try {
                    directory.close();
                } catch (IOException ignored) {
                }
                directory = null;
            }
            cleanupTempDir();
        }
    }

    private void addFieldToDocument(Document doc, DataField field, InternalRow row, int pos) {
        DataType type = field.type();
        if (isTextField(type)) {
            BinaryString bs = row.getString(pos);
            if (bs != null) {
                doc.add(
                        new TextField(
                                field.name(),
                                bs.toString(),
                                org.apache.lucene.document.Field.Store.NO));
            }
        } else if (isVectorField(type)) {
            float[] vector = extractVector(row, pos, type);
            if (vector != null) {
                doc.add(
                        new KnnFloatVectorField(
                                field.name(), vector, toSimilarityFunction(options.similarity())));
            }
        }
    }

    private float[] extractVector(InternalRow row, int pos, DataType type) {
        if (type instanceof VectorType) {
            InternalVector vec = row.getVector(pos);
            if (vec == null) {
                return null;
            }
            float[] result = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                result[i] = vec.getFloat(i);
            }
            return result;
        } else if (type instanceof ArrayType) {
            InternalArray arr = row.getArray(pos);
            if (arr == null) {
                return null;
            }
            float[] result = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                result[i] = arr.getFloat(i);
            }
            return result;
        }
        return null;
    }

    private float[] toFloatArray(Object key, DataType type) {
        if (key instanceof float[]) {
            return (float[]) key;
        } else if (key instanceof InternalVector) {
            InternalVector vec = (InternalVector) key;
            float[] result = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                result[i] = vec.getFloat(i);
            }
            return result;
        } else if (key instanceof InternalArray) {
            InternalArray arr = (InternalArray) key;
            float[] result = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                result[i] = arr.getFloat(i);
            }
            return result;
        }
        throw new IllegalArgumentException("Cannot convert to float[]: " + key.getClass());
    }

    static boolean isTextField(DataType type) {
        return type.getTypeRoot() == DataTypeRoot.VARCHAR
                || type.getTypeRoot() == DataTypeRoot.CHAR;
    }

    static boolean isVectorField(DataType type) {
        return type instanceof VectorType
                || (type instanceof ArrayType
                        && ((ArrayType) type).getElementType().getTypeRoot() == DataTypeRoot.FLOAT);
    }

    static VectorSimilarityFunction toSimilarityFunction(String similarity) {
        switch (similarity.toLowerCase()) {
            case "cosine":
                return VectorSimilarityFunction.COSINE;
            case "dot_product":
            case "inner_product":
                return VectorSimilarityFunction.DOT_PRODUCT;
            case "euclidean":
            case "l2":
            default:
                return VectorSimilarityFunction.EUCLIDEAN;
        }
    }

    private void writeArchive(PositionOutputStream out) throws IOException {
        String[] fileNames = directory.listAll();
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(fileNames.length);
        for (String name : fileNames) {
            byte[] nameBytes = name.getBytes("UTF-8");
            dos.writeInt(nameBytes.length);
            dos.write(nameBytes);

            long fileLength = directory.fileLength(name);
            dos.writeLong(fileLength);

            try (org.apache.lucene.store.IndexInput input =
                    directory.openInput(name, org.apache.lucene.store.IOContext.READONCE)) {
                byte[] buffer = new byte[8192];
                long remaining = fileLength;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    input.readBytes(buffer, 0, toRead);
                    dos.write(buffer, 0, toRead);
                    remaining -= toRead;
                }
            }
        }
        dos.flush();
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
}
