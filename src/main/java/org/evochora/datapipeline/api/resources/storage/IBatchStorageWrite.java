package org.evochora.datapipeline.api.resources.storage;

import com.google.protobuf.MessageLite;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IResource;

import java.io.IOException;
import java.util.List;

/**
 * Write-only interface for storage resources that support batch write operations
 * with automatic folder organization and manifest management.
 * <p>
 * This interface provides high-level batch write operations built on top of key-based
 * storage primitives. It handles:
 * <ul>
 *   <li>Hierarchical folder organization based on tick ranges</li>
 *   <li>Manifest file management for efficient querying</li>
 *   <li>Atomic batch writes with automatic metadata tracking</li>
 *   <li>Compression and decompression transparently</li>
 * </ul>
 * <p>
 * Storage configuration (folder structure, compression, caching) is transparent to callers.
 * Services only need to know about batch write operations, not the underlying organization.
 * <p>
 * <strong>Thread Safety:</strong> writeBatch() is thread-safe. Multiple services can write
 * concurrently as competing consumers without coordination.
 * <p>
 * <strong>Usage Pattern:</strong> This interface is injected into services via usage type
 * "storage-write:resourceName" to ensure type safety and proper metric isolation.
 */
public interface IBatchStorageWrite extends IResource {

    /**
     * Writes a batch of tick data to storage with automatic folder organization.
     * <p>
     * The batch is:
     * <ul>
     *   <li>Compressed according to storage configuration</li>
     *   <li>Written to appropriate folder based on firstTick</li>
     *   <li>Registered in folder manifest for efficient querying</li>
     *   <li>Atomically committed (temp file → final file)</li>
     * </ul>
     * <p>
     * <strong>Example usage (PersistenceService):</strong>
     * <pre>
     * List&lt;TickData&gt; batch = queue.drainTo(maxBatchSize);
     * long firstTick = batch.get(0).getTickNumber();
     * long lastTick = batch.get(batch.size() - 1).getTickNumber();
     * String filename = storage.writeBatch(batch, firstTick, lastTick);
     * log.info("Wrote {} ticks to {}", batch.size(), filename);
     * </pre>
     *
     * @param batch The tick data to persist (must be non-empty)
     * @param firstTick The first tick number in the batch
     * @param lastTick The last tick number in the batch
     * @return The relative filename where batch was stored (e.g., "001/234/batch_0012340000_0012340999.pb.zst")
     * @throws IOException If write fails
     * @throws IllegalArgumentException If batch is empty or tick order is invalid (firstTick > lastTick)
     */
    String writeBatch(List<TickData> batch, long firstTick, long lastTick) throws IOException;

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
     *   <li>Stored at the exact key path provided (e.g., "{simulationRunId}/metadata.pb")</li>
     *   <li>Atomically committed (temp file → final file)</li>
     * </ul>
     * <p>
     * <strong>Example usage (MetadataPersistenceService):</strong>
     * <pre>
     * SimulationMetadata metadata = buildMetadata();
     * String key = simulationRunId + "/metadata.pb";
     * storage.writeMessage(key, metadata);
     * log.info("Wrote metadata to {}", key);
     * </pre>
     *
     * @param key The storage key (relative path, e.g., "sim-123/metadata.pb")
     * @param message The protobuf message to write
     * @param <T> The protobuf message type
     * @throws IOException If write fails
     * @throws IllegalArgumentException If key is null/empty or message is null
     */
    <T extends MessageLite> void writeMessage(String key, T message) throws IOException;
}
