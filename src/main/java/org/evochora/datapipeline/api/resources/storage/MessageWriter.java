package org.evochora.datapipeline.api.resources.storage;

import com.google.protobuf.MessageLite;
import java.io.IOException;

/**
 * Interface for streaming multiple Protobuf messages to storage.
 * <p>
 * MessageWriter instances are obtained from {@link IStorageWriteResource#openWriter(String)}
 * and must be closed after use (use try-with-resources). Messages are written in
 * length-prefixed format (Protobuf's writeDelimitedTo) for efficient streaming reads.
 * <p>
 * <strong>Atomicity:</strong> Files appear in storage only when close() succeeds.
 * If close() is not called (e.g., exception during writing, JVM crash), the target
 * key does not appear in storage (only temporary file remains).
 * <p>
 * <strong>Thread Safety:</strong> NOT thread-safe. Each writer must be used by a single thread.
 * <p>
 * <strong>Usage Pattern:</strong>
 * <pre>
 * try (MessageWriter writer = storage.openWriter("key")) {
 *     for (MessageLite msg : batch) {
 *         writer.writeMessage(msg);
 *     }
 * }  // close() called automatically - atomic commit
 * </pre>
 */
public interface MessageWriter extends AutoCloseable {

    /**
     * Writes a Protobuf message to storage in length-prefixed format.
     * <p>
     * This method streams the message directly to storage without intermediate
     * buffering. Messages are written sequentially and can be read back in the
     * same order using MessageReader.
     * <p>
     * <strong>Performance:</strong> This operation has minimal overhead (~40ns for
     * metrics tracking + actual I/O time). The message is serialized directly to
     * the output stream.
     *
     * @param message The Protobuf message to write (must not be null)
     * @throws IOException If writing fails
     * @throws IllegalArgumentException If message is null
     */
    void writeMessage(MessageLite message) throws IOException;

    /**
     * Closes the writer and atomically commits the file to storage.
     * <p>
     * This method performs the following operations:
     * <ol>
     *   <li>Flushes all buffered data to disk</li>
     *   <li>Performs fsync to ensure durability</li>
     *   <li>Atomically renames temporary file to final key (POSIX rename)</li>
     * </ol>
     * <p>
     * After successful close(), the file is visible to all readers. If close()
     * throws an exception, the file does NOT appear in storage.
     * <p>
     * <strong>Idempotent:</strong> Calling close() multiple times is safe (subsequent
     * calls are no-ops).
     *
     * @throws IOException If commit fails (file does not appear in storage)
     */
    @Override
    void close() throws IOException;
}