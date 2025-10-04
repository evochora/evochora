package org.evochora.datapipeline.api.resources.storage;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import org.evochora.datapipeline.api.resources.IResource;
import java.io.IOException;
import java.util.List;

/**
 * Interface for storage resources that support read and list operations.
 * <p>
 * This interface provides both single-message reads (for metadata) and streaming
 * multi-message reads (for batches). Read operations work with Protobuf message
 * objects, with serialization handled transparently by the storage layer.
 * <p>
 * All read operations support concurrent access - multiple readers can read the
 * same key simultaneously without coordination.
 * <p>
 * Keys use hierarchical structure with '/' separators (e.g., "sim_123/metadata.pb").
 */
public interface IStorageReadResource extends IResource {

    /**
     * Reads a single Protobuf message from storage.
     * <p>
     * This method is designed for reading metadata files or other single-message
     * content. For files containing multiple length-prefixed messages, use
     * {@link #openReader(String, Parser)} instead.
     * <p>
     * <strong>Thread Safety:</strong> Multiple threads can read the same key concurrently.
     * <p>
     * <strong>Example usage:</strong>
     * <pre>
     * SimulationMetadata meta = storage.readMessage(
     *     "sim_123/metadata.pb",
     *     SimulationMetadata.parser()
     * );
     * </pre>
     *
     * @param <T> The Protobuf message type
     * @param key The storage key to read
     * @param parser The Protobuf parser for deserializing (e.g., TickData.parser())
     * @return The deserialized message
     * @throws IOException If the key does not exist or reading fails
     * @throws IllegalArgumentException If key or parser is null
     */
    <T extends MessageLite> T readMessage(String key, Parser<T> parser) throws IOException;

    /**
     * Opens a reader for streaming multiple Protobuf messages from storage.
     * <p>
     * This method is designed for reading batch files containing multiple messages
     * in length-prefixed format (written with writeDelimitedTo()). Messages are
     * parsed lazily on-demand, providing O(1) memory usage regardless of file size.
     * <p>
     * <strong>Thread Safety:</strong> MessageReader instances are NOT thread-safe.
     * Each reader must be used by a single thread. However, multiple readers can
     * read the same key concurrently from different threads.
     * <p>
     * <strong>Example usage:</strong>
     * <pre>
     * try (MessageReader&lt;TickData&gt; reader = storage.openReader(
     *         "sim_123/batch_0_999.pb",
     *         TickData.parser())) {
     *     while (reader.hasNext()) {
     *         TickData tick = reader.next();
     *         processTickForIndex(tick);
     *     }
     * }
     * </pre>
     *
     * @param <T> The Protobuf message type
     * @param key The storage key to read
     * @param parser The Protobuf parser for deserializing
     * @return A MessageReader for streaming messages from storage
     * @throws IOException If the key does not exist or opening fails
     * @throws IllegalArgumentException If key or parser is null
     */
    <T extends MessageLite> MessageReader<T> openReader(String key, Parser<T> parser) throws IOException;

    /**
     * Checks if a key exists in storage.
     * <p>
     * This is a lightweight operation that does not read the file contents.
     *
     * @param key The storage key to check
     * @return true if the key exists, false otherwise
     * @throws IllegalArgumentException If key is null
     */
    boolean exists(String key);

    /**
     * Lists all keys in storage that start with the given prefix.
     * <p>
     * The prefix is treated as a string prefix, not a directory boundary. For example:
     * <ul>
     *   <li>Prefix "sim_123/" matches "sim_123/metadata.pb", "sim_123/batch_0_999.pb"</li>
     *   <li>Prefix "sim_1" matches "sim_1/...", "sim_10/...", "sim_123/..." (all starting with "sim_1")</li>
     * </ul>
     * <p>
     * The returned list is in <strong>unspecified order</strong>. Callers requiring
     * sorted order must sort the result themselves (e.g., Collections.sort()).
     * <p>
     * For batch files with zero-padded tick numbers (e.g., "batch_0000000000000000000_..."),
     * lexicographic sort produces chronological order.
     * <p>
     * <strong>Example usage:</strong>
     * <pre>
     * List&lt;String&gt; batches = storage.listKeys("sim_123/batch_");
     * Collections.sort(batches);  // Chronological order due to zero-padding
     * for (String batchFile : batches) {
     *     if (!isAlreadyIndexed(batchFile)) {
     *         processBatch(batchFile);
     *     }
     * }
     * </pre>
     *
     * @param prefix The prefix to match (empty string matches all keys)
     * @return List of keys starting with prefix (may be empty, never null)
     * @throws IllegalArgumentException If prefix is null
     */
    List<String> listKeys(String prefix);
}