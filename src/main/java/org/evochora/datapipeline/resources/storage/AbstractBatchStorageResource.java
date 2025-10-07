package org.evochora.datapipeline.resources.storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.storage.BatchMetadata;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.StorageMetadata;
import org.evochora.datapipeline.resources.AbstractResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Abstract base class for batch storage resources with hierarchical folder organization,
 * manifest-based querying, and in-memory caching.
 * <p>
 * This class implements all the high-level logic for batch storage:
 * <ul>
 *   <li>Hierarchical folder path calculation based on tick ranges</li>
 *   <li>Manifest file management (JSONL format, one per consumer)</li>
 *   <li>In-memory manifest caching with TTL and write-through invalidation</li>
 *   <li>Storage metadata generation (.storage-metadata.json)</li>
 *   <li>Query optimization (folder pruning, lookback calculation)</li>
 * </ul>
 * <p>
 * Subclasses only need to implement low-level I/O primitives for their specific
 * storage backend (filesystem, S3, etc.).
 */
public abstract class AbstractBatchStorageResource extends AbstractResource implements IBatchStorageWrite, IBatchStorageRead {

    private static final Logger log = LoggerFactory.getLogger(AbstractBatchStorageResource.class);
    private static final String STORAGE_METADATA_VERSION = "1.0";
    private static final Gson GSON = new Gson();

    // Configuration
    protected final List<Long> folderLevels;
    protected final int maxBatchSize;
    protected final StorageMetadata.CompressionConfig compression;
    protected final int cacheTtlSeconds;

    // Manifest cache
    protected final ManifestCache manifestCache;

    // Lazy-initialized storage metadata
    private volatile boolean metadataInitialized = false;
    private final Object metadataLock = new Object();

    protected AbstractBatchStorageResource(String name, Config options) {
        super(name, options);

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

        // Parse maxBatchSize
        this.maxBatchSize = options.hasPath("folderStructure.maxBatchSize")
            ? options.getInt("folderStructure.maxBatchSize")
            : 10000;

        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }

        // Parse compression configuration
        if (options.hasPath("compression.enabled") && options.getBoolean("compression.enabled")) {
            String codec = options.hasPath("compression.codec")
                ? options.getString("compression.codec")
                : "zstd";
            int level = options.hasPath("compression.level")
                ? options.getInt("compression.level")
                : 3;
            this.compression = new StorageMetadata.CompressionConfig(codec, level);
        } else {
            this.compression = new StorageMetadata.CompressionConfig("none", 0);
        }

        // Parse cache TTL (0 = disabled)
        this.cacheTtlSeconds = options.hasPath("cacheTtlSeconds")
            ? options.getInt("cacheTtlSeconds")
            : 5;

        if (cacheTtlSeconds < 0) {
            throw new IllegalArgumentException("cacheTtlSeconds cannot be negative");
        }

        this.manifestCache = new ManifestCache(cacheTtlSeconds);

        log.info("AbstractBatchStorageResource '{}' initialized: folders={}, maxBatch={}, compression={}/{}, cacheTTL={}s",
            name, folderLevels, maxBatchSize, compression.codec, compression.level, cacheTtlSeconds);
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

        // Ensure storage metadata exists (lazy initialization)
        ensureStorageMetadata(batch.get(0).getSimulationRunId());

        // Calculate folder path from firstTick
        String simulationId = batch.get(0).getSimulationRunId();
        String folderPath = simulationId + "/" + calculateFolderPath(firstTick);

        // Generate batch filename
        String filename = String.format("batch_%019d_%019d.pb", firstTick, lastTick);
        if (!"none".equals(compression.codec)) {
            filename += "." + getCompressionExtension(compression.codec);
        }

        String fullPath = folderPath + "/" + filename;

        // Serialize and compress batch
        byte[] data = serializeBatch(batch);
        byte[] compressedData = compressBatch(data);

