package org.evochora.datapipeline.api.resources.storage;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IResource;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read-only interface for storage resources that support batch read operations.
 * <p>
 * This interface provides batch read operations for tick data storage:
 * <ul>
 *   <li>Direct batch file reading by filename</li>
 *   <li>Automatic decompression based on file extension</li>
 *   <li>Single message reading for metadata/config files</li>
 * </ul>
 * <p>
 * Storage configuration (folder structure, compression) is transparent to callers.
 * <p>
 * <strong>Thread Safety:</strong> All methods are thread-safe. Multiple services can read
 * concurrently without coordination.
 * <p>
 * <strong>Usage Pattern:</strong> This interface is injected into services via usage type
 * "storage-read:resourceName" to ensure type safety and proper metric isolation.
 *
 * <h3>Implementor contract</h3>
 * Implementations must provide these 6 abstract methods:
 * <ol>
 *   <li>{@link #forEachRawChunk} — streaming raw-byte read (the primary read primitive)</li>
 *   <li>{@link #readMessage} — single protobuf message read (metadata, configs)</li>
 *   <li>{@link #listBatchFiles(String, String, int, long, long, SortOrder)} — batch file listing</li>
 *   <li>{@link #listRunIds} — run discovery</li>
 *   <li>{@link #findMetadataPath} — metadata file lookup</li>
 *   <li>{@link #findLastBatchFile} — last batch file lookup for resume</li>
 * </ol>
 * All other methods are default convenience overloads that delegate to these primitives.
 * <p>
 * {@link #forEachChunk} is a default that delegates to {@link #forEachRawChunk} with full
 * protobuf parsing. Implementations that need wire-level field filtering (e.g.,
 * {@link ChunkFieldFilter#SKIP_ORGANISMS}) must override {@code forEachChunk} as well.
 *
 * <h3>Legacy methods (will be removed once all consumers use streaming)</h3>
 * The following methods materialize all chunks into a {@code List} and exist only for
 * backward compatibility with consumers that have not yet migrated to streaming via
 * {@link #forEachRawChunk} or {@link #forEachChunk}:
 * <ul>
 *   <li>{@link #readChunkBatch(StoragePath)} — collect all chunks into a list</li>
 *   <li>{@link #readChunkBatch(StoragePath, ChunkFieldFilter)} — collect with wire-level filtering</li>
 *   <li>{@link #readLastSnapshot(StoragePath)} — read last snapshot via full materialization</li>
 * </ul>
 * New consumers should use {@link #forEachRawChunk} or {@link #forEachChunk} directly
 * to avoid O(n) heap allocation.
 */
public interface IBatchStorageRead extends IResource {

    /**
     * Specifies the sort order for batch file listing operations.
     * <p>
     * Batch files are named with zero-padded tick numbers (e.g., {@code batch_0000001000_0000001099.pb}),
     * so lexicographic sorting equals tick order.
     */
    enum SortOrder {
        /**
         * Sort batch files by tick number in ascending order (oldest first).
         * This is the default behavior for all existing {@code listBatchFiles} methods.
         */
        ASCENDING,

        /**
         * Sort batch files by tick number in descending order (newest first).
         * Use this when only the most recent files are needed, such as finding
         * the last checkpoint for resume.
         */
        DESCENDING
    }

    /**
     * Lists simulation run IDs in storage that started after the given timestamp.
     * <p>
     * Used by indexers for run discovery in parallel mode.
     * <p>
     * Implementation notes:
     * <ul>
     *   <li>Returns empty list if no matching runs</li>
     *   <li>Never blocks - returns immediately</li>
     *   <li>Run timestamp can be determined from:
     *     <ul>
     *       <li>Parsing runId format (YYYYMMDDHHmmssSS-UUID)</li>
     *       <li>Directory creation time (filesystem)</li>
     *       <li>Object metadata (S3/Azure)</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @param afterTimestamp Only return runs that started after this time
     * @return List of simulation run IDs, sorted by timestamp (oldest first)
     * @throws IOException if storage access fails
     */
    List<String> listRunIds(Instant afterTimestamp) throws IOException;

    /**
     * Reads a chunk batch file by its physical storage path, returning all chunks as a list.
     * <p>
     * <strong>Legacy:</strong> This method materializes all chunks into memory. New consumers
     * should use {@link #forEachChunk} to process chunks one at a time without O(n) heap.
     * Will be removed once all consumers have migrated to streaming.
     * <p>
     * <strong>Default implementation:</strong> Collects all chunks from {@link #forEachChunk}
     * into a list.
     *
     * @param path The physical storage path (includes compression extension)
     * @return List of all tick data chunks in the batch
     * @throws IOException If file doesn't exist or read fails
     * @throws IllegalArgumentException If path is null
     */
    default List<TickDataChunk> readChunkBatch(StoragePath path) throws IOException {
        List<TickDataChunk> result = new ArrayList<>();
        try {
            forEachChunk(path, ChunkFieldFilter.ALL, result::add);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to read chunk batch: " + path, e);
        }
        return result;
    }

    /**
     * Reads the snapshot from the last chunk in a batch file.
     * <p>
     * <strong>Legacy:</strong> This default implementation streams all chunks via
     * {@link #forEachChunk} and returns the last snapshot. Will be removed once all
     * consumers have migrated to streaming. Resume operations should use
     * {@link #forEachChunk} with a snapshot-only filter instead.
     *
     * @param path The physical storage path (includes compression extension)
     * @return The snapshot TickData from the last chunk in the batch
     * @throws IOException              If file doesn't exist, is empty, or read fails
     * @throws IllegalArgumentException If path is null
     */
    default TickData readLastSnapshot(StoragePath path) throws IOException {
        TickData[] last = new TickData[1];
        try {
            forEachChunk(path, ChunkFieldFilter.ALL, chunk -> last[0] = chunk.getSnapshot());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to read last snapshot: " + path, e);
        }
        if (last[0] == null) {
            throw new IOException("Empty batch file: " + path);
        }
        return last[0];
    }

    /**
     * Reads a chunk batch from storage, skipping heavy protobuf fields at the wire level.
     * <p>
     * <strong>Legacy:</strong> This method materializes all filtered chunks into a list.
     * New consumers should use {@link #forEachChunk} directly to apply wire-level filtering
     * without O(n) heap allocation. Will be removed once all consumers have migrated to streaming.
     * <p>
     * <strong>Default implementation:</strong> Collects chunks from {@code forEachChunk} into a list.
     *
     * @param path   The physical storage path (includes compression extension)
     * @param filter Controls which fields to skip during parsing
     * @return List of tick data chunks with filtered fields omitted
     * @throws IOException              If file doesn't exist or read fails
     * @throws IllegalArgumentException If path or filter is null
     */
    default List<TickDataChunk> readChunkBatch(StoragePath path, ChunkFieldFilter filter) throws IOException {
        List<TickDataChunk> result = new ArrayList<>();
        try {
            forEachChunk(path, filter, result::add);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to read chunk batch with filter " + filter + ": " + path, e);
        }
        return result;
    }

    /**
     * Reads a single protobuf message from storage at the specified physical path.
     * <p>
     * This method is designed for reading non-batch data like metadata, configurations,
     * or checkpoint files.
     * <p>
     * The message is:
     * <ul>
     *   <li>Decompressed automatically based on path extension</li>
     *   <li>Parsed as a single length-delimited protobuf message</li>
     *   <li>Expected to contain exactly one message (error if file is empty or has multiple messages)</li>
     * </ul>
     * <p>
     * <strong>Example usage (Analysis Service):</strong>
     * <pre>
     * StoragePath path = StoragePath.of(metadataInfo.getStoragePath());
     * SimulationMetadata metadata = storage.readMessage(path, SimulationMetadata.parser());
     * log.info("Read metadata for simulation {}", metadata.getSimulationRunId());
     * </pre>
     *
     * @param path The physical storage path (includes compression extension)
     * @param parser The protobuf parser for the message type
     * @param <T> The protobuf message type
     * @return The parsed message
     * @throws IOException If file doesn't exist, is empty, contains multiple messages, or read fails
     * @throws IllegalArgumentException If path or parser is null
     */
    <T extends MessageLite> T readMessage(StoragePath path, Parser<T> parser) throws IOException;

    /**
     * Finds the metadata file path for a given simulation run ID.
     * <p>
     * This method searches for metadata files matching the pattern {@code {runId}/metadata.pb}
     * and returns the physical storage path, including compression extensions if present.
     * The method is compression-transparent and will find both uncompressed ({@code metadata.pb})
     * and compressed variants ({@code metadata.pb.zst}, etc.).
     * <p>
     * This method is designed for cold-path scenarios where metadata needs to be read
     * after the simulation and persistence services have finished running, without requiring
     * the metadata topic.
     * <p>
     * <strong>Example usage (Cold Path - CLI/Rendering):</strong>
     * <pre>
     * Optional&lt;StoragePath&gt; metadataPath = storage.findMetadataPath(runId);
     * if (metadataPath.isPresent()) {
     *     SimulationMetadata metadata = storage.readMessage(metadataPath.get(), SimulationMetadata.parser());
     *     // Process metadata...
     * } else {
     *     // Metadata not found for this run
     * }
     * </pre>
     * <p>
     * <strong>Storage Backend Compatibility:</strong>
     * <ul>
     *   <li>Filesystem: Uses directory traversal to find metadata files</li>
     *   <li>S3/Azure: Uses object listing with prefix matching</li>
     *   <li>All backends: Compression-transparent (finds .pb and .pb.zst variants)</li>
     * </ul>
     *
     * @param runId The simulation run ID (must not be null)
     * @return Optional containing the physical storage path if found, empty otherwise
     * @throws IOException If storage access fails
     * @throws IllegalArgumentException If runId is null
     */
    Optional<StoragePath> findMetadataPath(String runId) throws IOException;

    /**
     * Lists batch files within a tick range with pagination and configurable sort order.
     * <p>
     * This is the single primitive listing operation. All convenience overloads delegate here.
     * Returns batch files where the batch start tick falls within [{@code startTick}, {@code endTick}].
     * <p>
     * Only files matching the pattern "batch_*" are returned. The search is recursive through
     * the hierarchical folder structure.
     * <p>
     * <strong>Sentinel values for "no filter":</strong>
     * <ul>
     *   <li>{@code startTick = 0} — no lower bound (ticks are always &ge; 0)</li>
     *   <li>{@code endTick = Long.MAX_VALUE} — no upper bound</li>
     * </ul>
     *
     * @param prefix Filter prefix (e.g., "sim123/" for specific simulation, "" for all)
     * @param continuationToken Token from previous call, or null for first page
     * @param maxResults Maximum files to return per page (must be &gt; 0)
     * @param startTick Minimum start tick (inclusive), 0 for no lower bound
     * @param endTick Maximum start tick (inclusive), {@code Long.MAX_VALUE} for no upper bound
     * @param sortOrder Sort order for results ({@link SortOrder#ASCENDING} or {@link SortOrder#DESCENDING})
     * @return Paginated result with filenames and continuation token
     * @throws IOException If storage access fails
     * @throws IllegalArgumentException If prefix is null, sortOrder is null, startTick &lt; 0,
     *         endTick &lt; startTick, or maxResults &le; 0
     */
    BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults,
                                       long startTick, long endTick, SortOrder sortOrder) throws IOException;

    /**
     * Convenience overload: lists all batch files with pagination, sorted ascending.
     * <p>
     * Delegates to {@link #listBatchFiles(String, String, int, long, long, SortOrder)}
     * with no tick filtering and ascending sort order.
     *
     * @param prefix Filter prefix (e.g., "sim123/" for specific simulation, "" for all)
     * @param continuationToken Token from previous call, or null for first page
     * @param maxResults Maximum files to return per page (must be &gt; 0)
     * @return Paginated result with filenames and continuation token
     * @throws IOException If storage access fails
     * @throws IllegalArgumentException If prefix is null or maxResults &le; 0
     */
    default BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults) throws IOException {
        return listBatchFiles(prefix, continuationToken, maxResults, 0, Long.MAX_VALUE, SortOrder.ASCENDING);
    }

    /**
     * Convenience overload: lists all batch files with pagination and configurable sort order.
     * <p>
     * Delegates to {@link #listBatchFiles(String, String, int, long, long, SortOrder)}
     * with no tick filtering.
     *
     * @param prefix Filter prefix (e.g., "sim123/" for specific simulation, "" for all)
     * @param continuationToken Token from previous call, or null for first page
     * @param maxResults Maximum files to return per page (must be &gt; 0)
     * @param sortOrder Sort order for results ({@link SortOrder#ASCENDING} or {@link SortOrder#DESCENDING})
     * @return Paginated result with filenames and continuation token
     * @throws IOException If storage access fails
     * @throws IllegalArgumentException If prefix is null, sortOrder is null, or maxResults &le; 0
     */
    default BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults,
                                               SortOrder sortOrder) throws IOException {
        return listBatchFiles(prefix, continuationToken, maxResults, 0, Long.MAX_VALUE, sortOrder);
    }

    /**
     * Convenience overload: lists batch files starting from a specific tick, sorted ascending.
     * <p>
     * Delegates to {@link #listBatchFiles(String, String, int, long, long, SortOrder)}
     * with no upper tick bound and ascending sort order.
     *
     * @param prefix Filter prefix (e.g., "sim123/" for specific simulation)
     * @param continuationToken Token from previous call, or null for first page
     * @param maxResults Maximum files to return per page (must be &gt; 0)
     * @param startTick Minimum start tick (inclusive)
     * @return Paginated result with matching batch filenames, sorted by start tick
     * @throws IOException If storage access fails
     * @throws IllegalArgumentException If prefix is null, startTick &lt; 0, or maxResults &le; 0
     */
    default BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults,
                                               long startTick) throws IOException {
        return listBatchFiles(prefix, continuationToken, maxResults, startTick, Long.MAX_VALUE, SortOrder.ASCENDING);
    }

    /**
     * Convenience overload: lists batch files within a tick range, sorted ascending.
     * <p>
     * Delegates to {@link #listBatchFiles(String, String, int, long, long, SortOrder)}
     * with ascending sort order.
     *
     * @param prefix Filter prefix (e.g., "sim123/" for specific simulation)
     * @param continuationToken Token from previous call, or null for first page
     * @param maxResults Maximum files to return per page (must be &gt; 0)
     * @param startTick Minimum start tick (inclusive)
     * @param endTick Maximum start tick (inclusive)
     * @return Paginated result with matching batch filenames, sorted by start tick
     * @throws IOException If storage access fails
     * @throws IllegalArgumentException If prefix is null, startTick &lt; 0, endTick &lt; startTick,
     *         or maxResults &le; 0
     */
    default BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults,
                                               long startTick, long endTick) throws IOException {
        return listBatchFiles(prefix, continuationToken, maxResults, startTick, endTick, SortOrder.ASCENDING);
    }

    /**
     * Finds the last (most recent by tick number) batch file for a given run prefix.
     * <p>
     * This method is optimized for efficiently finding the last batch file without loading
     * all batch file names. Each storage backend can implement this optimally:
     * <ul>
     *   <li>Filesystem: Traverse folder hierarchy from highest-numbered folders</li>
     *   <li>S3: Use reverse listing or folder-based traversal</li>
     * </ul>
     * <p>
     * <strong>Primary use case:</strong> Resume operations that need to find the last
     * checkpoint to restore simulation state.
     * <p>
     * <strong>Example usage (SnapshotLoader finding last batch for resume):</strong>
     * <pre>
     * Optional&lt;StoragePath&gt; lastBatch = storage.findLastBatchFile("sim123/raw/");
     * if (lastBatch.isPresent()) {
     *     TickData snapshot = storage.readLastSnapshot(lastBatch.get());
     *     // Resume from snapshot...
     * }
     * </pre>
     *
     * @param runIdPrefix The run prefix to search (e.g., "runId/raw/")
     * @return Optional containing the path to the last batch file, or empty if no batches exist
     * @throws IOException If storage access fails
     * @throws IllegalArgumentException If runIdPrefix is null
     */
    Optional<StoragePath> findLastBatchFile(String runIdPrefix) throws IOException;

    /**
     * Streams raw chunk bytes from a batch file one at a time.
     * <p>
     * This is the primary read primitive. Each chunk's uncompressed protobuf bytes are read
     * from storage, wrapped in a {@link RawChunk} with metadata extracted via partial parse
     * (firstTick, lastTick, tickCount), and passed to the consumer. The raw bytes are discarded
     * before the next chunk is read. Peak heap usage is O(rawChunkSize) (~25 MB for 4000x3000).
     * <p>
     * The raw bytes include all protobuf fields (including organisms). Consumers that need
     * parsed objects should use {@link #forEachChunk} instead.
     *
     * @param path     The physical storage path (includes compression extension)
     * @param consumer Callback invoked once per chunk with the raw bytes and metadata
     * @throws Exception              If reading or the consumer callback fails
     * @throws IllegalArgumentException If any parameter is null
     */
    void forEachRawChunk(StoragePath path,
                         CheckedConsumer<RawChunk> consumer) throws Exception;

    /**
     * Streams parsed chunks from a batch file one at a time, with optional wire-level filtering.
     * <p>
     * <strong>Default implementation:</strong> Delegates to {@link #forEachRawChunk}, parsing
     * each chunk's raw bytes via {@code TickDataChunk.parseFrom()}. Only supports
     * {@link ChunkFieldFilter#ALL}; other filters require an implementation override with
     * wire-level protobuf filtering.
     * <p>
     * Implementations that support wire-level field filtering (e.g.,
     * {@link ChunkFieldFilter#SKIP_ORGANISMS}) must override this method.
     *
     * @param path     The physical storage path (includes compression extension)
     * @param filter   Controls which fields to skip during parsing
     * @param consumer Callback invoked once per chunk with the filtered chunk
     * @throws Exception              If reading, parsing, or the consumer callback fails
     * @throws UnsupportedOperationException If filter is not {@code ALL} and this method is not overridden
     * @throws IllegalArgumentException If any parameter is null
     */
    default void forEachChunk(StoragePath path, ChunkFieldFilter filter,
                              CheckedConsumer<TickDataChunk> consumer) throws Exception {
        if (filter != ChunkFieldFilter.ALL) {
            throw new UnsupportedOperationException(
                "ChunkFieldFilter." + filter + " requires an override of forEachChunk with wire-level filtering");
        }
        forEachRawChunk(path, rawChunk ->
            consumer.accept(TickDataChunk.parseFrom(rawChunk.data())));
    }

    /**
     * Convenience overload: streams all parsed chunks without field filtering.
     * <p>
     * Delegates to {@link #forEachChunk(StoragePath, ChunkFieldFilter, CheckedConsumer)}
     * with {@link ChunkFieldFilter#ALL}.
     *
     * @param path     The physical storage path (includes compression extension)
     * @param consumer Callback invoked once per chunk
     * @throws Exception              If reading, parsing, or the consumer callback fails
     * @throws IllegalArgumentException If any parameter is null
     */
    default void forEachChunk(StoragePath path,
                              CheckedConsumer<TickDataChunk> consumer) throws Exception {
        forEachChunk(path, ChunkFieldFilter.ALL, consumer);
    }
}
