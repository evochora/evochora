package org.evochora.datapipeline.resources.storage;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.resources.storage.wrappers.MonitoredBatchStorageReader;
import org.evochora.datapipeline.resources.storage.wrappers.MonitoredBatchStorageWriter;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
 * storage backend (filesystem, S3, etc.). Inherits IMonitorable infrastructure
 * from {@link AbstractResource}, with a hook method {@link #addCustomMetrics(Map)}
 * for implementation-specific metrics.
 */
public abstract class AbstractBatchStorageResource extends AbstractResource
    implements IBatchStorageWrite, IBatchStorageRead, IContextualResource {

    private static final Logger log = LoggerFactory.getLogger(AbstractBatchStorageResource.class);

    // Configuration
    protected final List<Long> folderLevels;
    protected final ICompressionCodec codec;
    protected final int metricsWindowSeconds;

    // Base metrics tracking (all storage implementations)
    protected final java.util.concurrent.atomic.AtomicLong writeOperations = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong readOperations = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong bytesWritten = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong bytesRead = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong writeErrors = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong readErrors = new java.util.concurrent.atomic.AtomicLong(0);

    // Performance metrics (sliding window using unified utils)
    private final SlidingWindowCounter writeOpsCounter;
    private final SlidingWindowCounter writeBytesCounter;
    private final SlidingWindowPercentiles writeLatencyTracker;
    private final SlidingWindowCounter readOpsCounter;
    private final SlidingWindowCounter readBytesCounter;
    private final SlidingWindowPercentiles readLatencyTracker;

    protected AbstractBatchStorageResource(String name, Config options) {
        super(name, options);

        // Initialize compression codec (fail-fast if environment validation fails)
        try {
            this.codec = org.evochora.datapipeline.utils.compression.CompressionCodecFactory.createAndValidate(options);
            if (!"none".equals(codec.getName())) {
                log.debug("Storage '{}' using compression: codec={}, level={}",
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

        // Parse metrics window configuration (default: 5 seconds)
        this.metricsWindowSeconds = options.hasPath("metricsWindowSeconds")
            ? options.getInt("metricsWindowSeconds")
            : 5;

        // Initialize performance metrics trackers
        this.writeOpsCounter = new SlidingWindowCounter(metricsWindowSeconds);
        this.writeBytesCounter = new SlidingWindowCounter(metricsWindowSeconds);
        this.writeLatencyTracker = new SlidingWindowPercentiles(metricsWindowSeconds);
        this.readOpsCounter = new SlidingWindowCounter(metricsWindowSeconds);
        this.readBytesCounter = new SlidingWindowCounter(metricsWindowSeconds);
        this.readLatencyTracker = new SlidingWindowPercentiles(metricsWindowSeconds);

        log.info("Storage '{}' initialized: codec={}, level={}, folders={}, metricsWindow={}s",
            name, codec.getName(), codec.getLevel(), folderLevels, metricsWindowSeconds);
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

        // Generate batch filename (logical key)
        String logicalFilename = String.format("batch_%019d_%019d.pb", firstTick, lastTick);
        String logicalPath = folderPath + "/" + logicalFilename;

        // Serialize batch
        byte[] data = serializeBatch(batch);

        // Write batch (handles compression, atomicity, and metrics)
        put(logicalPath, data);

        // Return physical path for reference (with compression extension)
        String physicalPath = toPhysicalPath(logicalPath);
        log.debug("Wrote batch {} with {} ticks", physicalPath, batch.size());

        return physicalPath;
    }

    @Override
    public List<TickData> readBatch(String filename) throws IOException {
        if (filename == null || filename.isEmpty()) {
            throw new IllegalArgumentException("filename cannot be null or empty");
        }

        // Read and decompress (handles resolution, decompression, and metrics)
        byte[] data = get(filename);

        // Deserialize
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

        // Write (handles compression, atomicity, and metrics)
        put(key, data);
    }

    @Override
    public <T extends MessageLite> T readMessage(String key, Parser<T> parser) throws IOException {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key cannot be null or empty");
        }
        if (parser == null) {
            throw new IllegalArgumentException("parser cannot be null");
        }

        // Read and decompress (handles resolution, decompression, and metrics)
        byte[] data = get(key);

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
     * Strips compression extensions from physical path to get logical path.
     * Used by listBatchFiles() to return logical keys.
     * <p>
     * Uses CompressionCodecFactory.detectFromExtension() to dynamically detect
     * compression extensions. This ensures new codecs are automatically supported
     * without changing this method.
     *
     * @param physicalPath physical path like "batch_000.pb.zst"
     * @return logical path like "batch_000.pb"
     */
    private String toLogicalPath(String physicalPath) {
        // Auto-detect compression from extension
        ICompressionCodec detectedCodec = org.evochora.datapipeline.utils.compression.CompressionCodecFactory.detectFromExtension(physicalPath);
        String extension = detectedCodec.getFileExtension();
        
        // If NoneCodec, extension is "" → no change needed
        if (extension.isEmpty() || !physicalPath.endsWith(extension)) {
            return physicalPath;
        }
        
        // Strip compression extension
        return physicalPath.substring(0, physicalPath.length() - extension.length());
    }

    /**
     * Parses the start tick from a batch filename.
     * <p>
     * Batch filenames follow the pattern: batch_STARTICK_ENDTICK.pb[.compression]
     * Example: "batch_0000000000_0000000999.pb.zst" → startTick = 0
     * <p>
     * This method is protected so subclasses can use it for tick-based filtering
     * in their {@link #listRaw} implementations.
     *
     * @param filename The batch filename (with or without path, with or without compression extension)
     * @return The start tick, or -1 if filename doesn't match the batch pattern
     */
    protected long parseBatchStartTick(String filename) {
        // Extract just the filename if a path is provided
        String name = filename.substring(filename.lastIndexOf('/') + 1);
        
        // Check if it matches the batch pattern
        if (!name.startsWith("batch_") || !name.contains(".pb")) {
            return -1;
        }
        
        try {
            // Extract the part between "batch_" and the first underscore after it
            // Pattern: batch_0000000000_0000000999.pb...
            int startIdx = 6; // Length of "batch_"
            int endIdx = name.indexOf('_', startIdx);
            if (endIdx == -1) {
                return -1;
            }
            
            String tickStr = name.substring(startIdx, endIdx);
            return Long.parseLong(tickStr);
        } catch (NumberFormatException e) {
            log.trace("Failed to parse start tick from filename: {}", filename, e);
            return -1;
        }
    }

    /**
     * Parses the end tick from a batch filename.
     * <p>
     * Batch filenames follow the pattern: batch_STARTICK_ENDTICK.pb[.compression]
     * Example: "batch_0000000000_0000000999.pb.zst" → endTick = 999
     * <p>
     * This method is protected so subclasses can use it for tick-based filtering
     * in their {@link #listRaw} implementations.
     *
     * @param filename The batch filename (with or without path, with or without compression extension)
     * @return The end tick, or -1 if filename doesn't match the batch pattern
     */
    protected long parseBatchEndTick(String filename) {
        // Extract just the filename if a path is provided
        String name = filename.substring(filename.lastIndexOf('/') + 1);
        
        // Check if it matches the batch pattern
        if (!name.startsWith("batch_") || !name.contains(".pb")) {
            return -1;
        }
        
        try {
            // Extract the part between the second underscore and ".pb"
            // Pattern: batch_0000000000_0000000999.pb...
            int startIdx = 6; // Length of "batch_"
            int firstUnderscore = name.indexOf('_', startIdx);
            if (firstUnderscore == -1) {
                return -1;
            }
            
            int secondUnderscore = firstUnderscore + 1;
            int dotPbIdx = name.indexOf(".pb");
            if (dotPbIdx == -1) {
                return -1;
            }
            
            String tickStr = name.substring(secondUnderscore, dotPbIdx);
            return Long.parseLong(tickStr);
        } catch (NumberFormatException e) {
            log.trace("Failed to parse end tick from filename: {}", filename, e);
            return -1;
        }
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
    private byte[] compressBatch(byte[] data) throws IOException {
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
    private byte[] decompressBatch(byte[] compressedData, String filename) throws IOException {
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
        return listBatchFilesInternal(prefix, continuationToken, maxResults, null, null);
    }

    @Override
    public BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults, long startTick) throws IOException {
        if (startTick < 0) {
            throw new IllegalArgumentException("startTick must be >= 0");
        }
        return listBatchFilesInternal(prefix, continuationToken, maxResults, startTick, null);
    }

    @Override
    public BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults, long startTick, long endTick) throws IOException {
        if (startTick < 0) {
            throw new IllegalArgumentException("startTick must be >= 0");
        }
        if (endTick < startTick) {
            throw new IllegalArgumentException("endTick must be >= startTick");
        }
        return listBatchFilesInternal(prefix, continuationToken, maxResults, startTick, endTick);
    }

    /**
     * Internal implementation for listing batch files with optional tick filtering.
     * <p>
     * This method delegates to {@link #listRaw} with nullable tick parameters and performs
     * the common logic of converting physical paths to logical paths and filtering to batch files.
     *
     * @param prefix Filter prefix (e.g., "sim123/" for specific simulation)
     * @param continuationToken Token from previous call, or null for first page
     * @param maxResults Maximum files to return per page
     * @param startTick Minimum start tick (nullable - null means no lower bound)
     * @param endTick Maximum start tick (nullable - null means no upper bound)
     * @return Paginated result with matching batch filenames
     * @throws IOException If storage access fails
     */
    private BatchFileListResult listBatchFilesInternal(String prefix, String continuationToken, int maxResults,
                                                        Long startTick, Long endTick) throws IOException {
        if (prefix == null) {
            throw new IllegalArgumentException("prefix cannot be null");
        }
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be > 0");
        }

        // Delegate to subclass to get all files with prefix (potentially paginated internally)
        // listRaw returns PHYSICAL paths (with compression extensions)
        // Request maxResults + 1 to detect truncation
        List<String> allPhysicalFiles = listRaw(prefix, false, continuationToken, maxResults + 1, startTick, endTick);

        // Convert to logical paths and filter to batch files
        List<String> batchFiles = allPhysicalFiles.stream()
            .map(this::toLogicalPath)  // Strip compression extensions
            .filter(path -> {
                String filename = path.substring(path.lastIndexOf('/') + 1);
                return filename.startsWith("batch_") && filename.endsWith(".pb");
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

    /**
     * Adds storage-specific metrics to the provided map.
     * <p>
     * This override adds counters and performance metrics tracked by all storage resources.
     * Subclasses should call {@code super.addCustomMetrics(metrics)} to include these.
     * <p>
     * Added metrics:
     * <ul>
     *   <li>write_operations - cumulative write count</li>
     *   <li>read_operations - cumulative read count</li>
     *   <li>bytes_written - cumulative bytes written</li>
     *   <li>bytes_read - cumulative bytes read</li>
     *   <li>write_errors - cumulative write errors</li>
     *   <li>read_errors - cumulative read errors</li>
     *   <li>writes_per_sec - sliding window write rate (O(1))</li>
     *   <li>reads_per_sec - sliding window read rate (O(1))</li>
     *   <li>write_bytes_per_sec - sliding window write throughput (O(1))</li>
     *   <li>read_bytes_per_sec - sliding window read throughput (O(1))</li>
     *   <li>write_latency_ms - sliding window average write latency (O(1))</li>
     *   <li>read_latency_ms - sliding window average read latency (O(1))</li>
     * </ul>
     *
     * @param metrics Mutable map to add metrics to (already contains base error_count from AbstractResource)
     */
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics
        
        // Cumulative metrics
        metrics.put("write_operations", writeOperations.get());
        metrics.put("read_operations", readOperations.get());
        metrics.put("bytes_written", bytesWritten.get());
        metrics.put("bytes_read", bytesRead.get());
        metrics.put("write_errors", writeErrors.get());
        metrics.put("read_errors", readErrors.get());
        
        // Performance metrics (sliding window using unified utils - O(1))
        metrics.put("writes_per_sec", writeOpsCounter.getRate());
        metrics.put("reads_per_sec", readOpsCounter.getRate());
        metrics.put("write_bytes_per_sec", writeBytesCounter.getRate());
        metrics.put("read_bytes_per_sec", readBytesCounter.getRate());
        metrics.put("write_latency_ms", writeLatencyTracker.getAverage() / 1_000_000.0);
        metrics.put("read_latency_ms", readLatencyTracker.getAverage() / 1_000_000.0);
    }

    /**
     * Clears operational errors and resets error counters.
     * <p>
     * Extends AbstractResource's clearErrors() to also reset storage-specific error counters.
     */
    @Override
    public void clearErrors() {
        super.clearErrors();  // Clear errors collection in AbstractResource
        writeErrors.set(0);
        readErrors.set(0);
    }

    // ===== IContextualResource implementation =====

    /**
     * Returns a contextual wrapper for this storage resource based on usage type.
     * <p>
     * All batch storage implementations support the same usage types and use the same
     * monitoring wrappers, as the wrappers operate on the standard {@link IBatchStorageRead}
     * and {@link IBatchStorageWrite} interfaces.
     * <p>
     * Supported usage types:
     * <ul>
     *   <li>storage-write - Returns a {@link MonitoredBatchStorageWriter} that tracks write metrics</li>
     *   <li>storage-read - Returns a {@link MonitoredBatchStorageReader} that tracks read metrics</li>
     * </ul>
     *
     * @param context The resource context containing usage type and service information
     * @return The wrapped resource with monitoring capabilities
     * @throws IllegalArgumentException if usageType is null or not supported
     */
    @Override
    public final IWrappedResource getWrappedResource(ResourceContext context) {
        if (context.usageType() == null) {
            throw new IllegalArgumentException(String.format(
                "Storage resource '%s' requires a usageType in the binding URI. " +
                "Expected format: 'usageType:%s' where usageType is one of: " +
                "storage-write, storage-read",
                getResourceName(), getResourceName()
            ));
        }

        return switch (context.usageType()) {
            case "storage-write" -> new MonitoredBatchStorageWriter(this, context);
            case "storage-read" -> new MonitoredBatchStorageReader(this, context);
            default -> throw new IllegalArgumentException(String.format(
                "Unsupported usage type '%s' for storage resource '%s'. " +
                "Supported types: storage-write, storage-read",
                context.usageType(), getResourceName()
            ));
        };
    }

    // ===== Abstract I/O primitives (subclasses implement) =====

    // ===== Resolution & High-Level Operations (non-abstract) =====

    /**
     * Converts logical key to physical path for writing.
     * Adds compression extension based on configured codec.
     */
    private String toPhysicalPath(String logicalKey) {
        return logicalKey + codec.getFileExtension();
    }

    /**
     * Finds physical path for a logical key.
     * Uses prefix matching to find actual file (with or without compression).
     *
     * @param logicalKey logical key like "runId/metadata.pb"
     * @return physical path like "runId/metadata.pb.zst"
     * @throws IOException if file not found
     */
    private String findPhysicalPath(String logicalKey) throws IOException {
        List<String> matches = listRaw(logicalKey, false, null, 1, null, null);
        if (matches.isEmpty()) {
            throw new IOException("File not found: " + logicalKey);
        }
        return matches.get(0);
    }

    /**
     * Writes data to storage at logical key.
     * Handles compression, physical path resolution, and metrics tracking.
     *
     * @param logicalKey logical key without compression extension
     * @param data uncompressed data
     * @throws IOException if write fails
     */
    protected final void put(String logicalKey, byte[] data) throws IOException {
        byte[] compressed = compressBatch(data);
        String physicalPath = toPhysicalPath(logicalKey);
        
        long writeStart = System.nanoTime();
        putRaw(physicalPath, compressed);
        long writeLatency = System.nanoTime() - writeStart;
        
        recordWrite(compressed.length, writeLatency);
    }

    /**
     * Reads data from storage at logical key.
     * Handles physical path resolution, decompression, and metrics tracking.
     *
     * @param logicalKey logical key without compression extension
     * @return decompressed data
     * @throws IOException if file not found or read fails
     */
    protected final byte[] get(String logicalKey) throws IOException {
        String physicalPath = findPhysicalPath(logicalKey);
        
        long readStart = System.nanoTime();
        byte[] compressed = getRaw(physicalPath);
        long readLatency = System.nanoTime() - readStart;
        
        recordRead(compressed.length, readLatency);
        
        return decompressBatch(compressed, physicalPath);
    }

    // ===== Abstract I/O primitives (subclasses implement) =====

    /**
     * Writes raw bytes to physical path.
     * <p>
     * Implementation must:
     * <ul>
     *   <li>Create parent directories if needed</li>
     *   <li>Ensure atomic writes (temp-then-move pattern or backend-native atomics)</li>
     *   <li>Only perform I/O - metrics are tracked by caller (put() method)</li>
     * </ul>
     * <p>
     * Example implementations:
     * <ul>
     *   <li>FileSystem: temp file + Files.move(ATOMIC_MOVE)</li>
     *   <li>S3: Direct putObject (inherently atomic)</li>
     * </ul>
     *
     * @param physicalPath physical path including compression extension
     * @param data raw bytes (already compressed by caller)
     * @throws IOException if write fails
     */
    protected abstract void putRaw(String physicalPath, byte[] data) throws IOException;

    /**
     * Reads raw bytes from physical path.
     * <p>
     * Implementation must:
     * <ul>
     *   <li>Throw IOException if file doesn't exist</li>
     *   <li>Only perform I/O - metrics are tracked by caller (get() method)</li>
     * </ul>
     *
     * @param physicalPath physical path including compression extension
     * @return raw bytes (still compressed, caller handles decompression)
     * @throws IOException if file not found or read fails
     */
    protected abstract byte[] getRaw(String physicalPath) throws IOException;

    /**
     * Lists physical paths or directory prefixes matching a prefix, with optional tick filtering.
     * <p>
     * This method works for three use cases:
     * <ul>
     *   <li>Finding file variants: listRaw("runId/metadata.pb", false, null, 1, null, null)</li>
     *   <li>Listing batches: listRaw("runId/", false, token, 1000, null, null)</li>
     *   <li>Listing batches from tick: listRaw("runId/", false, token, 1000, 5000L, null)</li>
     *   <li>Listing batches in range: listRaw("runId/", false, token, 1000, 1000L, 5000L)</li>
     *   <li>Listing run IDs: listRaw("", true, null, 1000, null, null)</li>
     * </ul>
     * <p>
     * Performance: O(1) for directories or single file, O(n) for recursive file listing.
     * <p>
     * Implementation must:
     * <ul>
     *   <li>If listDirectories=false: Recursively scan for files (Files.walk)</li>
     *   <li>If listDirectories=true: List immediate subdirectory prefixes (Files.list)</li>
     *   <li>Return physical paths with compression extensions (files) or directory prefixes ending with "/"</li>
     *   <li>Filter out .tmp files to avoid race conditions</li>
     *   <li>Apply tick filtering if startTick/endTick are non-null (only relevant for batch files)</li>
     *   <li>Support pagination via continuationToken</li>
     *   <li>Enforce maxResults limit (prevent runaway queries)</li>
     *   <li>Return results in lexicographic order</li>
     * </ul>
     * <p>
     * S3 mapping: ListObjectsV2 with prefix, delimiter="/", maxKeys, continuationToken
     * <ul>
     *   <li>listDirectories=true → delimiter="/" (returns CommonPrefixes)</li>
     *   <li>listDirectories=false → delimiter=null (returns Contents)</li>
     * </ul>
     *
     * @param prefix prefix to match (e.g., "runId/metadata.pb", "runId/", or "")
     * @param listDirectories if true, return directory prefixes; if false, return files
     * @param continuationToken pagination token from previous call, or null
     * @param maxResults hard limit on results
     * @param startTick minimum batch start tick (nullable, ignored if listDirectories=true or null)
     * @param endTick maximum batch start tick (nullable, ignored if listDirectories=true or null)
     * @return physical paths (files) or directory prefixes, max maxResults items
     * @throws IOException if storage access fails
     */
    protected abstract List<String> listRaw(String prefix, boolean listDirectories, String continuationToken, 
                                             int maxResults, Long startTick, Long endTick) throws IOException;

    @Override
    public List<String> listRunIds(Instant afterTimestamp) throws IOException {
        // List all run directories (first level directories in storage root)
        // Pass null for startTick/endTick as they are not relevant for directory listing
        List<String> runDirs = listRaw("", true, null, 10000, null, null);
        
        // Parse timestamps and filter
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSS");
        return runDirs.stream()
            .map(dir -> dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir)  // Strip trailing "/"
            .filter(runId -> {
                if (runId.length() < 17) return false;
                try {
                    String timestampStr = runId.substring(0, 17);
                    LocalDateTime ldt = LocalDateTime.parse(timestampStr, formatter);
                    Instant runIdInstant = ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
                    return runIdInstant.isAfter(afterTimestamp);
                } catch (DateTimeParseException e) {
                    log.trace("Ignoring non-runId directory: {}", runId);
                    return false;
                }
            })
            .sorted()
            .toList();
    }

    // ===== Performance tracking helpers =====

    /**
     * Records a write operation for performance tracking.
     * This is an O(1) operation using unified monitoring utils.
     * Updates both legacy counters (writeOperations) and new sliding window metrics.
     *
     * @param bytes Number of bytes written
     * @param latencyNanos Write latency in nanoseconds
     */
    private void recordWrite(long bytes, long latencyNanos) {
        // Legacy counters (for backward compatibility)
        writeOperations.incrementAndGet();
        bytesWritten.addAndGet(bytes);
        
        // New sliding window metrics
        writeOpsCounter.recordCount();
        writeBytesCounter.recordSum(bytes);
        writeLatencyTracker.record(latencyNanos);
    }

    /**
     * Records a read operation for performance tracking.
     * This is an O(1) operation using unified monitoring utils.
     * Updates both legacy counters (readOperations) and new sliding window metrics.
     *
     * @param bytes Number of bytes read
     * @param latencyNanos Read latency in nanoseconds
     */
    private void recordRead(long bytes, long latencyNanos) {
        // Legacy counters (for backward compatibility)
        readOperations.incrementAndGet();
        bytesRead.addAndGet(bytes);
        
        // New sliding window metrics
        readOpsCounter.recordCount();
        readBytesCounter.recordSum(bytes);
        readLatencyTracker.record(latencyNanos);
    }

}
