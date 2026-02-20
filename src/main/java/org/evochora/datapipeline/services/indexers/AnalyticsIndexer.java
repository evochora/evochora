package org.evochora.datapipeline.services.indexers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.IAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.utils.delta.DeltaCodec;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.IAnalyticsStorageWrite;
import org.evochora.datapipeline.utils.MetadataConfigHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;

/**
 * Indexer service for generating analytics artifacts in Parquet format.
 * <p>
 * <strong>Architecture:</strong> The indexer owns all I/O logic (DuckDB, Parquet, storage).
 * Plugins only define schemas and extract row data, keeping them simple and focused
 * on domain logic.
 * <p>
 * <strong>Streaming Session Lifecycle:</strong>
 * <ol>
 *   <li><strong>Lazy init</strong> ({@link #processChunk}): On first chunk, a DuckDB
 *       in-memory connection is created with tables and PreparedStatements for all
 *       plugin/LOD combinations.</li>
 *   <li><strong>Accumulate</strong> ({@link #processChunk}): Each chunk's ticks are
 *       fed to matching plugin/LOD tasks via {@code extractRows()}, rows are added
 *       to DuckDB table batches. Tick range is tracked for Parquet filenames.</li>
 *   <li><strong>Commit + reset</strong> ({@link #commitProcessedChunks}): After
 *       {@code insertBatchSize} chunks (or on timeout/shutdown), all DuckDB batches
 *       are executed, exported to ZSTD-compressed Parquet files, streamed to analytics
 *       storage, and the DuckDB session is closed and reset for the next window.</li>
 * </ol>
 * <p>
 * <strong>Error Handling:</strong> Uses bulkhead pattern — plugin failures don't affect
 * other plugins. IOException from storage causes batch retry.
 */
