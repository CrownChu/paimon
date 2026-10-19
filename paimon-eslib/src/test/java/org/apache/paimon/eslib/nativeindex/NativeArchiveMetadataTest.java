/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import org.apache.paimon.eslib.internal.model.FieldIndexConfig;
import org.apache.paimon.eslib.internal.model.ScalarFieldType;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeArchiveMetadataTest {
    private static final String SHA = "01".repeat(32);
    private static final byte[] MAPPING =
            NativeJson.utf8(
                    "{\"_source\":{\"enabled\":false},\"properties\":{\"f0\":{\"coerce\":false,\"type\":\"long\"}}}");

    private static NativeArchiveMetadata metadata(
            byte[] mapping, long start, Map<String, NativeArchiveMetadata.File> files)
            throws IOException {
        return new NativeArchiveMetadata(
                "table", 31, 7, start, 3, SHA, SHA, mapping, "12".repeat(20), SHA, files);
    }

    private static NativeMapping query(ScalarFieldType type) throws IOException {
        return new NativeMapping(
                Collections.singletonMap(
                        "f0",
                        FieldIndexConfig.builder("f0", FieldIndexConfig.IndexType.SCALAR)
                                .scalarType(type)
                                .build()));
    }

    @Test
    void importedIdentityOwnsItsMappingAndFileTableAndChecksPhysicalTypes() throws Exception {
        byte[] mapping = MAPPING.clone();
        Map<String, NativeArchiveMetadata.File> files = new LinkedHashMap<>();
        files.put("segments_1", new NativeArchiveMetadata.File(10, SHA));
        NativeArchiveMetadata metadata = metadata(mapping, 9007199254740993L, files);
        mapping[0] = 0;
        files.clear();
        NativeIndexManifest value = metadata.manifest(query(ScalarFieldType.LONG));
        assertArrayEquals(MAPPING, value.mapping);
        assertEquals(1, value.files.size());
        assertEquals(9007199254740993L, value.rowStart);
        assertThrows(IOException.class, () -> metadata.manifest(query(ScalarFieldType.INT)));
    }

    @Test
    void rejectsAnOverflowingRowSpanAndUnsafeFileNames() {
        Map<String, NativeArchiveMetadata.File> files =
                Collections.singletonMap("segments_1", new NativeArchiveMetadata.File(10, SHA));
        assertThrows(IOException.class, () -> metadata(MAPPING, Long.MAX_VALUE - 1, files));
        assertThrows(
                IOException.class,
                () ->
                        metadata(
                                MAPPING,
                                0,
                                Collections.singletonMap(
                                        "../segments_1", new NativeArchiveMetadata.File(10, SHA))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeArchiveMetadata.File(10, "not-a-sha"));
    }

    @Test
    void rejectsAdditionalReadSettingsButAllowsOriginalWriteOnlyCoercionDefaults()
            throws Exception {
        Map<String, NativeArchiveMetadata.File> files =
                Collections.singletonMap("segments_1", new NativeArchiveMetadata.File(10, SHA));
        byte[] defaultCoercion =
                NativeJson.utf8(
                        new String(MAPPING, java.nio.charset.StandardCharsets.UTF_8)
                                .replace("\"coerce\":false,", ""));
        metadata(defaultCoercion, 0, files).manifest(query(ScalarFieldType.LONG));
        byte[] docValuesDisabled =
                NativeJson.utf8(
                        new String(MAPPING, java.nio.charset.StandardCharsets.UTF_8)
                                .replace(
                                        "\"type\":\"long\"",
                                        "\"type\":\"long\",\"doc_values\":false"));
        assertThrows(
                IOException.class,
                () -> metadata(docValuesDisabled, 0, files).manifest(query(ScalarFieldType.LONG)));
    }
}