        // Write atomically: temp file → final file
        String tempPath = folderPath + "/.tmp-" + UUID.randomUUID() + "-" + filename;
        try {
            writeBytes(tempPath, compressedData);
            atomicMove(tempPath, fullPath);
        } catch (IOException e) {
            // Clean up temp file on failure
            try {
                deleteIfExists(tempPath);
            } catch (IOException cleanupEx) {
                log.warn("Failed to clean up temp file: {}", tempPath, cleanupEx);
            }
            throw e;
        }

        // Append to manifest (JSONL format)
        String manifestPath = folderPath + "/.manifest-" + resourceName + ".jsonl";
        String manifestEntry = createManifestEntry(filename, firstTick, lastTick,
            batch.size(), compressedData.length);
        appendLine(manifestPath, manifestEntry);

        // Invalidate cache for this folder
        manifestCache.invalidate(folderPath);

        log.debug("Wrote batch {} with {} ticks ({} bytes compressed)",
            fullPath, batch.size(), compressedData.length);

        return fullPath;
    }

    @Override
    public List<BatchMetadata> queryBatches(long startTick, long endTick) throws IOException {
        if (startTick > endTick) {
            throw new IllegalArgumentException(
                String.format("startTick (%d) cannot be greater than endTick (%d)", startTick, endTick)
            );
        }

        // Calculate folder range with lookback
        List<String> foldersToScan = calculateFolderRange(startTick, endTick);

        List<BatchMetadata> results = new ArrayList<>();

        for (String folder : foldersToScan) {
            // Check cache first
            List<BatchMetadata> folderBatches = manifestCache.get(folder);

            if (folderBatches == null) {
                // Cache miss - read from storage
                folderBatches = readFolderManifests(folder);
                manifestCache.put(folder, folderBatches);
            }

            // Filter by query range
            for (BatchMetadata batch : folderBatches) {
                if (batch.overlaps(startTick, endTick)) {
                    results.add(batch);
                }
            }
        }

        log.debug("Query [{}-{}] scanned {} folders, found {} batches",
            startTick, endTick, foldersToScan.size(), results.size());

        return results;
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

    /**
     * Calculates folder path for a given tick using configured folder levels.
     * <p>
     * Example with levels=[100000000, 100000]:
     * <ul>
     *   <li>Tick 123,456,789 → "001/234/"</li>
     *   <li>Tick 5,000,000,000 → "050/000/"</li>
     * </ul>
     */
    protected String calculateFolderPath(long tick) {
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
     * Calculates folder range that might contain batches overlapping [startTick, endTick].
     * Includes lookback to account for batches starting before startTick.
     * <p>
     * Safety: Limits the maximum number of folders scanned to prevent OOM on unreasonably large ranges.
     */
    protected List<String> calculateFolderRange(long startTick, long endTick) {
        // Calculate lookback distance
        long lookback = maxBatchSize;
        long adjustedStart = Math.max(0, startTick - lookback);

        // Get innermost folder size
        long folderSize = folderLevels.get(folderLevels.size() - 1);

        // Calculate folder indices
        long startFolder = adjustedStart / folderSize;
        long endFolder = endTick / folderSize;

        // Safety check: prevent OOM on unreasonably large ranges
        // Limit to 100,000 folders (with default 1000 ticks/folder = 100M tick range)
        long folderCount = endFolder - startFolder + 1;
        if (folderCount > 100_000) {
            log.warn("Query range [{}, {}] spans {} folders (max 100,000). " +
                "Consider using smaller tick ranges for better performance.",
                startTick, endTick, folderCount);
            // Scan only existing folders instead of generating all theoretical folders
            return scanExistingFolders();
        }

        // Generate folder paths for reasonable ranges
        List<String> folders = new ArrayList<>();
        for (long f = startFolder; f <= endFolder; f++) {
            folders.add(calculateFolderPath(f * folderSize));
        }

        return folders;
    }

    /**
     * Scans storage to find all existing folders (fallback for large queries).
     * This is slower but safer than generating millions of theoretical folder paths.
     */
    protected List<String> scanExistingFolders() throws UnsupportedOperationException {
        try {
            // List all keys and extract unique folder paths
            List<String> allKeys = listKeys("");
            return allKeys.stream()
                .map(key -> {
                    // Extract folder path (everything except filename)
                    int lastSlash = key.lastIndexOf('/');
                    return lastSlash > 0 ? key.substring(0, lastSlash) : "";
                })
                .filter(path -> !path.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to scan existing folders", e);
            return Collections.emptyList();
        }
    }

    /**
     * Reads all manifest files in a folder and parses them into BatchMetadata list.
     */
    protected List<BatchMetadata> readFolderManifests(String folder) throws IOException {
        // List all manifest files (.manifest-*.jsonl)
        List<String> manifestFiles = listKeys(folder + "/.manifest-")
            .stream()
            .filter(key -> key.endsWith(".jsonl"))
            .collect(Collectors.toList());

        if (manifestFiles.isEmpty()) {
            return Collections.emptyList();
        }

        List<BatchMetadata> batches = new ArrayList<>();

        for (String manifestFile : manifestFiles) {
            try {
                List<String> lines = readLines(manifestFile);
                for (String line : lines) {
                    if (line != null && !line.trim().isEmpty()) {
                        try {
                            BatchMetadata batch = parseManifestLine(line, folder);
                            batches.add(batch);
                        } catch (Exception e) {
                            log.warn("Failed to parse manifest line in {}: {}", manifestFile, line, e);
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read manifest file: {}", manifestFile, e);
            }
        }

        return batches;
    }

    /**
     * Parses a single JSONL manifest entry into BatchMetadata.
     * Format: {"f":"filename","t0":firstTick,"t1":lastTick,"n":count,"sz":size,"ts":"timestamp"}
     */
    protected BatchMetadata parseManifestLine(String jsonLine, String folder) {
        JsonObject json = GSON.fromJson(jsonLine, JsonObject.class);

        String filename = folder + "/" + json.get("f").getAsString();
        long firstTick = json.get("t0").getAsLong();
        long lastTick = json.get("t1").getAsLong();
        int recordCount = json.get("n").getAsInt();
        long compressedSize = json.get("sz").getAsLong();
        Instant createdAt = Instant.parse(json.get("ts").getAsString());

        return new BatchMetadata(filename, firstTick, lastTick, recordCount, compressedSize, createdAt);
    }

    /**
     * Creates a JSONL manifest entry for a batch.
     */
    protected String createManifestEntry(String filename, long firstTick, long lastTick,
                                        int recordCount, long compressedSize) {
        JsonObject json = new JsonObject();
        json.addProperty("f", filename);
        json.addProperty("t0", firstTick);
        json.addProperty("t1", lastTick);
        json.addProperty("n", recordCount);
        json.addProperty("sz", compressedSize);
        json.addProperty("ts", Instant.now().toString());
        return GSON.toJson(json);
    }

    /**
     * Ensures .storage-metadata.json exists for the simulation.
     * Uses double-checked locking for thread-safe lazy initialization.
     */
    protected void ensureStorageMetadata(String simulationId) throws IOException {
        if (metadataInitialized) {
            return;
        }

        synchronized (metadataLock) {
            if (metadataInitialized) {
                return;
            }

            String metadataPath = simulationId + "/.storage-metadata.json";

            // Check if already exists
            if (exists(metadataPath)) {
                metadataInitialized = true;
                return;
            }

            // Create metadata
            StorageMetadata metadata = new StorageMetadata(
                STORAGE_METADATA_VERSION,
                new StorageMetadata.FolderStructure(folderLevels),
                maxBatchSize,
                compression
            );

            // Serialize to JSON
            JsonObject json = new JsonObject();
            json.addProperty("version", metadata.version);

            JsonObject folderStructure = new JsonObject();
            folderStructure.add("levels", GSON.toJsonTree(metadata.folderStructure.levels));
            json.add("folderStructure", folderStructure);

            json.addProperty("maxBatchSize", metadata.maxBatchSize);

            JsonObject compressionJson = new JsonObject();
            compressionJson.addProperty("codec", metadata.compression.codec);
            compressionJson.addProperty("level", metadata.compression.level);
            json.add("compression", compressionJson);

            String jsonString = GSON.toJson(json);
            writeBytes(metadataPath, jsonString.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            metadataInitialized = true;
            log.info("Created storage metadata for simulation {}", simulationId);
        }
    }

    /**
     * Serializes a batch of TickData to bytes using length-delimited protobuf format.
     */
    protected byte[] serializeBatch(List<TickData> batch) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (TickData tick : batch) {
            tick.writeDelimitedTo(bos);
        }
        return bos.toByteArray();
    }

    /**
     * Deserializes a batch from bytes using length-delimited protobuf format.
     */
    protected List<TickData> deserializeBatch(byte[] data) throws IOException {
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
     * Compresses data according to configured compression codec.
     */
    protected abstract byte[] compressBatch(byte[] data) throws IOException;

    /**
     * Decompresses data based on filename extension or configured codec.
     */
    protected abstract byte[] decompressBatch(byte[] compressedData, String filename) throws IOException;

    /**
     * Returns file extension for compression codec (e.g., "zst" for zstd).
     */
    protected String getCompressionExtension(String codec) {
        return switch (codec.toLowerCase()) {
            case "zstd" -> "zst";
            case "gzip", "gz" -> "gz";
            default -> "";
        };
    }

    /**
     * Reads a text file as list of lines.
     */
    protected List<String> readLines(String path) throws IOException {
        byte[] data = readBytes(path);
        String content = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        return Arrays.asList(content.split("\n"));
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
     * Appends a line to a file (creates if doesn't exist).
     */
    protected abstract void appendLine(String path, String line) throws IOException;

    /**
     * Lists all keys starting with prefix.
     */
    protected abstract List<String> listKeys(String prefix) throws IOException;

    /**
     * Atomically moves a file from src to dest.
     */
    protected abstract void atomicMove(String src, String dest) throws IOException;

    /**
     * Checks if a path exists.
     */
    protected abstract boolean exists(String path) throws IOException;

    /**
     * Deletes a file if it exists (best-effort).
     */
    protected abstract void deleteIfExists(String path) throws IOException;

    // ===== Manifest Cache =====

    /**
     * In-memory cache for folder manifests with TTL-based expiration.
     */
    protected static class ManifestCache {
        private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();
        private final int ttlSeconds;

        ManifestCache(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        static class CachedEntry {
            final List<BatchMetadata> batches;
            final Instant cachedAt;

            CachedEntry(List<BatchMetadata> batches) {
                this.batches = Collections.unmodifiableList(new ArrayList<>(batches));
                this.cachedAt = Instant.now();
            }

            boolean isExpired(int ttlSeconds) {
                if (ttlSeconds == 0) {
                    return true; // Cache disabled
                }
                return Instant.now().isAfter(cachedAt.plusSeconds(ttlSeconds));
            }
        }

        List<BatchMetadata> get(String folder) {
            if (ttlSeconds == 0) {
                return null; // Cache disabled
            }

            CachedEntry entry = cache.get(folder);
            if (entry != null && !entry.isExpired(ttlSeconds)) {
                return entry.batches;
            }
            return null;
        }

        void put(String folder, List<BatchMetadata> batches) {
            if (ttlSeconds > 0) {
                cache.put(folder, new CachedEntry(batches));
            }
        }

        void invalidate(String folder) {
            cache.remove(folder);
        }
    }
}
