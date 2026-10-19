/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class NativeRuntime {
    static final String ROOT = "META-INF/native/linux-x86_64/";
    static final String TARGET_TRIPLE = "x86_64-unknown-linux-gnu";
    private static NativeRuntime cached;
    private static boolean loadingAttempted;
    private final ElasticppLibrary api;
    // Anchor the loader and the extracted files for the complete process lifetime.
    private final NativeLibrary anchor;
    final String commit, sharedObjectSha256;
    final int maximumDimensions;

    private interface LibC extends Library {
        String gnu_get_libc_version();
    }

    private NativeRuntime(
            ElasticppLibrary api, NativeLibrary anchor, String commit, String sha, int dimensions) {
        this.api = api;
        this.anchor = anchor;
        this.commit = commit;
        this.sharedObjectSha256 = sha;
        this.maximumDimensions = dimensions;
    }

    static synchronized NativeRuntime load() throws IOException {
        if (cached != null) {
            return cached;
        }
        if (loadingAttempted) {
            throw new IOException("Native runtime initialization failed; use a fresh process");
        }
        loadingAttempted = true;
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")
                || !Arrays.asList("amd64", "x86_64").contains(System.getProperty("os.arch"))
                || Native.POINTER_SIZE != 8) {
            throw new IOException("Native ESLib requires Linux x86_64 LP64");
        }
        NativeClient.verifyLayouts();
        ClassLoader loader = NativeRuntime.class.getClassLoader();
        JsonNode manifest;
        try (InputStream stream = loader.getResourceAsStream(ROOT + "native-manifest.json")) {
            if (stream == null) {
                throw new IOException("Native platform runtime package is missing");
            }
            manifest = NativeJson.parse(readBounded(stream, 1048576));
        }
        if (!"eslib-native-bundle-v1".equals(text(manifest, "format"))
                || !TARGET_TRIPLE.equals(text(manifest, "targetTriple"))
                || !"10.4.0".equals(text(manifest, "luceneVersion"))) {
            throw new IOException("Native manifest baseline differs");
        }
        String commit = text(manifest, "elasticppCommit"),
                sha = text(manifest, "sharedObjectSha256");
        if (!commit.matches("[0-9a-f]{40}")) {
            throw new IOException("Missing immutable native source identity");
        }
        NativeJson.unhex(sha);
        String minimumGlibc = text(manifest, "minimumGlibc");
        if (!"x86-64-v3".equals(text(manifest, "cpuIsa"))) {
            throw new IOException("Native CPU contract is not supported by this candidate");
        }
        try (InputStream cpuInfo = Files.newInputStream(Path.of("/proc/cpuinfo"))) {
            requireV3Cpu(
                    new String(readBounded(cpuInfo, 8 * 1024 * 1024), StandardCharsets.US_ASCII));
        }
        try {
            if (compareVersions(Native.load("c", LibC.class).gnu_get_libc_version(), minimumGlibc)
                    < 0) {
                throw new IOException("Native package requires glibc " + minimumGlibc);
            }
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("Unable to verify native runtime platform", e);
        }
        Map<String, String> files = files(manifest);
        if (!sha.equals(files.get("libelasticpp.so"))) {
            throw new IOException("Native manifest library SHA differs");
        }
        Path directory =
                Files.createTempDirectory(
                        "paimon-native-runtime-" + sha.substring(0, 12) + "-",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        directory.toFile().deleteOnExit();
        // A fresh owner-only directory prevents concurrent replacement during dlopen.
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path output = directory.resolve(file.getKey());
            output.toFile().deleteOnExit();
            try (InputStream stream = loader.getResourceAsStream(ROOT + file.getKey())) {
                if (stream == null) {
                    throw new IOException(
                            "Packaged native dependency is missing: " + file.getKey());
                }
                MessageDigest hash = digest();
                try (java.io.OutputStream out = Files.newOutputStream(output)) {
                    byte[] buffer = new byte[1024 * 1024];
                    int length;
                    while ((length = stream.read(buffer)) != -1) {
                        hash.update(buffer, 0, length);
                        out.write(buffer, 0, length);
                    }
                }
                if (!file.getValue().equals(NativeJson.hex(hash.digest()))) {
                    throw new IOException("Native file digest mismatch");
                }
                Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("r-x------"));
            }
        }
        Map<String, Object> options = new HashMap<>();
        options.put(Library.OPTION_STRING_ENCODING, "UTF-8");
        options.put(Library.OPTION_OPEN_FLAGS, 0x1002); // RTLD_NOW | RTLD_NODELETE
        Path libraryPath = directory.resolve("libelasticpp.so");
        NativeLibrary anchor;
        ElasticppLibrary api;
        try {
            anchor = NativeLibrary.getInstance(libraryPath.toString(), options);
            for (String symbol : NativeClient.REQUIRED_SYMBOLS) {
                anchor.getFunction(symbol);
            }
            api = Native.load(libraryPath.toString(), ElasticppLibrary.class, options);
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("Native runtime ABI or dependency cannot be loaded", e);
        }
        Pointer pointer = api.getNativeEsCapabilitiesV1();
        if (pointer == null) {
            throw new IOException("Native capability allocation failed");
        }
        int dimensions;
        try {
            NativeClient.requireHeader(pointer, new ElasticppLibrary.Status().size());
            ElasticppLibrary.Capabilities capabilities = new ElasticppLibrary.Capabilities(pointer);
            NativeClient.requireStatus(capabilities.status, "native capability");
            NativeClient.require(
                    capabilities.minimumAbiVersion == 1
                            && capabilities.maximumAbiVersion == 1
                            && (capabilities.capabilityFlags & 127L) == 127L,
                    "Native writer/reader ABI capability differs");
            if (!commit.equals(
                            NativeClient.string(
                                    capabilities.gitCommit, capabilities.gitCommitLength, 128))
                    || !sha.equals(NativeJson.hex(capabilities.sharedObjectSha256))
                    || !text(manifest, "luceneVersion")
                            .equals(
                                    NativeClient.string(
                                            capabilities.luceneVersion,
                                            capabilities.luceneVersionLength,
                                            128))
                    || !text(manifest, "targetTriple")
                            .equals(
                                    NativeClient.string(
                                            capabilities.targetTriple,
                                            capabilities.targetTripleLength,
                                            128))
                    || !minimumGlibc.equals(
                            NativeClient.string(
                                    capabilities.minimumGlibc,
                                    capabilities.minimumGlibcLength,
                                    128))) {
                throw new IOException("Loaded native library differs from its published identity");
            }
            dimensions = capabilities.maximumVectorDimensions;
            NativeClient.require(
                    dimensions > 0 && dimensions <= 1024,
                    "Native vector dimension capability differs");
        } finally {
            api.freeNativeEsCapabilitiesV1(pointer);
        }
        cached = new NativeRuntime(api, anchor, commit, sha, dimensions);
        return cached;
    }

    NativeClient client() {
        return new NativeClient(api, commit, sharedObjectSha256, maximumDimensions);
    }

    static void requireV3Cpu(String cpuInfo) throws IOException {
        Set<String> required =
                new HashSet<>(
                        Arrays.asList(
                                "cx16", "lahf_lm", "popcnt", "pni", "ssse3", "sse4_1", "sse4_2",
                                "avx", "avx2", "bmi1", "bmi2", "f16c", "fma", "movbe", "xsave"));
        boolean found = false;
        for (String line : cpuInfo.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0 || !"flags".equals(line.substring(0, colon).trim())) {
                continue;
            }
            found = true;
            Set<String> flags =
                    new HashSet<>(Arrays.asList(line.substring(colon + 1).trim().split("\\s+")));
            if (!flags.containsAll(required)
                    || !(flags.contains("abm") || flags.contains("lzcnt"))) {
                throw new IOException("Native runtime requires the x86-64-v3 CPU feature set");
            }
        }
        if (!found) {
            throw new IOException("Unable to verify native CPU features");
        }
    }

    static String text(JsonNode value, String name) throws IOException {
        JsonNode field = value.get(name);
        if (field == null || !field.isTextual() || field.textValue().isEmpty()) {
            throw new IOException("Missing manifest field: " + name);
        }
        return field.textValue();
    }

    static Map<String, String> files(JsonNode manifest) throws IOException {
        JsonNode files = manifest.get("files");
        if (files == null || !files.isObject() || files.isEmpty() || files.size() > 64) {
            throw new IOException("Invalid native file set");
        }
        Map<String, String> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> iterator = files.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            if (!entry.getKey().matches("[A-Za-z0-9_-]+\\.so(?:\\.[0-9]+)*")
                    || !entry.getValue().isTextual()) {
                throw new IOException("Unsafe native file entry");
            }
            NativeJson.unhex(entry.getValue().textValue());
            values.put(entry.getKey(), entry.getValue().textValue());
        }
        return values;
    }

    static byte[] readBounded(InputStream input, int maximum) throws IOException {
        byte[] bytes = input.readNBytes(maximum + 1);
        if (bytes.length > maximum) {
            throw new IOException("Native metadata exceeds its limit");
        }
        return bytes;
    }

    static int compareVersions(String actual, String minimum) throws IOException {
        if (actual == null
                || minimum == null
                || !actual.matches("[0-9]+\\.[0-9]+")
                || !minimum.matches("[0-9]+\\.[0-9]+")) {
            throw new IOException("Invalid glibc version");
        }
        String[] a = actual.split("\\."), b = minimum.split("\\.");
        try {
            int major = Integer.compare(Integer.parseInt(a[0]), Integer.parseInt(b[0]));
            return major != 0
                    ? major
                    : Integer.compare(Integer.parseInt(a[1]), Integer.parseInt(b[1]));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid glibc version", e);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
