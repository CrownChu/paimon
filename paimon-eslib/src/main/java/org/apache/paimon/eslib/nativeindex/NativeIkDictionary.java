/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import org.apache.paimon.eslib.internal.model.BuiltinAnalyzer;
import org.apache.paimon.eslib.internal.model.FieldIndexConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Identifies the external dictionary used by elasticpp's process-wide IK dictionary. */
final class NativeIkDictionary {
    private static Map<String, String> pinnedFiles;
    final Map<String, String> files;

    private NativeIkDictionary(Map<String, String> files) {
        this.files = Collections.unmodifiableMap(files);
    }

    static boolean required(Map<String, FieldIndexConfig> fields) {
        if (fields == null) {
            return false;
        }
        for (FieldIndexConfig field : fields.values()) {
            if (field != null
                    && field.indexType() == FieldIndexConfig.IndexType.FULLTEXT
                    && (field.analyzer() == BuiltinAnalyzer.IK_SMART
                            || field.analyzer() == BuiltinAnalyzer.IK_MAX_WORD)) {
                return true;
            }
        }
        return false;
    }

    static synchronized NativeIkDictionary selected(Map<String, FieldIndexConfig> fields)
            throws IOException {
        if (!required(fields)) {
            return null;
        }
        String config = System.getenv("ES_PATH_CONF");
        if (config == null || config.isEmpty()) {
            throw new IOException("Native IK requires ES_PATH_CONF with an analysis-ik directory");
        }
        NativeIkDictionary dictionary = read(Path.of(config).resolve("analysis-ik"));
        if (pinnedFiles != null && !pinnedFiles.equals(dictionary.files)) {
            throw new IOException("Native IK dictionaries changed; use a fresh process");
        }
        pinnedFiles = dictionary.files;
        return dictionary;
    }

    static NativeIkDictionary read(Path directory) throws IOException {
        Map<String, String> files = new TreeMap<>();
        for (String name : new String[] {"main.dic", "quantifier.dic", "stopword.dic"}) {
            Path file = directory.resolve(name);
            if (!Files.isRegularFile(file) || Files.size(file) > 64 * 1024 * 1024L) {
                throw new IOException(
                        "Native IK requires bounded main, quantifier and stopword dictionaries");
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > 64 * 1024 * 1024 || (name.equals("main.dic") && bytes.length == 0)) {
                throw new IOException("Native IK dictionary is empty or oversized");
            }
            files.put(name, NativeJson.hex(NativeJson.digest(bytes)));
        }
        return new NativeIkDictionary(files);
    }
}
