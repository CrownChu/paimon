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

package org.apache.paimon.eslib.javaindex;

import org.apache.paimon.eslib.internal.ESIndexBuilder;
import org.apache.paimon.eslib.internal.model.FieldIndexConfig;
import org.apache.paimon.eslib.internal.model.ScalarFieldType;

import org.elasticsearch.eslib.builder.DefaultESIndexBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Java/Lucene writer; constructing it never loads elasticpp. */
public final class JavaESIndexBuilder implements ESIndexBuilder {
    private final DefaultESIndexBuilder delegate;

    public JavaESIndexBuilder(Map<String, FieldIndexConfig> fields) throws IOException {
        delegate = new DefaultESIndexBuilder(JavaTypes.fields(fields));
    }

    public JavaESIndexBuilder(Map<String, FieldIndexConfig> fields, Path directory)
            throws IOException {
        delegate = new DefaultESIndexBuilder(JavaTypes.fields(fields), directory);
    }

    @Override
    public void addVector(String field, long row, float[] vector) throws IOException {
        delegate.addVector(field, row, vector);
    }

    @Override
    public void addTextField(String field, long row, String text) throws IOException {
        delegate.addTextField(field, row, text);
    }

    @Override
    public void addScalarField(String field, long row, Object value, ScalarFieldType type)
            throws IOException {
        delegate.addScalarField(
                field,
                row,
                value,
                org.elasticsearch.eslib.api.model.ScalarFieldType.valueOf(type.name()));
    }

    @Override
    public void addNullDoc(long row) throws IOException {
        delegate.addNullDoc(row);
    }

    @Override
    public void finishDocument(long row) throws IOException {
        delegate.finishDocument(row);
    }

    @Override
    public void build() throws IOException {
        delegate.build();
    }

    @Override
    public Path getOutputDir() {
        return delegate.getOutputDir();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
