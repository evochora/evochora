package org.evochora.datapipeline.resources.storage;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Abstract base class for batch storage resources with hierarchical folder organization.
 * <p>
 * This class implements all the high-level logic for batch storage:
 * <ul>
 *   <li>Hierarchical folder path calculation based on tick ranges</li>
 *   <li>Atomic batch file writing with compression</li>
 *   <li>Base monitoring and metrics tracking</li>
 * </ul>
 * <p>
 * Subclasses only need to implement low-level I/O primitives for their specific
 * storage backend (filesystem, S3, etc.). Monitoring is inherited, with a hook
 * method {@link #addCustomMetrics(Map)} for implementation-specific metrics.
 */
public abstract class AbstractBatchStorageResource extends AbstractResource
    implements IBatchStorageWrite, IBatchStorageRead, IMonitorable {

    private static final Logger log = LoggerFactory.getLogger(AbstractBatchStorageResource.class);

    // Configuration
    protected final List<Long> folderLevels;
    protected final ICompressionCodec codec;

    // Base metrics tracking (all storage implementations)
    protected final java.util.concurrent.atomic.AtomicLong writeOperations = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong readOperations = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong bytesWritten = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong bytesRead = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong writeErrors = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong readErrors = new java.util.concurrent.atomic.AtomicLong(0);
    protected final List<org.evochora.datapipeline.api.resources.OperationalError> errors =
        java.util.Collections.synchronizedList(new ArrayList<>());

    protected AbstractBatchStorageResource(String name, Config options) {
        super(name, options);

        // Initialize compression codec (fail-fast if environment validation fails)
        try {
            this.codec = org.evochora.datapipeline.utils.compression.CompressionCodecFactory.createAndValidate(options);
            if (!"none".equals(codec.getName())) {
                log.info("Storage '{}' using compression: codec={}, level={}",
                    name, codec.getName(), codec.getLevel());
            }
        } catch (org.evochora.datapipeline.utils.compression.CompressionException e) {
            throw new IllegalStateException("Failed to initialize compression codec for storage '" + name + "'", e);
        }

        // Parse folder structure configuration
        if (options.hasPath("folderStructure.levels")) {
            this.folderLevels = options.getLongList("folderStructure.levels")
                .stream()
                .map(Number::longValue)
                .collect(Collectors.toList());
        } else {
            // Default: [100M, 100K]
            this.folderLevels = Arrays.asList(100_000_000L, 100_000L);
        }

        if (folderLevels.isEmpty()) {
            throw new IllegalArgumentException("folderStructure.levels cannot be empty");
        }
        for (Long level : folderLevels) {
            if (level <= 0) {
                throw new IllegalArgumentException("All folder levels must be positive");
            }
        }

        log.info("AbstractBatchStorageResource '{}' initialized: folders={}",
            name, folderLevels);
    }

    @Override
    public String writeBatch(List<TickData> batch, long firstTick, long lastTick) throws IOException {
        if (batch == null || batch.isEmpty()) {
            throw new IllegalArgumentException("batch cannot be null or empty");
        }
        if (firstTick > lastTick) {
            throw new IllegalArgumentException(
                String.format("firstTick (%d) cannot be greater than lastTick (%d)", firstTick, lastTick)
            );
        }

        // Calculate folder path from firstTick
        String simulationId = batch.get(0).getSimulationRunId();
        String folderPath = simulationId + "/" + calculateFolderPath(firstTick);

        // Generate batch filename
        String filename = String.format("batch_%019d_%019d.pb", firstTick, lastTick);
        String extension = codec.getFileExtension();
        if (!extension.isEmpty()) {
            filename += extension;
        }

        String fullPath = folderPath + "/" + filename;

        // Serialize and compress batch
        byte[] data = serializeBatch(batch);
        byte[] compressedData = compressBatch(data);

        // Write atomically: temp file → final file
        // Note: Implementations should catch IOExceptions from these operations
        // and perform cleanup of tempPath if needed before rethrowing
        String tempPath = folderPath + "/.tmp-" + UUID.randomUUID() + "-" + filename;
        writeBytes(tempPath, compressedData);
        atomicMove(tempPath, fullPath);

        log.debug("Wrote batch {} with {} ticks ({} bytes compressed)",
            fullPath, batch.size(), compressedData.length);

        return fullPath;
    }

    @Override
    public List<TickData> readBatch(String filename) throws IOException {
        if (filename == null || filename.isEmpty()) {
            throw new IllegalArgumentException("filename cannot be null or empty");
        }

        // Read and decompress
        byte[] compressedData = readBytes(filename);
        byte[] data = decompressBatch(compressedData, filename);

        // Deserialize protobuf messages
        return deserializeBatch(data);
    }

    @Override
    public <T extends MessageLite> void writeMessage(String key, T message) throws IOException {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key cannot be null or empty");
        }
        if (message == null) {
            throw new IllegalArgumentException("message cannot be null");
        }

        // Serialize single delimited protobuf message
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        message.writeDelimitedTo(bos);
        byte[] data = bos.toByteArray();

        // Compress
        byte[] compressedData = compressBatch(data);

        // Add codec extension to key
        String finalKey = key + codec.getFileExtension();

        writeBytes(finalKey, compressedData);
    }

    @Override
    public <T extends MessageLite> T readMessage(String key, Parser<T> parser) throws IOException {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key cannot be null or empty");
        }
        if (parser == null) {
            throw new IllegalArgumentException("parser cannot be null");
        }

        // Read file using key as-is (caller provides full filename with extension)
        byte[] compressedData = readBytes(key);

        // Auto-detect compression from filename and decompress
        byte[] data = decompressBatch(compressedData, key);

        // Deserialize single message
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        T message = parser.parseDelimitedFrom(bis);

        if (message == null) {
            throw new IOException("File is empty: " + key);
        }

        // Verify exactly one message
        if (bis.available() > 0) {
            T secondMessage = parser.parseDelimitedFrom(bis);
            if (secondMessage != null) {
                throw new IOException("File contains multiple messages: " + key);
            }
        }

        return message;
    }

    /**
     * Calculates folder path for a given tick using configured folder levels.
     * <p>
     * Example with levels=[100000000, 100000]:
     * <ul>
     *   <li>Tick 123,456,789 → "001/234/"</li>
     *   <li>Tick 5,000,000,000 → "050/000/"</li>
     * </ul>
     */
    private String calculateFolderPath(long tick) {
        StringBuilder path = new StringBuilder();
        long remaining = tick;

        for (int i = 0; i < folderLevels.size(); i++) {
            long divisor = folderLevels.get(i);
            long bucket = remaining / divisor;

            // Format with 3 digits (supports up to 999 per level)
            path.append(String.format("%03d", bucket));

            if (i < folderLevels.size() - 1) {
                path.append("/");
            }

            remaining %= divisor;
        }

        return path.toString();
    }

    /**
     * Serializes a batch of TickData to bytes using length-delimited protobuf format.
     */
    private byte[] serializeBatch(List<TickData> batch) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (TickData tick : batch) {
            tick.writeDelimitedTo(bos);
        }
        return bos.toByteArray();
    }

    /**
     * Deserializes a batch from bytes using length-delimited protobuf format.
     */
    private List<TickData> deserializeBatch(byte[] data) throws IOException {
        List<TickData> batch = new ArrayList<>();
        ByteArrayInputStream bis = new ByteArrayInputStream(data);

        while (bis.available() > 0) {
            TickData tick = TickData.parseDelimitedFrom(bis);
            if (tick == null) {
                break; // End of stream
            }
            batch.add(tick);
        }

        return batch;
    }

    /**
     * Compresses data using the configured compression codec.
     * <p>
     * This method is implemented generically in the abstract class to avoid
     * code duplication across storage backends. NoneCodec is treated like any
     * other codec - it simply returns the stream unchanged.
     *
     * @param data uncompressed data
     * @return compressed data (or original data if NoneCodec is used)
     * @throws IOException if compression fails
     */
    protected byte[] compressBatch(byte[] data) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (OutputStream compressedStream = codec.wrapOutputStream(bos)) {
                compressedStream.write(data);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IOException("Failed to compress batch", e);
        }
    }

    /**
     * Decompresses data based on filename extension.
     * <p>
     * This method is implemented generically in the abstract class to avoid
     * code duplication across storage backends. It auto-detects compression
     * from the filename extension, independent of the configured codec for writing.
     * <p>
     * NoneCodec is treated like any other codec - it simply returns the stream unchanged.
     *
     * @param compressedData potentially compressed data
     * @param filename filename used to auto-detect compression by extension
     * @return decompressed data (or original data if no compression detected)
     * @throws IOException if decompression fails
     */
    protected byte[] decompressBatch(byte[] compressedData, String filename) throws IOException {
        // Auto-detect codec from file extension (independent of configured codec!)
        ICompressionCodec detectedCodec = org.evochora.datapipeline.utils.compression.CompressionCodecFactory.detectFromExtension(filename);

        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(compressedData);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream decompressedStream = detectedCodec.wrapInputStream(bis)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = decompressedStream.read(buffer)) != -1) {
                    bos.write(buffer, 0, bytesRead);
                }
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IOException("Failed to decompress batch: " + filename, e);
        }
    }

    @Override
    public BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults) throws IOException {
        if (prefix == null) {
            throw new IllegalArgumentException("prefix cannot be null");
        }
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be > 0");
        }

        // Delegate to subclass to get all files with prefix (potentially paginated internally)
        List<String> allFiles = listFilesWithPrefix(prefix, continuationToken, maxResults + 1);

        // Filter to batch files only
        List<String> batchFiles = allFiles.stream()
            .filter(path -> {
                String filename = path.substring(path.lastIndexOf('/') + 1);
                return filename.startsWith("batch_") && (filename.endsWith(".pb") || filename.contains(".pb."));
            })
            .sorted()  // Lexicographic order = tick order
            .limit(maxResults + 1)  // +1 to detect truncation
            .toList();

        // Check if truncated
        boolean truncated = batchFiles.size() > maxResults;
        List<String> resultFiles = truncated ? batchFiles.subList(0, maxResults) : batchFiles;
        String nextToken = truncated ? resultFiles.get(resultFiles.size() - 1) : null;

        return new BatchFileListResult(resultFiles, nextToken, truncated);
    }

    // ===== IResource implementation =====

    /**
     * Returns the current operational state for the specified usage type.
     * <p>
     * All batch storage implementations return ACTIVE for "storage-read" and "storage-write" usage types,
     * as batch storage is stateless and always available.
     * <p>
     * This method is final to ensure consistent behavior across all storage implementations.
     *
     * @param usageType The usage type (must be "storage-read" or "storage-write")
     * @return UsageState.ACTIVE for valid usage types
     * @throws IllegalArgumentException if usageType is null or not recognized
     */
    @Override
    public final UsageState getUsageState(String usageType) {
        if (usageType == null) {
            throw new IllegalArgumentException("Storage requires non-null usageType");
        }

        return switch (usageType) {
            case "storage-read", "storage-write" -> UsageState.ACTIVE;
            default -> throw new IllegalArgumentException("Unknown usageType: " + usageType);
        };
    }

    // ===== IMonitorable implementation =====

    /**
     * Returns metrics for this storage resource.
     * <p>
     * This implementation returns base metrics tracked by all storage implementations,
     * then calls {@link #addCustomMetrics(Map)} to allow subclasses to add
     * implementation-specific metrics.
     * <p>
     * Base metrics included:
     * <ul>
     *   <li>write_operations - number of write calls</li>
     *   <li>read_operations - number of read calls</li>
     *   <li>bytes_written - total bytes written</li>
     *   <li>bytes_read - total bytes read</li>
     *   <li>write_errors - number of write errors</li>
     *   <li>read_errors - number of read errors</li>
     * </ul>
     *
     * @return Map of metric names to their current values
     */
    @Override
    public final Map<String, Number> getMetrics() {
        Map<String, Number> metrics = getBaseMetrics();
        addCustomMetrics(metrics);
        return metrics;
    }

    /**
     * Returns the base metrics tracked by AbstractBatchStorageResource.
     * <p>
     * This method is final and cannot be overridden. Subclasses should use
     * {@link #addCustomMetrics(Map)} to add their own metrics.
     *
     * @return Map containing base metrics in the order they should be reported
     */
    protected final Map<String, Number> getBaseMetrics() {
        Map<String, Number> metrics = new LinkedHashMap<>();
        metrics.put("write_operations", writeOperations.get());
        metrics.put("read_operations", readOperations.get());
        metrics.put("bytes_written", bytesWritten.get());
        metrics.put("bytes_read", bytesRead.get());
        metrics.put("write_errors", writeErrors.get());
        metrics.put("read_errors", readErrors.get());
        return metrics;
    }

    /**
     * Hook method for subclasses to add implementation-specific metrics.
     * <p>
     * The default implementation does nothing. Subclasses should override this method
     * to add their own metrics to the provided map.
     * <p>
     * Example:
     * <pre>
     * &#64;Override
     * protected void addCustomMetrics(Map&lt;String, Number&gt; metrics) {
     *     metrics.put("disk_available_bytes", rootDirectory.getUsableSpace());
     *     metrics.put("s3_put_requests", s3PutRequests.get());
     * }
     * </pre>
     *
     * @param metrics Mutable map to add custom metrics to (already contains base metrics)
     */
    protected void addCustomMetrics(Map<String, Number> metrics) {
        // Default: no custom metrics
    }

    /**
     * Returns the list of operational errors.
     * <p>
     * Implementation of {@link IMonitorable#getErrors()}.
     */
    @Override
    public List<OperationalError> getErrors() {
        synchronized (errors) {
            return new ArrayList<>(errors);
        }
    }

    /**
     * Clears all operational errors and resets error counters.
     * <p>
     * Implementation of {@link IMonitorable#clearErrors()}.
     */
    @Override
    public void clearErrors() {
        errors.clear();
        writeErrors.set(0);
        readErrors.set(0);
    }

    /**
     * Returns whether the storage is healthy (no errors).
     * <p>
     * Implementation of {@link IMonitorable#isHealthy()}.
     */
    @Override
    public boolean isHealthy() {
        return errors.isEmpty();
    }

    // ===== Abstract I/O primitives (subclasses implement) =====

    /**
     * Writes bytes to a path. Creates parent directories if needed.
     */
    protected abstract void writeBytes(String path, byte[] data) throws IOException;

    /**
     * Reads all bytes from a path.
     */
    protected abstract byte[] readBytes(String path) throws IOException;

    /**
     * Atomically moves a file from src to dest.
     */
    protected abstract void atomicMove(String src, String dest) throws IOException;

    /**
     * Lists files matching the prefix with pagination support.
     * <p>
     * This method recursively scans the storage for all files (not just batch files)
     * matching the prefix. The abstract class will filter to batch files only.
     * <p>
     * Implementation notes:
     * <ul>
     *   <li>Must return results in lexicographic order by full path</li>
     *   <li>Should support pagination via continuationToken</li>
     *   <li>Should return up to maxResults files</li>
     *   <li>Filter out .tmp files to avoid race conditions</li>
     * </ul>
     *
     * @param prefix Filter prefix (e.g., "sim123/")
     * @param continuationToken Token from previous call (filename to start after), or null for first page
     * @param maxResults Maximum files to return
     * @return List of file paths matching prefix, sorted lexicographically
     * @throws IOException If storage access fails
     */
    protected abstract List<String> listFilesWithPrefix(String prefix, String continuationToken, int maxResults) throws IOException;
}
