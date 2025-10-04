package org.evochora.datapipeline.api.resources.storage;

import com.google.protobuf.MessageLite;
import java.io.IOException;
import java.util.Iterator;

/**
 * Interface for streaming multiple Protobuf messages from storage.
 * <p>
 * MessageReader instances are obtained from {@link IStorageReadResource#openReader(String, com.google.protobuf.Parser)}
 * and must be closed after use (use try-with-resources). Messages are read lazily from
 * length-prefixed format, providing O(1) memory usage regardless of file size.
 * <p>
 * <strong>Thread Safety:</strong> NOT thread-safe. Each reader must be used by a single thread.
 * However, multiple readers can read the same key concurrently from different threads.
 * <p>
 * <strong>Usage Pattern:</strong>
 * <pre>
 * try (MessageReader&lt;TickData&gt; reader = storage.openReader("key", TickData.parser())) {
 *     while (reader.hasNext()) {
 *         TickData tick = reader.next();
 *         processTickForIndex(tick);
 *     }
 * }
 * </pre>
 *
 * @param <T> The Protobuf message type being read
 */
public interface MessageReader<T extends MessageLite> extends AutoCloseable, Iterator<T> {

    /**
     * Checks if more messages are available to read.
     * <p>
     * This method does not parse the next message - it only checks if the end
     * of file has been reached. Parsing happens lazily in next().
     *
     * @return true if more messages available, false at end of file
     */
    @Override
    boolean hasNext();

    /**
     * Reads and parses the next Protobuf message from storage.
     * <p>
     * Messages are parsed lazily on-demand, providing O(1) memory usage. This
     * method reads the length prefix, then parses exactly that many bytes into
     * the message object.
     * <p>
     * <strong>Performance:</strong> Parsing time depends on message size, typically
     * 100-1000 nanoseconds for TickData messages.
     *
     * @return The next deserialized message
     * @throws java.util.NoSuchElementException If no more messages available (check hasNext() first)
     * @throws RuntimeException wrapping IOException If parsing fails (corrupt data)
     */
    @Override
    T next();

    /**
     * Closes the reader and releases file handle.
     * <p>
     * After close(), hasNext() and next() throw IllegalStateException.
     * <p>
     * <strong>Idempotent:</strong> Calling close() multiple times is safe (subsequent
     * calls are no-ops).
     *
     * @throws IOException If closing the underlying stream fails
     */
    @Override
    void close() throws IOException;
}