package org.evochora.datapipeline.api.resources.storage;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IResource;

import java.io.IOException;
import java.time.Instant;
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
 */
public interface IBatchStorageRead extends IResource {

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
     * Reads a batch file by its physical storage path.
     * <p>
     * This method:
     * <ul>
     *   <li>Decompresses the file automatically based on path extension</li>
     *   <li>Parses length-delimited protobuf messages</li>
     *   <li>Returns all ticks in the batch in original order</li>
     * </ul>
     * <p>
     * <strong>Example usage (IndexerService):</strong>
     * <pre>
     * StoragePath path = StoragePath.of(batchInfo.getStoragePath());
     * List&lt;TickData&gt; ticks = storage.readBatch(path);
     * log.info("Read {} ticks from {}", ticks.size(), path);
     * </pre>
     *
     * @param path The physical storage path (includes compression extension)
     * @return List of all tick data in the batch
     * @throws IOException If file doesn't exist or read fails
     * @throws IllegalArgumentException If path is null
     * @deprecated Use {@link #readChunkBatch(StoragePath)} instead. Will be removed after delta compression migration.
     */
    @Deprecated(forRemoval = true)
    List<TickData> readBatch(StoragePath path) throws IOException;

    /**
     * Reads a chunk batch file by its physical storage path.
     * <p>
     * This is the delta compression variant of {@link #readBatch(StoragePath)}.
     * Each chunk is a self-contained unit containing a snapshot and deltas.
     * <p>
     * This method:
     * <ul>
     *   <li>Decompresses the file automatically based on path extension</li>
     *   <li>Parses length-delimited protobuf messages</li>
     *   <li>Returns all chunks in the batch in original order</li>
     * </ul>
     * <p>
     * <strong>Example usage (IndexerService with delta compression):</strong>
     * <pre>
     * StoragePath path = StoragePath.of(batchInfo.getStoragePath());
     * List&lt;TickDataChunk&gt; chunks = storage.readChunkBatch(path);
     * log.info("Read {} chunks from {}", chunks.size(), path);
     * </pre>
     *
     * @param path The physical storage path (includes compression extension)
     * @return List of all tick data chunks in the batch
     * @throws IOException If file doesn't exist or read fails
     * @throws IllegalArgumentException If path is null
     */
    List<TickDataChunk> readChunkBatch(StoragePath path) throws IOException;

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
     * Lists batch files with pagination support (S3-compatible).
     * <p>
     * This method returns batch files matching the prefix, with support for iterating through
     * large result sets without loading all filenames into memory. Results are sorted
     * lexicographically by filename, which gives tick-order naturally.
     * <p>
     * Only files matching the pattern "batch_*" are returned. The search is recursive through
     * the hierarchical folder structure.
     * <p>
     * <strong>Example usage (DummyReaderService discovering files):</strong>
     * <pre>
     * String prefix = "sim123/";
     * String token = null;
     * do {
     *     BatchFileListResult result = storage.listBatchFiles(prefix, token, 1000);
     *     for (StoragePath path : result.getFilenames()) {
     *         if (!processedFiles.contains(path)) {
     *             List&lt;TickData&gt; ticks = storage.readBatch(path);
     *             // Process ticks...
     *             processedFiles.add(path);
     *         }
     *     }
     *     token = result.getNextContinuationToken();
     * } while (result.isTruncated());
     * </pre>
     *
     * @param prefix Filter prefix (e.g., "sim123/" for specific simulation, "" for all)
     * @param continuationToken Token from previous call, or null for first page
     * @param maxResults Maximum files to return per page (must be > 0, typical: 1000)
     * @return Paginated result with filenames and continuation token
     * @throws IOException If storage access fails
     * @throws IllegalArgumentException If prefix is null or maxResults <= 0
     */
    BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults) throws IOException;

    /**
     * Lists batch files starting from a specific tick with pagination support.
     * <p>
     * Returns batch files where the batch start tick is greater than or equal to {@code startTick}.
     * Results are sorted by start tick (ascending), enabling sequential processing.
     * <p>
     * This method is optimized for both filesystem and S3:
     * <ul>
     *   <li>Filesystem: Efficient directory traversal with early termination after maxResults</li>
     *   <li>S3: Pagination with server-side filtering by filename pattern</li>
     * </ul>
     * <p>
     * <strong>Example usage (EnvironmentIndexer discovering new batches):</strong>
     * <pre>
     * long lastProcessedTick = 5000;
     * String token = null;
     * BatchFileListResult result = storage.listBatchFiles(
     *     "sim123/",
     *     token,
     *     100,  // Process up to 100 batches per iteration
     *     lastProcessedTick + samplingInterval  // Start from next expected tick
     * );
     * for (String filename : result.getFilenames()) {
     *     // Process batch...
     * }
     * </pre>
     *
     * @param prefix Filter prefix (e.g., "sim123/" for specific simulation)
     * @param continuationToken Token from previous call, or null for first page
     * @param maxResults Maximum files to return per page (must be > 0)
     * @param startTick Minimum start tick (inclusive) - batches with startTick >= this value
     * @return Paginated result with matching batch filenames, sorted by start tick
     * @throws IOException If storage access fails
     * @throws IllegalArgumentException If prefix is null, startTick < 0, or maxResults <= 0
     */
    BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults, long startTick) throws IOException;

    /**
     * Lists batch files within a tick range with pagination support.
     * <p>
     * Returns batch files where the batch start tick is greater than or equal to {@code startTick}
     * and less than or equal to {@code endTick}. Results are sorted by start tick (ascending).
     * <p>
     * This method is optimized for both filesystem and S3:
     * <ul>
     *   <li>Filesystem: Efficient filtering with early termination when range exceeded</li>
     *   <li>S3: Pagination with server-side filtering by filename pattern</li>
     * </ul>
     * <p>
     * <strong>Example usage (analyze specific tick range):</strong>
     * <pre>
     * BatchFileListResult result = storage.listBatchFiles(
     *     "sim123/",
     *     null,   // continuationToken
     *     100,    // maxResults
     *     1000,   // startTick
     *     5000    // endTick
     * );
     * // Process batches in range [1000, 5000]
     * </pre>
     *
     * @param prefix Filter prefix (e.g., "sim123/" for specific simulation)
     * @param continuationToken Token from previous call, or null for first page
     * @param maxResults Maximum files to return per page (must be > 0)
     * @param startTick Minimum start tick (inclusive)
     * @param endTick Maximum start tick (inclusive)
     * @return Paginated result with matching batch filenames, sorted by start tick
     * @throws IOException If storage access fails
     * @throws IllegalArgumentException If prefix is null, ticks invalid, or maxResults <= 0
     */
    BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults, long startTick, long endTick) throws IOException;
}
