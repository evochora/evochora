package org.evochora.datapipeline.api.resources.storage;

import com.google.protobuf.MessageLite;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IResource;

import java.io.IOException;
import java.util.Iterator;

/**
 * Write-only interface for storage resources that support batch write operations
 * with automatic folder organization.
 * <p>
 * This interface provides high-level batch write operations built on top of key-based
 * storage primitives. It handles:
 * <ul>
 *   <li>Hierarchical folder organization based on tick ranges</li>
 *   <li>Atomic batch writes with compression</li>
 *   <li>Single-message writes for metadata and configuration files</li>
 * </ul>
 * <p>
 * Storage configuration (folder structure, compression) is transparent to callers.
 * Services only need to know about batch write operations, not the underlying organization.
 * <p>
 * <strong>Thread Safety:</strong> All write methods are thread-safe. Multiple services can write
 * concurrently as competing consumers without coordination.
 * <p>
 * <strong>Usage Pattern:</strong> This interface is injected into services via usage type
 * "storage-write:resourceName" to ensure type safety and proper metric isolation.
 */
public interface IBatchStorageWrite extends IResource {

    /**
     * Writes a single protobuf message to storage at the specified key.
     * <p>
     * This method is designed for non-batch data like metadata, configurations, or
     * checkpoint files that need to be part of the simulation run but aren't tick data.
     * <p>
     * The message is:
     * <ul>
     *   <li>Written as a length-delimited protobuf message</li>
     *   <li>Compressed according to storage configuration</li>
     *   <li>Stored at the exact key path provided (e.g., "{simulationRunId}/raw/metadata.pb")</li>
     *   <li>Atomically committed (temp file → final file)</li>
     * </ul>
     * <p>
     * The returned {@link StoragePath} represents the physical path including compression
     * extension. This path can be passed directly to {@link IBatchStorageRead#readMessage(StoragePath, com.google.protobuf.Parser)}
     * for reading.
     * <p>
     * <strong>Example usage (MetadataPersistenceService):</strong>
     * <pre>
     * SimulationMetadata metadata = buildMetadata();
     * String key = simulationRunId + "/raw/metadata.pb";
     * StoragePath path = storage.writeMessage(key, metadata);
     * log.info("Wrote metadata to {}", path);
     * </pre>
     *
     * @param key The storage key (relative path without compression extension, e.g., "sim-123/raw/metadata.pb")
     * @param message The protobuf message to write
     * @param <T> The protobuf message type
     * @return The physical storage path where message was written (includes compression extension)
     * @throws IOException If write fails
     * @throws IllegalArgumentException If key is null/empty or message is null
     */
    <T extends MessageLite> StoragePath writeMessage(String key, T message) throws IOException;

    /**
     * Writes chunks from an iterator to storage, streaming one chunk at a time.
     * <p>
     * Chunks are not required to be in memory simultaneously. The tick range and folder path
     * are derived from the chunks during iteration:
     * <ol>
     *   <li>First chunk provides {@code firstTick} and {@code simulationRunId}</li>
     *   <li>Chunks are streamed through compression to a temp file</li>
     *   <li>Last chunk provides {@code lastTick}</li>
     *   <li>Temp file is atomically renamed to final path</li>
     * </ol>
     * <p>
     * <strong>Atomicity:</strong> Data is written to a temp file during iteration.
     * The final file only appears after all chunks are written and the atomic rename succeeds.
     * On failure, the temp file is cleaned up.
     * <p>
     * <strong>Example usage (PersistenceService with streaming):</strong>
     * <pre>
     * try (StreamingBatch&lt;TickDataChunk&gt; batch = queue.receiveBatch(10, 30, SECONDS)) {
     *     StreamingWriteResult result = storage.writeChunkBatchStreaming(batch.iterator());
     *     batch.commit();
     * }
     * </pre>
     *
     * <p>
     * <strong>Thread Safety:</strong> See interface-level documentation.
     *
     * @param chunks iterator over tick data chunks (must have at least one element)
     * @return result containing storage path, tick range, chunk count, and bytes written
     * @throws IOException if the write fails (temp file is cleaned up)
     * @throws IllegalArgumentException if the iterator is null or empty
     */
    StreamingWriteResult writeChunkBatchStreaming(Iterator<TickDataChunk> chunks) throws IOException;

}
