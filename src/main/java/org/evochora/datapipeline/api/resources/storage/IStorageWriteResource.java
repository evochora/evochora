package org.evochora.datapipeline.api.resources.storage;

import com.google.protobuf.MessageLite;
import org.evochora.datapipeline.api.resources.IResource;
import java.io.IOException;

/**
 * Interface for storage resources that support write operations.
 * <p>
 * This interface provides Protobuf-aware storage operations where services work with
 * message objects and the storage layer handles serialization transparently. This maintains
 * architectural consistency with queue resources where serialization is also hidden.
 * <p>
 * Write operations are atomic: files appear only after successful completion. Crashes
 * during writes leave temporary files (.tmp suffix) but never corrupt final data files.
 * <p>
 * Keys use hierarchical structure with '/' separators (e.g., "sim_123/batch_0_999.pb").
 */
public interface IStorageWriteResource extends IResource {

    /**
     * Opens a writer for streaming multiple Protobuf messages to storage.
     * <p>
     * This method is designed for batch writes where multiple messages are written
     * sequentially to the same file. Messages are written in length-prefixed format
     * using Protobuf's writeDelimitedTo() for efficient streaming reads.
     * <p>
     * <strong>Atomicity:</strong> The file appears in storage only when close() succeeds.
     * During writing, data is accumulated in a temporary file. If the writer is not
     * closed properly (e.g., JVM crash), the temporary file remains but the target
     * key does not appear in storage.
     * <p>
     * <strong>Thread Safety:</strong> MessageWriter instances are NOT thread-safe.
     * Each writer must be used by a single thread.
     * <p>
     * <strong>Example usage:</strong>
     * <pre>
     * try (MessageWriter writer = storage.openWriter("sim_123/batch_0_999.pb")) {
     *     for (TickData tick : batchData) {
     *         writer.writeMessage(tick);
     *     }
     * }  // Atomic commit on close
     * </pre>
     *
     * @param key The storage key (hierarchical path with '/' separators)
     * @return A MessageWriter for streaming messages to storage
     * @throws IOException If opening the writer fails (e.g., permission denied)
     * @throws IllegalArgumentException If key is null, empty, or contains invalid characters
     */
    MessageWriter openWriter(String key) throws IOException;
}