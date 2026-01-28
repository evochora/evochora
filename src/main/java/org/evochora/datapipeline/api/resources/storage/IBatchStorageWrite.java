package org.evochora.datapipeline.api.resources.storage;

import com.google.protobuf.MessageLite;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IResource;

import java.io.IOException;
import java.util.List;

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
 * <strong>Thread Safety:</strong> writeChunkBatch() is thread-safe. Multiple services can write
 * concurrently as competing consumers without coordination.
 * <p>
 * <strong>Usage Pattern:</strong> This interface is injected into services via usage type
 * "storage-write:resourceName" to ensure type safety and proper metric isolation.
 */
public interface IBatchStorageWrite extends IResource {

    /**
     * Writes a batch of tick data chunks to storage with automatic folder organization.
     * <p>
     * Each chunk is a self-contained unit containing a snapshot and deltas.
     * <p>
     * The batch is:
     * <ul>
     *   <li>Compressed according to storage configuration</li>
     *   <li>Written to appropriate folder based on firstTick</li>
     *   <li>Atomically committed (temp file → final file)</li>
     * </ul>
     * <p>
     * The returned {@link StoragePath} represents the physical path including compression
     * extension (e.g., ".zst" for Zstandard). This path can be passed directly to
     * {@link IBatchStorageRead#readChunkBatch(StoragePath)} for reading.
     * <p>
     * <strong>Example usage (PersistenceService with delta compression):</strong>
     * <pre>
     * List&lt;TickDataChunk&gt; chunks = queue.drainTo(maxBatchSize);
     * long firstTick = chunks.get(0).getFirstTick();
     * long lastTick = chunks.get(chunks.size() - 1).getLastTick();
     * StoragePath path = storage.writeChunkBatch(chunks, firstTick, lastTick);
     * log.info("Wrote {} chunks ({} ticks) to {}", chunks.size(), totalTicks, path);
     * </pre>
     *
     * @param batch The tick data chunks to persist (must be non-empty)
     * @param firstTick The first tick number in the batch (from first chunk)
     * @param lastTick The last tick number in the batch (from last chunk)
     * @return The physical storage path where batch was written (includes compression extension)
     * @throws IOException If write fails
     * @throws IllegalArgumentException If batch is empty or tick order is invalid (firstTick > lastTick)
     */
    StoragePath writeChunkBatch(List<TickDataChunk> batch, long firstTick, long lastTick) throws IOException;

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
     * Moves a batch file to the superseded folder for crash-safe truncation.
     * <p>
     * This method is used during simulation resume to move batch files that need to be
     * superseded (e.g., files written after a crash that contain incomplete or stale data).
     * Moving rather than deleting preserves the data for potential forensic analysis.
     * <p>
     * The file is moved to a flat superseded folder structure:
     * {@code {runId}/raw/superseded/{originalFilename}}
     * <p>
     * Files in the superseded folder are excluded from {@link IBatchStorageRead#listBatchFiles}
     * results, ensuring they don't interfere with normal replay or resume operations.
     * <p>
     * <strong>Example usage (SnapshotLoader during resume):</strong>
     * <pre>
     * // Find batch files after the resume point that need truncation
     * List&lt;StoragePath&gt; filesToTruncate = findBatchesAfterTick(resumeFromTick);
     * for (StoragePath file : filesToTruncate) {
     *     storage.moveToSuperseded(file);
     *     log.info("Moved stale batch to superseded: {}", file);
     * }
     * </pre>
     *
     * @param path The physical storage path of the file to move (as returned by writeChunkBatch)
     * @throws IOException If the move fails (source not found, destination exists, etc.)
     * @throws IllegalArgumentException If path is null or doesn't appear to be a batch file
     */
    void moveToSuperseded(StoragePath path) throws IOException;
}
