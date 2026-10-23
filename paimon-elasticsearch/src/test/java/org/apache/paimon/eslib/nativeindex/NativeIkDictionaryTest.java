/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import org.apache.paimon.eslib.internal.model.BuiltinAnalyzer;
import org.apache.paimon.eslib.internal.model.FieldIndexConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeIkDictionaryTest {
    @TempDir Path root;

    private Path dictionary(String name) throws IOException {
        Path path = Files.createDirectories(root.resolve(name));
        Files.writeString(path.resolve("main.dic"), "中华人民共和国\n中华\n人民\n共和国\n");
        Files.writeString(path.resolve("quantifier.dic"), "个\n");
        Files.writeString(path.resolve("stopword.dic"), "");
        return path;
    }

    private Map<String, FieldIndexConfig> fields(BuiltinAnalyzer analyzer) {
        return Collections.singletonMap(
                "title",
                FieldIndexConfig.builder("title", FieldIndexConfig.IndexType.FULLTEXT)
                        .analyzer(analyzer)
                        .build());
    }

    @Test
    void bothIkModesKeepTheirNativeAnalyzerIdentity() throws Exception {
        NativeIkDictionary dictionary = NativeIkDictionary.read(dictionary("dict"));
        for (BuiltinAnalyzer mode :
                new BuiltinAnalyzer[] {BuiltinAnalyzer.IK_SMART, BuiltinAnalyzer.IK_MAX_WORD}) {
            NativeMapping mapping = new NativeMapping(fields(mode), dictionary);
            assertEquals(
                    mode.getName(),
                    NativeJson.parse(mapping.mapping)
                            .path("properties")
                            .path("title")
                            .path("analyzer")
                            .asText());
            assertEquals(3, NativeJson.parse(mapping.config).path("__native_ik_dictionary").size());
            assertThrows(IOException.class, () -> new NativeMapping(fields(mode), null));
        }
    }

    @Test
    void archiveIdentityDependsOnDictionaryContentsAndNotItsMachinePath() throws Exception {
        Path firstPath = dictionary("first"), secondPath = dictionary("second");
        NativeMapping first =
                new NativeMapping(
                        fields(BuiltinAnalyzer.IK_SMART), NativeIkDictionary.read(firstPath));
        NativeMapping second =
                new NativeMapping(
                        fields(BuiltinAnalyzer.IK_SMART), NativeIkDictionary.read(secondPath));
        assertArrayEquals(first.config, second.config);
        for (String name : new String[] {"main.dic", "quantifier.dic", "stopword.dic"}) {
            Path changed = dictionary("changed-" + name);
            Files.writeString(
                    changed.resolve(name), Files.readString(changed.resolve(name)) + "新词\n");
            NativeMapping different =
                    new NativeMapping(
                            fields(BuiltinAnalyzer.IK_SMART), NativeIkDictionary.read(changed));
            assertFalse(java.util.Arrays.equals(first.config, different.config), name);
        }
    }

    @Test
    void missingDictionaryFilesAreRejectedBeforeAnyNativeCall() throws Exception {
        Path path = dictionary("dict");
        Files.delete(path.resolve("quantifier.dic"));
        assertThrows(IOException.class, () -> NativeIkDictionary.read(path));
        Files.writeString(path.resolve("quantifier.dic"), "个\n");
        Files.writeString(path.resolve("main.dic"), "");
        assertThrows(IOException.class, () -> NativeIkDictionary.read(path));
    }
}
