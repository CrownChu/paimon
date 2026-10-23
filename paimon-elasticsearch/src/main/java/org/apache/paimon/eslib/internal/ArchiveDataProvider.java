/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 * Adapted for Paimon; see NOTICE.native-direct.txt.
 */

package org.apache.paimon.eslib.internal;

import java.io.Closeable;
import java.io.IOException;

/**
 * Interface for reading byte ranges from an archive file. Provided by Paimon's
 * GlobalIndexFileReader to ESLib's searcher.
 *
 * <p>Each instance wraps a single seekable stream. Use {@link #fork()} to obtain an independent
 * instance backed by a new stream — required for parallel reads (e.g. concurrent cluster scoring in
 * DiskBBQ).
 */
public interface ArchiveDataProvider extends Closeable {

    /**
     * Read bytes from the archive at the given offset.
     *
     * @param offset byte offset in the archive file
     * @param length number of bytes to read
     * @return the bytes read
     */
    byte[] readRange(long offset, int length) throws IOException;

    /**
     * Create an independent provider backed by a new stream on the same archive. The returned
     * instance has its own position and can be used concurrently with this instance on a different
     * thread.
     */
    ArchiveDataProvider fork() throws IOException;

    @Override
    default void close() throws IOException {}
}
