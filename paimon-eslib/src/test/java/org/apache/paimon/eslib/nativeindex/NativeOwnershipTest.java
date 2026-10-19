/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.nativeindex;

import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ABI error fixtures test the Java ownership boundary, never C++ search correctness. */
class NativeOwnershipTest {
    @Test
    void incompleteQueryAllocationIsFreedExactlyOnce() throws Exception {
        ElasticppLibrary.QueryResult fixture = new ElasticppLibrary.QueryResult();
        fixture.status.abiVersion = 1;
        fixture.status.structSize = fixture.status.size();
        fixture.status.success = 1;
        fixture.complete = 1;
        fixture.totalHits = 1;
        fixture.hitCount = 0;
        fixture.write();
        AtomicInteger released = new AtomicInteger();
        ElasticppLibrary api =
                proxy(
                        (name, args) -> {
                            if (name.equals("queryArchivedShardV1")) {
                                return fixture.getPointer();
                            }
                            if (name.equals("freePaimonEsArchivedQueryResultV1")) {
                                assertEquals(fixture.getPointer(), args[0]);
                                released.incrementAndGet();
                                return null;
                            }
                            throw new AssertionError(name);
                        });
        NativeClient client = new NativeClient(api, "a".repeat(40), "b".repeat(64), 1024);
        assertThrows(
                IOException.class,
                () ->
                        client.query(
                                1,
                                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                1,
                                false));
        assertEquals(1, released.get());
    }

    @Test
    void typedNativeFailureIsPreservedAndFreed() throws Exception {
        ElasticppLibrary.QueryResult fixture = new ElasticppLibrary.QueryResult();
        fixture.status.abiVersion = 1;
        fixture.status.structSize = fixture.status.size();
        fixture.status.errorCode = 6001;
        fixture.status.rebuildRequired = 1;
        fixture.write();
        AtomicInteger released = new AtomicInteger();
        ElasticppLibrary api =
                proxy(
                        (name, args) -> {
                            if (name.equals("queryArchivedShardV1")) {
                                return fixture.getPointer();
                            }
                            if (name.equals("freePaimonEsArchivedQueryResultV1")) {
                                released.incrementAndGet();
                                return null;
                            }
                            throw new AssertionError(name);
                        });
        NativeException failure =
                assertThrows(
                        NativeException.class,
                        () ->
                                new NativeClient(api, "a".repeat(40), "b".repeat(64), 1024)
                                        .query(
                                                1,
                                                "{}"
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8),
                                                1,
                                                false));
        assertEquals(6001, failure.code());
        assertTrue(failure.rebuildRequired());
        assertEquals(1, released.get());
    }

    @Test
    void invalidResultLayoutIsFreedWithoutFollowingItsHitsPointer() throws Exception {
        ElasticppLibrary.QueryResult fixture = new ElasticppLibrary.QueryResult();
        fixture.status.abiVersion = 99;
        fixture.status.structSize = fixture.status.size();
        fixture.hits = new Pointer(1);
        fixture.hitCount = 1;
        fixture.write();
        AtomicInteger released = new AtomicInteger();
        ElasticppLibrary api =
                proxy(
                        (name, args) -> {
                            if (name.equals("queryArchivedShardV1")) {
                                return fixture.getPointer();
                            }
                            if (name.equals("freePaimonEsArchivedQueryResultV1")) {
                                released.incrementAndGet();
                                return null;
                            }
                            throw new AssertionError(name);
                        });
        assertThrows(
                IOException.class,
                () ->
                        new NativeClient(api, "a".repeat(40), "b".repeat(64), 1024)
                                .query(
                                        1,
                                        "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                        1,
                                        false));
        assertEquals(1, released.get());
    }

    @Test
    void bulkItemFailureDoesNotBecomePartialSuccessOrRetry() throws Exception {
        ElasticppLibrary.BulkResult fixture = new ElasticppLibrary.BulkResult();
        fixture.abiVersion = 1;
        fixture.structSize = fixture.size();
        fixture.summary.abiVersion = 1;
        fixture.summary.structSize = fixture.summary.size();
        fixture.summary.errorCode = 29;
        fixture.summary.flags = 16;
        fixture.write();
        AtomicInteger released = new AtomicInteger(), called = new AtomicInteger();
        ElasticppLibrary api =
                proxy(
                        (name, args) -> {
                            if (name.equals("bulkShardDocumentsU64V1")) {
                                called.incrementAndGet();
                                return fixture.getPointer();
                            }
                            if (name.equals("freeShardBulkWriteResultV1")) {
                                released.incrementAndGet();
                                return null;
                            }
                            throw new AssertionError(name);
                        });
        NativeException failure =
                assertThrows(
                        NativeException.class,
                        () ->
                                new NativeClient(api, "a".repeat(40), "b".repeat(64), 1024)
                                        .bulk(
                                                "eslib-fixture",
                                                "bulk-1",
                                                0,
                                                Collections.singletonList(
                                                        "{}"
                                                                .getBytes(
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .UTF_8))));
        assertTrue(failure.mutationStarted());
        assertEquals(1, called.get());
        assertEquals(1, released.get());
    }

    private interface Call {
        Object invoke(String name, Object[] args);
    }

    private static ElasticppLibrary proxy(Call call) {
        return (ElasticppLibrary)
                Proxy.newProxyInstance(
                        ElasticppLibrary.class.getClassLoader(),
                        new Class<?>[] {ElasticppLibrary.class},
                        (object, method, args) -> call.invoke(method.getName(), args));
    }
}
