package org.evochora.datapipeline.api.resources.storage;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IResource;

import java.io.IOException;
import java.util.List;

/**
 * Read-only interface for storage resources that support batch query and read operations
 * with automatic folder discovery and manifest-based querying.
 * <p>
 * This interface provides high-level batch read operations built on top of key-based
 * storage primitives. It handles:
 * <ul>
 *   <li>Hierarchical folder discovery based on tick ranges</li>
 *   <li>Manifest-based querying (avoids expensive directory listing)</li>
 *   <li>Automatic decompression based on file extension</li>
 *   <li>In-memory caching (if enabled) for repeated queries</li>
 * </ul>
 * <p>
 * Storage configuration (folder structure, compression, caching) is transparent to callers.
 * Services only need to know about batch read operations, not the underlying organization.
 * <p>
 * <strong>Thread Safety:</strong> All methods are thread-safe. Multiple services can read
 * concurrently without coordination.
 * <p>
 * <strong>Usage Pattern:</strong> This interface is injected into services via usage type
 * "storage-read:resourceName" to ensure type safety and proper metric isolation.
 */
public interface IBatchStorageRead extends IResource {

    /**
     * Queries for all batches that overlap the specified tick range.
     * <p>
     * This method:
     * <ul>
     *   <li>Reads storage metadata to understand folder structure</li>
     *   <li>Scans only folders that might contain relevant batches</li>
     *   <li>Reads manifests to find overlapping batches without listing files</li>
     *   <li>Uses in-memory cache (if enabled) to avoid redundant manifest reads</li>
     * </ul>
     * <p>
     * A batch overlaps the query range if: batch.firstTick <= endTick AND batch.lastTick >= startTick
     * <p>
     * <strong>Performance:</strong> For typical indexer queries (1K-10K ticks), this scans 1-2 folders
     * and returns results in ~5-10ms (local) or ~50-60ms (S3, first query). Cached queries: &lt;5ms.
     * <p>
     * <strong>Example usage (IndexerService):</strong>
     * <pre>
     * List&lt;BatchMetadata&gt; batches = storage.queryBatches(1_000_000, 1_010_000);
     * for (BatchMetadata meta : batches) {
     *     if (!database.isBatchIndexed(meta.filename, indexerType)) {
     *         List&lt;TickData&gt; data = storage.readBatch(meta.filename);
     *         indexTicks(data);
     *         database.markBatchIndexed(meta.filename, indexerType);
     *     }
     * }
     * </pre>
     *
     * @param startTick The inclusive start of the query range
     * @param endTick The inclusive end of the query range
     * @return List of batch metadata for batches overlapping [startTick, endTick], may be empty
     * @throws IOException If reading manifests fails
     * @throws IllegalArgumentException If startTick > endTick
     */
    List<BatchMetadata> queryBatches(long startTick, long endTick) throws IOException;

    /**
     * Reads a batch file by its filename (as returned by writeBatch or queryBatches).
     * <p>
     * This method:
     * <ul>
     *   <li>Decompresses the file automatically based on extension</li>
     *   <li>Parses length-delimited protobuf messages</li>
     *   <li>Returns all ticks in the batch in original order</li>
     * </ul>
     * <p>
     * <strong>Example usage (IndexerService):</strong>
     * <pre>
     * String filename = "001/234/batch_0012340000_0012340999.pb.zst";
     * List&lt;TickData&gt; ticks = storage.readBatch(filename);
     * log.info("Read {} ticks from {}", ticks.size(), filename);
     * </pre>
     *
     * @param filename The relative filename (e.g., "001/234/batch_0012340000_0012340999.pb.zst")
     * @return List of all tick data in the batch
     * @throws IOException If file doesn't exist or read fails
     * @throws IllegalArgumentException If filename is null or empty
     */
    List<TickData> readBatch(String filename) throws IOException;

    /**
     * Reads a single protobuf message from storage at the specified key.
     * <p>
     * This method is designed for reading non-batch data like metadata, configurations,
     * or checkpoint files. It supports files with or without compression extensions.
     * <p>
     * The message is:
     * <ul>
     *   <li>Decompressed automatically based on file extension</li>
     *   <li>Parsed as a single length-delimited protobuf message</li>
     *   <li>Expected to contain exactly one message (error if file is empty or has multiple messages)</li>
     * </ul>
     * <p>
     * <strong>Example usage (Analysis Service):</strong>
     * <pre>
     * String key = simulationRunId + "/metadata.pb";
     * SimulationMetadata metadata = storage.readMessage(key, SimulationMetadata.parser());
     * log.info("Read metadata for simulation {}", metadata.getSimulationRunId());
     * </pre>
     *
     * @param key The storage key (relative path, e.g., "sim-123/metadata.pb")
     * @param parser The protobuf parser for the message type
     * @param <T> The protobuf message type
     * @return The parsed message
     * @throws IOException If file doesn't exist, is empty, contains multiple messages, or read fails
     * @throws IllegalArgumentException If key or parser is null/empty
     */
    <T extends MessageLite> T readMessage(String key, Parser<T> parser) throws IOException;
}