public class AnalyticsIndexer<ACK> extends AbstractBatchIndexer<ACK> implements IMemoryEstimatable {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsIndexer.class);
    
    /** Default hierarchy divisors: [100M, 100K] creates 000/000/ structure */
    private static final List<Long> DEFAULT_FOLDER_HIERARCHY = List.of(100_000_000L, 100_000L);

    private final IAnalyticsStorageWrite analyticsOutput;
    private final List<IAnalyticsPlugin> plugins = new ArrayList<>();
    private final Path tempDirectory;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** Hierarchical folder divisors for organizing Parquet files (same as PersistenceService). */
    private final List<Long> folderHierarchyDivisors;
    
    // DuckDB driver loaded flag
    private static volatile boolean duckDbDriverLoaded = false;

    // Metrics
    private final AtomicLong ticksProcessed = new AtomicLong(0);
    private final AtomicLong rowsWritten = new AtomicLong(0);

    // Streaming session state (lazily initialized on first processChunk, reset on commitProcessedChunks)
    private Connection duckDbConn;
    private List<PluginLodTask> sessionTasks;
    private Map<PluginLodTask, Integer> sessionRowsPerTask;
    private Map<IAnalyticsPlugin, List<PluginLodTask>> sessionTasksByPlugin;
    private DeltaCodec.Decoder sessionDecoder;
    private long sessionStartTick = Long.MAX_VALUE;
    private long sessionEndTick = Long.MIN_VALUE;
    
    /**
     * Internal record for tracking plugin processing tasks.
     * Groups a plugin with its LOD configuration and database statement.
     */
    private record PluginLodTask(
        IAnalyticsPlugin plugin,
        String metricId,
        String lodLevel,
        int samplingInterval,
        PreparedStatement statement,
        ParquetSchema schema,
        boolean needsEnvironment
    ) {}

    /**
     * Creates a new AnalyticsIndexer.
     *
     * @param name Service name
     * @param options Configuration options
     * @param resources Resource map (must contain "analyticsOutput")
     */
    public AnalyticsIndexer(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.analyticsOutput = getRequiredResource("analyticsOutput", IAnalyticsStorageWrite.class);
        
        // Configure hierarchical folder structure (same as PersistenceService)
        if (options.hasPath("folderStructure.levels")) {
            this.folderHierarchyDivisors = options.getLongList("folderStructure.levels");
        } else {
            this.folderHierarchyDivisors = DEFAULT_FOLDER_HIERARCHY;
        }
        
        // Configure temp directory
        String tempPathStr = options.hasPath("tempDirectory") 
            ? options.getString("tempDirectory") 
            : System.getProperty("java.io.tmpdir") + "/evochora/analytics/" + name;
        
        this.tempDirectory = Paths.get(tempPathStr);
        
        // Load DuckDB driver once
        loadDuckDbDriver();
        
        // Load plugins
        loadPlugins(options);
    }
    
    /**
     * Loads the DuckDB JDBC driver (thread-safe, idempotent).
     */
    private static synchronized void loadDuckDbDriver() {
        if (!duckDbDriverLoaded) {
            try {
                Class.forName("org.duckdb.DuckDBDriver");
                duckDbDriverLoaded = true;
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("DuckDB driver not found on classpath", e);
            }
        }
    }

    private void loadPlugins(Config options) {
        if (!options.hasPath("plugins")) {
            log.warn("No plugins configured for AnalyticsIndexer '{}'", serviceName);
            return;
        }

        List<? extends ConfigObject> pluginConfigs = options.getObjectList("plugins");
        for (ConfigObject co : pluginConfigs) {
            Config pluginConfig = co.toConfig();
            String className = pluginConfig.getString("className");
            Config pluginOptions = pluginConfig.hasPath("options") 
                ? pluginConfig.getConfig("options") 
                : com.typesafe.config.ConfigFactory.empty();

            try {
                Class<?> clazz = Class.forName(className);
                if (!IAnalyticsPlugin.class.isAssignableFrom(clazz)) {
                    throw new IllegalArgumentException("Class " + className + " does not implement IAnalyticsPlugin");
                }
                
                IAnalyticsPlugin plugin = (IAnalyticsPlugin) clazz.getDeclaredConstructor().newInstance();
                plugin.configure(pluginOptions);
                
                // Validate schema is defined
                ParquetSchema schema = plugin.getSchema();
                if (schema == null) {
                    throw new IllegalArgumentException("Plugin " + className + " returned null schema");
                }
                
                plugins.add(plugin);
                log.debug("Loaded analytics plugin: {} (metricId={}, columns={})", 
                    className, plugin.getMetricId(), schema.getColumnCount());
                
            } catch (Exception e) {
                // Fatal error on startup if config is wrong
                throw new RuntimeException("Failed to load plugin: " + className, e);
            }
        }
    }

    @Override
    protected boolean useStreamingProcessing() {
        return true;
    }

    @Override
    protected Set<ComponentType> getRequiredComponents() {
        return EnumSet.of(ComponentType.METADATA);
    }
    
    @Override
    protected Set<ComponentType> getOptionalComponents() {
        return EnumSet.of(ComponentType.DLQ);
    }

    @Override
    protected void prepareTables(String runId) throws Exception {
        // 1. Startup Cleanup (Resilience)
        cleanupTempDirectory();
        Files.createDirectories(tempDirectory);
        
        // 2. Initialize Plugins
        IAnalyticsContext context = new AnalyticsContextImpl(runId);
        for (IAnalyticsPlugin plugin : plugins) {
            try {
                plugin.initialize(context);
                
                // Write manifest immediately (idempotent)
                writePluginMetadata(runId, plugin);
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize plugin: " + plugin.getMetricId(), e);
            }
        }
        
        log.debug("AnalyticsIndexer prepared for run: {}", runId);
    }

    @Override
    protected void onShutdown() throws Exception {
        // 1. Release DuckDB session if still open (e.g., shutdown before final commit)
        resetSession();

        // 2. Call onFinish() for all plugins
        for (IAnalyticsPlugin plugin : plugins) {
            try {
                plugin.onFinish();
            } catch (Exception e) {
                log.warn("Plugin {} failed during onFinish(): {}",
                    plugin.getMetricId(), e.getMessage());
                log.debug("Plugin onFinish() error details:", e);
            }
        }

        // 3. Clean up temp directory
        cleanupTempDirectory();

        log.debug("AnalyticsIndexer shutdown cleanup completed");
    }

    /**
     * {@inheritDoc}
     * <p>
     * Lazily initializes a DuckDB session on first call, then processes the chunk
     * through all plugin/LOD tasks. Session state persists across calls until
     * {@link #commitProcessedChunks()} exports and resets.
     */
    @Override
    protected void processChunk(TickDataChunk chunk) throws Exception {
        if (plugins.isEmpty()) {
            return;
        }

        // Lazy init: DuckDB connection + tables + PreparedStatements + decoder
        if (duckDbConn == null) {
            initSession();
        }

        // Track tick range for Parquet filename
        sessionStartTick = Math.min(sessionStartTick, chunk.getFirstTick());
        sessionEndTick = Math.max(sessionEndTick, chunk.getLastTick());

        // Process chunk through all plugins
        try {
            processChunkOptimized(chunk, sessionDecoder, sessionTasksByPlugin, sessionRowsPerTask);
        } catch (ChunkCorruptedException e) {
            log.warn("Skipping corrupt chunk: {}", e.getMessage());
            recordError("CORRUPT_CHUNK", e.getMessage(),
                String.format("firstTick=%d, lastTick=%d", chunk.getFirstTick(), chunk.getLastTick()));
        }

        ticksProcessed.addAndGet(chunk.getTickCount());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Executes all accumulated DuckDB batches, exports each plugin/LOD table to
     * Parquet (ZSTD compressed), streams to analytics storage, then closes the
     * DuckDB connection and resets session state.
     */
    @Override
    protected void commitProcessedChunks() throws Exception {
        if (duckDbConn == null || sessionTasks == null) {
            return;
        }

        String runId = getMetadata().getSimulationRunId();
        String subPath = calculateFolderPath(sessionStartTick);
        String filename = String.format("batch_%020d_%020d.parquet", sessionStartTick, sessionEndTick);

        try {
            for (PluginLodTask task : sessionTasks) {
                Path tempFile = null;
                try {
                    int totalRows = sessionRowsPerTask.get(task);
                    if (totalRows == 0) continue;

                    task.statement().executeBatch();

                    tempFile = Files.createTempFile(tempDirectory,
                        task.metricId() + "_" + task.lodLevel() + "_", ".parquet");
                    String exportPath = tempFile.toAbsolutePath().toString().replace("\\", "/");
                    String tableName = task.metricId() + "_" + task.lodLevel();
                    String exportSql = String.format(
                        "COPY %s TO '%s' (FORMAT PARQUET, CODEC 'ZSTD')", tableName, exportPath);

                    try (Statement stmt = duckDbConn.createStatement()) {
                        stmt.execute(exportSql);
                    }

                    try (InputStream in = Files.newInputStream(tempFile);
                         OutputStream out = analyticsOutput.openAnalyticsOutputStream(
                             runId, task.metricId(), task.lodLevel(), subPath, filename)) {
                        in.transferTo(out);
                    }

                    log.debug("Plugin {} wrote {} rows for {} batch {}-{} to {}/{}",
                        task.metricId(), totalRows, task.lodLevel(),
                        sessionStartTick, sessionEndTick, subPath, filename);

                    rowsWritten.addAndGet(totalRows);

                } catch (Exception e) {
                    log.warn("Failed to export plugin {} LOD {}: {}",
                        task.metricId(), task.lodLevel(), e.getMessage());
                    log.debug("Plugin export error details:", e);
                    recordError("ANALYTICS_IO_ERROR", "Failed to write analytics data",
                        String.format("Plugin: %s, LOD: %s", task.metricId(), task.lodLevel()));
                } finally {
                    task.statement().close();
                    if (tempFile != null) Files.deleteIfExists(tempFile);
                }
            }
        } finally {
            resetSession();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Not used — AnalyticsIndexer uses streaming via {@link #processChunk} and
     * {@link #commitProcessedChunks} instead.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    protected void flushChunks(List<TickDataChunk> chunks) throws Exception {
        throw new UnsupportedOperationException(
            "AnalyticsIndexer uses streaming processing, not buffered flushChunks");
    }

    /**
     * Initializes a DuckDB in-memory session with tables and PreparedStatements
     * for all plugin/LOD combinations.
     */
    private void initSession() throws Exception {
        duckDbConn = DriverManager.getConnection("jdbc:duckdb:");
        sessionTasks = new ArrayList<>();
        sessionRowsPerTask = new HashMap<>();

        boolean anyPluginNeedsEnvironment = plugins.stream()
            .anyMatch(IAnalyticsPlugin::needsEnvironmentData);
        sessionDecoder = anyPluginNeedsEnvironment ? createDecoder() : null;

        for (IAnalyticsPlugin plugin : plugins) {
            try {
                int baseSamplingInterval = plugin.getSamplingInterval();
                boolean needsEnv = plugin.needsEnvironmentData();

                for (int level = 0; level < plugin.getLodLevels(); level++) {
                    String lodLevel = "lod" + level;
                    int effectiveSamplingInterval = baseSamplingInterval
                        * (int) Math.pow(plugin.getLodFactor(), level);

                    ParquetSchema schema = plugin.getSchema();
                    String tableName = plugin.getMetricId() + "_" + lodLevel;

                    try (Statement stmt = duckDbConn.createStatement()) {
                        stmt.execute(schema.toCreateTableSql(tableName));
                    }

                    PreparedStatement ps = duckDbConn.prepareStatement(schema.toInsertSql(tableName));
                    PluginLodTask task = new PluginLodTask(plugin, plugin.getMetricId(), lodLevel,
                        effectiveSamplingInterval, ps, schema, needsEnv);
                    sessionTasks.add(task);
                    sessionRowsPerTask.put(task, 0);
                }
            } catch (Exception e) {
                log.warn("Failed to initialize plugin {}, it will be skipped: {}",
                    plugin.getMetricId(), e.getMessage());
                log.debug("Plugin initialization error details:", e);
            }
        }

        sessionTasksByPlugin = sessionTasks.stream()
            .collect(java.util.stream.Collectors.groupingBy(PluginLodTask::plugin));

        sessionStartTick = Long.MAX_VALUE;
        sessionEndTick = Long.MIN_VALUE;
    }

    /**
     * Closes the DuckDB connection and resets all session state.
     */
    private void resetSession() {
        if (duckDbConn != null) {
            try {
                duckDbConn.close();
            } catch (Exception e) {
                log.debug("Failed to close DuckDB connection: {}", e.getMessage());
            }
        }
        duckDbConn = null;
        sessionTasks = null;
        sessionRowsPerTask = null;
        sessionTasksByPlugin = null;
        sessionDecoder = null;
        sessionStartTick = Long.MAX_VALUE;
        sessionEndTick = Long.MIN_VALUE;
    }
    
    /**
     * Processes a single chunk with optimized decompression.
     * <p>
     * <strong>Optimization Strategy:</strong>
     * <ul>
     *   <li>For ticks where no plugin needs environment data: read organisms directly from delta</li>
     *   <li>For ticks where at least one plugin needs environment: decompress using stateful decoder</li>
     *   <li>Decoder reuses state for sequential forward access (no repeated snapshot application)</li>
     * </ul>
     *
     * @param chunk The chunk to process
     * @param decoder The stateful decoder (null if no plugins need environment)
     * @param tasksByPlugin Plugin tasks grouped by plugin
     * @param rowsWrittenPerTask Counter for rows written per task
     */
    private void processChunkOptimized(
            TickDataChunk chunk,
            DeltaCodec.Decoder decoder,
            Map<IAnalyticsPlugin, List<PluginLodTask>> tasksByPlugin,
            Map<PluginLodTask, Integer> rowsWrittenPerTask) throws ChunkCorruptedException {
        
        String runId = chunk.getSimulationRunId();
        
        // Process snapshot first (always has full environment data)
        TickData snapshot = chunk.getSnapshot();
        processTickForPlugins(snapshot, tasksByPlugin, rowsWrittenPerTask);
        
        // Process deltas
        for (TickDelta delta : chunk.getDeltasList()) {
            long tickNumber = delta.getTickNumber();
            
            // Determine if any plugin needs this tick AND needs environment data
            boolean needsEnvironmentForThisTick = false;
            boolean anyPluginNeedsThisTick = false;
            
            for (Map.Entry<IAnalyticsPlugin, List<PluginLodTask>> entry : tasksByPlugin.entrySet()) {
                for (PluginLodTask task : entry.getValue()) {
                    if (tickNumber % task.samplingInterval() == 0) {
                        anyPluginNeedsThisTick = true;
                        if (task.needsEnvironment()) {
                            needsEnvironmentForThisTick = true;
                            break;
                        }
                    }
                }
                if (needsEnvironmentForThisTick) break;
            }
            
            if (!anyPluginNeedsThisTick) {
                continue; // No plugin needs this tick
            }
            
            if (needsEnvironmentForThisTick && decoder != null) {
                // Decompress to get full environment data (uses stateful decoder)
                TickData fullTick = decoder.decompressTick(chunk, tickNumber);
                processTickForPlugins(fullTick, tasksByPlugin, rowsWrittenPerTask);
            } else {
                // Create lightweight TickData with only organism data (no environment reconstruction)
                TickData lightTick = createLightweightTickData(runId, delta);
                processTickForPlugins(lightTick, tasksByPlugin, rowsWrittenPerTask);
            }

            // Yield after each delta to prevent system freezing during heavy chunk processing
            Thread.yield();
        }
    }
    
    /**
     * Creates a lightweight TickData with only organism data (no environment).
     * <p>
     * Used for plugins that don't need environment data, avoiding expensive decompression.
     */
    private TickData createLightweightTickData(String runId, TickDelta delta) {
        return TickData.newBuilder()
                .setSimulationRunId(runId)
                .setTickNumber(delta.getTickNumber())
                .setCaptureTimeMs(delta.getCaptureTimeMs())
                // Note: CellColumns is empty - plugins that need it should declare needsEnvironmentData()
                .addAllOrganisms(delta.getOrganismsList())
                .setTotalOrganismsCreated(delta.getTotalOrganismsCreated())
                .setTotalUniqueGenomes(delta.getTotalUniqueGenomes())
                .setRngState(delta.getRngState())
                .addAllPluginStates(delta.getPluginStatesList())
                .build();
    }
    
    /**
     * Processes a tick for all plugins that need it.
     */
    private void processTickForPlugins(
            TickData tick,
            Map<IAnalyticsPlugin, List<PluginLodTask>> tasksByPlugin,
            Map<PluginLodTask, Integer> rowsWrittenPerTask) {
        
        long tickNumber = tick.getTickNumber();
        
        for (Map.Entry<IAnalyticsPlugin, List<PluginLodTask>> entry : tasksByPlugin.entrySet()) {
            IAnalyticsPlugin plugin = entry.getKey();
            List<PluginLodTask> pluginTasks = entry.getValue();
            
            // Find the finest-grained LOD that matches this tick
            PluginLodTask finestMatchingTask = null;
            for (PluginLodTask task : pluginTasks) {
                if (tickNumber % task.samplingInterval() == 0) {
                    if (finestMatchingTask == null || 
                        task.samplingInterval() < finestMatchingTask.samplingInterval()) {
                        finestMatchingTask = task;
                    }
                }
            }
            
            if (finestMatchingTask == null) continue;
            
            // Extract rows ONCE from the plugin
            try {
                List<Object[]> rows = plugin.extractRows(tick);
                
                // Distribute the same rows to ALL matching LOD levels
                for (PluginLodTask task : pluginTasks) {
                    if (tickNumber % task.samplingInterval() == 0) {
                        for (Object[] row : rows) {
                            bindRow(task.statement(), task.schema(), row);
                            task.statement().addBatch();
                            rowsWrittenPerTask.compute(task, (k, v) -> v + 1);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Plugin {} failed to extract rows for tick {}. Skipping row.", 
                    plugin.getMetricId(), tickNumber);
                recordError("PLUGIN_EXTRACT_ERROR", "Plugin failed during row extraction", 
                    String.format("Plugin: %s, Tick: %d", plugin.getMetricId(), tickNumber));
            }
        }
    }
    
    /**
     * Calculates the hierarchical folder path based on tick number.
     * <p>
     * Uses the same algorithm as PersistenceService for consistency.
     * With default divisors [100M, 100K]:
     * <ul>
     *   <li>Tick 12,345,678 → "000/123"</li>
     *   <li>Tick 123,456,789 → "001/234"</li>
     * </ul>
     *
     * @param tick The tick number to calculate the path for
     * @return Hierarchical path string (e.g., "000/123")
     */
    private String calculateFolderPath(long tick) {
        StringBuilder path = new StringBuilder();
        long remaining = tick;
        
        for (int i = 0; i < folderHierarchyDivisors.size(); i++) {
            long divisor = folderHierarchyDivisors.get(i);
            long bucket = remaining / divisor;
            
            // Format with 3 digits (supports up to 999 per level)
            path.append(String.format("%03d", bucket));
            
            if (i < folderHierarchyDivisors.size() - 1) {
                path.append("/");
            }
            
            remaining %= divisor;
        }
        
        return path.toString();
    }
    
    /**
     * Binds a row of values to a PreparedStatement based on schema types.
     */
    private void bindRow(PreparedStatement ps, ParquetSchema schema, Object[] row) throws Exception {
        List<ParquetSchema.Column> columns = schema.getColumns();
        
        if (row.length != columns.size()) {
            throw new IllegalArgumentException(
                "Row has " + row.length + " values but schema has " + columns.size() + " columns");
        }
        
        for (int i = 0; i < row.length; i++) {
            Object value = row[i];
            ColumnType type = columns.get(i).type();
            int paramIndex = i + 1; // JDBC is 1-indexed
            
            if (value == null) {
                ps.setNull(paramIndex, java.sql.Types.NULL);
                continue;
            }
            
            switch (type) {
                case BIGINT -> ps.setLong(paramIndex, ((Number) value).longValue());
                case INTEGER -> ps.setInt(paramIndex, ((Number) value).intValue());
                case DOUBLE -> ps.setDouble(paramIndex, ((Number) value).doubleValue());
                case VARCHAR -> ps.setString(paramIndex, value.toString());
                case BOOLEAN -> ps.setBoolean(paramIndex, (Boolean) value);
            }
        }
    }
    
    private void cleanupTempDirectory() {
        try {
            if (Files.exists(tempDirectory)) {
                try (var stream = Files.walk(tempDirectory)) {
                     stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                }
                log.debug("Cleaned up temp directory: {}", tempDirectory);
            }
        } catch (IOException e) {
            log.warn("Failed to clean temp directory: {}", e.getMessage());
        }
    }
    
    private void writePluginMetadata(String runId, IAnalyticsPlugin plugin) {
        for (ManifestEntry entry : plugin.getManifestEntries()) {
            try {
                String json = gson.toJson(entry);
                analyticsOutput.writeAnalyticsBlob(
                    runId,
                    entry.id,
                    null,
                    "metadata.json",
                    json.getBytes(StandardCharsets.UTF_8)
                );
            } catch (Exception e) {
                log.warn("Failed to write metadata for plugin {}: {}", plugin.getMetricId(), e.getMessage());
            }
        }
    }

    /**
     * Creates a Decoder for decompressing chunks.
     * <p>
     * The Decoder's MutableCellState is reused across multiple decompressChunk calls
     * within a single batch, avoiding allocation overhead.
     *
     * @return a new Decoder, or a Decoder with size 1 if metadata is unavailable
     */
    private DeltaCodec.Decoder createDecoder() {
        SimulationMetadata metadata = getMetadata();
        if (metadata == null || metadata.getResolvedConfigJson().isEmpty()) {
            log.debug("No environment metadata available, using minimal Decoder");
            return new DeltaCodec.Decoder(1);
        }
        long totalCellsLong = 1L;
        for (int dim : MetadataConfigHelper.getEnvironmentShape(metadata)) {
            totalCellsLong *= dim;
        }
        // DeltaCodec.Decoder uses int arrays, so worlds > 2.1B cells are not supported
        if (totalCellsLong > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                "World too large for delta decoding: " + totalCellsLong + " cells exceeds Integer.MAX_VALUE. " +
                "Reduce environment dimensions.");
        }
        return new DeltaCodec.Decoder((int) totalCellsLong);
    }

    /**
     * Context implementation passed to plugins.
     */
    private class AnalyticsContextImpl implements IAnalyticsContext {
        private final String runId;

        public AnalyticsContextImpl(String runId) {
            this.runId = runId;
        }

        @Override
        public SimulationMetadata getMetadata() {
            return AnalyticsIndexer.this.getMetadata(); 
        }

        @Override
        public String getRunId() {
            return runId;
        }

        @Override
        public OutputStream openArtifactStream(String metricId, String lodLevel, String filename) throws IOException {
            return analyticsOutput.openAnalyticsOutputStream(runId, metricId, lodLevel, filename);
        }

        @Override
        public Path getTempDirectory() {
            return AnalyticsIndexer.this.tempDirectory;
        }
    }
    
    // ==================== IMemoryEstimatable ====================
    
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        List<MemoryEstimate> estimates = new ArrayList<>();

        // 1. Streaming: one parsed chunk at a time
        long bytesPerChunk = params.estimateBytesPerChunk();
        estimates.add(new MemoryEstimate(
            serviceName,
            bytesPerChunk,
            String.format("1 parsed chunk ≤ %s (streaming, no buffering)",
                SimulationParameters.formatBytes(bytesPerChunk)),
            MemoryEstimate.Category.SERVICE_BATCH
        ));

        // 2. DuckDB in-memory tables: rows accumulate across insertBatchSize chunks
        int insertBatchSize = getInsertBatchSize();
        int samplesPerChunk = params.samplesPerChunk();
        long simulationTicksPerChunk = params.simulationTicksPerChunk();
        long duckDbTableBytes = 0;
        int totalTasks = 0;

        for (IAnalyticsPlugin plugin : plugins) {
            int baseSamplingInterval = plugin.getSamplingInterval();
            long bytesPerRow = estimateRowBytes(plugin.getSchema());

            for (int level = 0; level < plugin.getLodLevels(); level++) {
                int effectiveInterval = baseSamplingInterval
                    * (int) Math.pow(plugin.getLodFactor(), level);
                // Matching ticks per chunk: upper bound (not all simulation ticks are data samples)
                long matchingTicksPerChunk = Math.min(
                    Math.max(1, simulationTicksPerChunk / effectiveInterval),
                    samplesPerChunk);
                long rowsAccumulated = (long) insertBatchSize * matchingTicksPerChunk;
                duckDbTableBytes += rowsAccumulated * bytesPerRow;
                totalTasks++;
            }
        }

        // DuckDB engine overhead: connection state + per-table metadata
        long duckDbEngineOverhead = 2L * 1024 * 1024 + (long) totalTasks * 64 * 1024;
        long totalDuckDbBytes = duckDbTableBytes + duckDbEngineOverhead;

        estimates.add(new MemoryEstimate(
            serviceName + " (DuckDB)",
            totalDuckDbBytes,
            String.format("DuckDB tables: %d chunks × %d samples/chunk across %d plugin/LOD tasks + engine overhead",
                insertBatchSize, samplesPerChunk, totalTasks),
            MemoryEstimate.Category.SERVICE_BATCH
        ));

        // 3. MutableCellState for delta decompression (2 int[] arrays: moleculeData + ownerIds)
        long envTotalCells = params.totalCells();
        long mutableCellStateBytes = envTotalCells * 8L;  // 2 int arrays × 4 bytes = 8 bytes/cell
        estimates.add(new MemoryEstimate(
            serviceName + " (decompression)",
            mutableCellStateBytes,
            String.format("MutableCellState: %d cells × 8 bytes (2 int[] arrays)", envTotalCells),
            MemoryEstimate.Category.SERVICE_BATCH
        ));

        // 4. Plugin-specific internal state estimates
        for (IAnalyticsPlugin plugin : plugins) {
            estimates.addAll(plugin.estimateWorstCaseMemory(params));
        }

        return estimates;
    }

    /**
     * Estimates in-memory bytes per row for a DuckDB table based on column types.
     *
     * @param schema The Parquet schema defining the table columns
     * @return Estimated bytes per row including per-row overhead
     */
    private long estimateRowBytes(ParquetSchema schema) {
        long bytes = 8; // per-row overhead (null mask, internal metadata)
        for (ParquetSchema.Column col : schema.getColumns()) {
            bytes += switch (col.type()) {
                case BIGINT -> 8;
                case INTEGER -> 4;
                case DOUBLE -> 8;
                case VARCHAR -> 64;
                case BOOLEAN -> 1;
            };
        }
        return bytes;
    }
}
