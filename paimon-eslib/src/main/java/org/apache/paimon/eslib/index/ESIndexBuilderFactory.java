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

import org.apache.paimon.eslib.internal.ESIndexBuilder;
import org.apache.paimon.eslib.internal.ESIndexSearcher;
import org.apache.paimon.eslib.internal.model.FieldIndexConfig;
import org.apache.paimon.eslib.nativeindex.NativeESIndexBuilder;
import org.apache.paimon.eslib.nativeindex.NativeESIndexSearcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Creates the native implementation compiled into this Paimon module. */
public class ESIndexBuilderFactory {

    static final String JAVA = "java";
    static final String ELASTICPP = "elasticpp";

    static String backend(String value) {
        String backend = value.toLowerCase(Locale.ROOT);
        if (!ELASTICPP.equals(backend)) {
            throw new IllegalArgumentException(
                    "Paimon es-index directly uses elasticpp; Java ESLib is not packaged. "
                            + "Read legacy Java indexes with the original module or rebuild them.");
        }
        return backend;
    }

    public static ESIndexBuilder create(Map<String, FieldIndexConfig> fieldConfigs)
            throws IOException {
        return new NativeESIndexBuilder(fieldConfigs);
    }

    static ESIndexBuilder create(Map<String, FieldIndexConfig> fieldConfigs, Path outputDirectory)
            throws IOException {
        return new NativeESIndexBuilder(fieldConfigs, outputDirectory);
    }

    public static ESIndexSearcher createSearcher() {
        return new NativeESIndexSearcher();
    }
}
