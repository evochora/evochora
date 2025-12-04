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
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.IAnalyticsStorageWrite;
import org.evochora.datapipeline.utils.PathExpansion;
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
 * <strong>Processing Flow per Batch:</strong>
 * <ol>
 *   <li>For each plugin: create DuckDB in-memory table from schema</li>
 *   <li>For each tick: call plugin's {@code extractRows()}, insert into table</li>
 *   <li>Export table to Parquet file (ZSTD compressed)</li>
 *   <li>Stream Parquet to storage</li>
 *   <li>Cleanup temporary files</li>
 * </ol>
 * <p>
 * <strong>Error Handling:</strong> Uses bulkhead pattern - plugin failures don't affect
 * other plugins. IOException from storage causes batch retry.
 */
public class AnalyticsIndexer<ACK> extends AbstractBatchIndexer<ACK> implements IMemoryEstimatable {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsIndexer.class);
    private static final String TABLE_NAME = "metrics";
    
    /** Default hierarchy divisors: [100M, 100K] creates 000/000/ structure */
    private static final List<Long> DEFAULT_FOLDER_HIERARCHY = List.of(100_000_000L, 100_000L);

    private final IAnalyticsStorageWrite analyticsOutput;
    private final List<IAnalyticsPlugin> plugins = new ArrayList<>();
    private final Path tempDirectory;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final int insertBatchSize;
    
    /** Hierarchical folder divisors for organizing Parquet files (same as PersistenceService). */
    private final List<Long> folderHierarchyDivisors;
    
    // DuckDB driver loaded flag
    private static volatile boolean duckDbDriverLoaded = false;
    
    // Metrics
    private final AtomicLong ticksProcessed = new AtomicLong(0);
    private final AtomicLong rowsWritten = new AtomicLong(0);

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
        this.insertBatchSize = options.hasPath("insertBatchSize") ? options.getInt("insertBatchSize") : 25;
        
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
        
        String expandedPath = PathExpansion.expandPath(tempPathStr);
        this.tempDirectory = Paths.get(expandedPath);
        
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
                log.info("Loaded analytics plugin: {} (metricId={}, columns={})", 
                    className, plugin.getMetricId(), schema.getColumnCount());
                
            } catch (Exception e) {
                // Fatal error on startup if config is wrong
                throw new RuntimeException("Failed to load plugin: " + className, e);
            }
        }
    }

    @Override
    protected Set<ComponentType> getRequiredComponents() {
        // No BUFFERING: Use batch-passthrough for consistent Parquet file boundaries
        // Each PersistenceService batch = one Parquet file (no mixing across batches)
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
        // 1. Call onFinish() for all plugins
        for (IAnalyticsPlugin plugin : plugins) {
            try {
                plugin.onFinish();
            } catch (Exception e) {
                log.warn("Plugin {} failed during onFinish(): {}", 
                    plugin.getMetricId(), e.getMessage());
                log.debug("Plugin onFinish() error details:", e);
            }
        }
        
        // 2. Clean up temp directory
        cleanupTempDirectory();
        
        log.debug("AnalyticsIndexer shutdown cleanup completed");
    }

    @Override
    protected void flushTicks(List<TickData> ticks) throws Exception {
        if (ticks.isEmpty()) return;
        
        // Calculate actual min/max tick numbers (robust, doesn't assume sorted order)
        long startTick = ticks.stream().mapToLong(TickData::getTickNumber).min().orElse(0);
        long endTick = ticks.stream().mapToLong(TickData::getTickNumber).max().orElse(0);
        String runId = getMetadata().getSimulationRunId();
        
        boolean anyIoError = false;
        
        for (IAnalyticsPlugin plugin : plugins) {
            try {
                processPluginBatch(plugin, ticks, runId, startTick, endTick);
            } catch (RuntimeException e) {
                // Logic error (Bug) -> Bulkhead: Log and continue other plugins
                log.error("Plugin {} failed for batch {}-{} (skipping): {}", 
                    plugin.getMetricId(), startTick, endTick, e.getMessage());
                log.debug("Plugin failure details:", e);
            } catch (IOException e) {
                // IO Error -> Fatal for batch integrity
                log.warn("Plugin {} IO failure: {}", plugin.getMetricId(), e.getMessage());
                anyIoError = true;
            }
        }
        
        ticksProcessed.addAndGet(ticks.size());
        
        if (anyIoError) {
            throw new IOException("One or more plugins failed with IO error. Failing batch.");
        }
    }
    
    /**
     * Processes a batch of ticks for a single plugin using DuckDB.
     * <p>
     * Generates Parquet files for all configured LOD levels:
     * <ul>
     *   <li>lod0: Full resolution (samplingInterval)</li>
     *   <li>lod1: samplingInterval * lodFactor</li>
     *   <li>lod2: samplingInterval * lodFactor^2</li>
     *   <li>etc.</li>
     * </ul>
     * <p>
     * Flow per LOD level: Filter ticks → Create table → Insert rows → Export Parquet → Upload.
     */
    private void processPluginBatch(IAnalyticsPlugin plugin, List<TickData> ticks,
                                    String runId, long startTick, long endTick) throws IOException {
        
        ParquetSchema schema = plugin.getSchema();
        String metricId = plugin.getMetricId();
        int baseSamplingInterval = plugin.getSamplingInterval();
        int lodFactor = plugin.getLodFactor();
        int lodLevels = plugin.getLodLevels();
        
        // Calculate hierarchical folder path based on startTick
        String subPath = calculateFolderPath(startTick);
        String filename = String.format("batch_%020d_%020d.parquet", startTick, endTick);
        
        // Process each LOD level
        for (int level = 0; level < lodLevels; level++) {
            String lodLevel = "lod" + level;
            int effectiveSamplingInterval = baseSamplingInterval * (int) Math.pow(lodFactor, level);
            
            try {
                int rowsWrittenForLevel = processPluginBatchForLod(
                    plugin, schema, ticks, runId, metricId, 
                    lodLevel, subPath, filename, effectiveSamplingInterval,
                    startTick, endTick
                );
                
                if (rowsWrittenForLevel > 0) {
                    rowsWritten.addAndGet(rowsWrittenForLevel);
                }
            } catch (IOException e) {
                // Re-throw IO errors (fatal for batch)
                throw e;
            } catch (Exception e) {
                // Log and continue for other LOD levels (best effort)
                log.warn("Plugin {} failed for LOD {} batch {}-{}: {}", 
                    metricId, lodLevel, startTick, endTick, e.getMessage());
                log.debug("LOD processing failure details:", e);
            }
        }
    }
    
    /**
     * Processes a batch for a single LOD level.
     * 
     * @return Number of rows written, or 0 if no data
     */
    private int processPluginBatchForLod(IAnalyticsPlugin plugin, ParquetSchema schema,
                                          List<TickData> ticks, String runId, String metricId,
                                          String lodLevel, String subPath, String filename,
                                          int samplingInterval, long startTick, long endTick) 
                                          throws IOException {
        Path tempFile = null;
        
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement stmt = conn.createStatement()) {
                
                // 1. Create table from schema
                String createSql = schema.toCreateTableSql(TABLE_NAME);
                stmt.execute(createSql);
                
                // 2. Insert rows from plugin (with sampling)
                String insertSql = schema.toInsertSql(TABLE_NAME);
                int totalRows = 0;
                
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    for (TickData tick : ticks) {
                        // Apply sampling for this LOD level
                        if (tick.getTickNumber() % samplingInterval != 0) {
                            continue;
                        }
                        
                        // Extract rows from plugin
                        List<Object[]> rows = plugin.extractRows(tick);
                        
                        for (Object[] row : rows) {
                            bindRow(ps, schema, row);
                            ps.addBatch();
                            totalRows++;
                        }
                    }
                    
                    if (totalRows > 0) {
                        ps.executeBatch();
                    }
                }
                
                // 3. Skip if no data for this LOD level
                if (totalRows == 0) {
                    log.trace("Plugin {} produced no rows for {} batch {}-{}", 
                        metricId, lodLevel, startTick, endTick);
                    return 0;
                }
                
                // 4. Export to Parquet
                tempFile = Files.createTempFile(tempDirectory, metricId + "_" + lodLevel + "_", ".parquet");
                String exportPath = tempFile.toAbsolutePath().toString().replace("\\", "/");
                String exportSql = String.format(
                    "COPY %s TO '%s' (FORMAT PARQUET, CODEC 'ZSTD')", 
                    TABLE_NAME, exportPath
                );
                stmt.execute(exportSql);
                
                // 5. Stream to storage with hierarchical folder structure
                try (InputStream in = Files.newInputStream(tempFile);
                     OutputStream out = analyticsOutput.openAnalyticsOutputStream(
                         runId, metricId, lodLevel, subPath, filename)) {
                    in.transferTo(out);
                }
                
                log.debug("Plugin {} wrote {} rows for {} batch {}-{} to {}/{}", 
                    metricId, totalRows, lodLevel, startTick, endTick, subPath, filename);
                
                return totalRows;
            }
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new RuntimeException("DuckDB processing failed for plugin " + metricId + " " + lodLevel, e);
        } finally {
            // Cleanup temp file
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.debug("Failed to delete temp file: {}", tempFile);
                }
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
        ManifestEntry entry = plugin.getManifestEntry();
        if (entry == null) return;
        
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
        long bytesPerTick = params.estimateBytesPerTick();
        
        List<MemoryEstimate> estimates = new ArrayList<>();
        
        // Base estimate for DuckDB processing and TickData buffering
        long baseBytes = (long) insertBatchSize * bytesPerTick;
        long wrapperOverhead = (long) insertBatchSize * 200; // TickData wrapper overhead
        long duckDbOverhead = 10 * 1024 * 1024; // DuckDB in-memory overhead
        long totalBaseBytes = baseBytes + wrapperOverhead + duckDbOverhead;
        
        String baseExplanation = String.format("%d insertBatchSize × %s/tick + DuckDB overhead",
            insertBatchSize,
            SimulationParameters.formatBytes(bytesPerTick));
        
        estimates.add(new MemoryEstimate(
            serviceName,
            totalBaseBytes,
            baseExplanation,
            MemoryEstimate.Category.SERVICE_BATCH
        ));

        // Add estimates from each plugin
        for (IAnalyticsPlugin plugin : plugins) {
            estimates.addAll(plugin.estimateWorstCaseMemory(params));
        }
        
        return estimates;
    }
}
