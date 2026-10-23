/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.core.JsonParser;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

final class NativeJson {
    static final ObjectMapper MAPPER =
            new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    static final Comparator<String> UTF8_ORDER =
            (a, b) -> {
                byte[] left = utf8(a), right = utf8(b);
                for (int i = 0; i < Math.min(left.length, right.length); i++) {
                    int c = Integer.compare(left[i] & 255, right[i] & 255);
                    if (c != 0) {
                        return c;
                    }
                }
                return Integer.compare(left.length, right.length);
            };

    static Map<String, Object> object(Object... entries) {
        if ((entries.length & 1) != 0) {
            throw new IllegalArgumentException("Unpaired JSON entries");
        }
        Map<String, Object> result = new TreeMap<>(UTF8_ORDER);
        for (int i = 0; i < entries.length; i += 2) {
            String key = (String) entries[i];
            if (result.put(key, entries[i + 1]) != null) {
                throw new IllegalArgumentException("Duplicate JSON key");
            }
        }
        return result;
    }

    static byte[] encode(Object value) throws IOException {
        return MAPPER.writeValueAsBytes(value);
    }

    static JsonNode parse(byte[] bytes) throws IOException {
        try (JsonParser parser = MAPPER.getFactory().createParser(bytes)) {
            JsonNode node = MAPPER.readTree(parser);
            if (node == null || parser.nextToken() != null) {
                throw new IOException("Missing JSON or trailing bytes");
            }
            return node;
        }
    }

    static byte[] utf8(String value) {
        try {
            ByteBuffer bytes =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(java.nio.CharBuffer.wrap(value));
            byte[] result = new byte[bytes.remaining()];
            bytes.get(result);
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid Unicode string", e);
        }
    }

    static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >>> 4) & 15, 16))
                    .append(Character.forDigit(b & 15, 16));
        }
        return out.toString();
    }

    static byte[] unhex(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid SHA-256");
        }
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    private NativeJson() {}
}
