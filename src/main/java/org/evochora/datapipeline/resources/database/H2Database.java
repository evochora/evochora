package org.evochora.datapipeline.resources.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.resources.database.h2.IH2EnvStorageStrategy;
import org.evochora.datapipeline.resources.database.h2.IH2OrgStorageStrategy;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.protobuf.ProtobufConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * H2 database implementation using HikariCP for connection pooling.
 * <p>
 * Implements {@link AutoCloseable} to ensure proper cleanup of database connections
 * and connection pool resources during shutdown.
 */
public class H2Database extends AbstractDatabaseResource 
        implements IDatabaseReaderProvider, IMemoryEstimatable {

    private static final Logger log = LoggerFactory.getLogger(H2Database.class);
    
    private final HikariDataSource dataSource;
    private final AtomicLong diskWrites = new AtomicLong(0);
    private final SlidingWindowCounter diskWritesCounter;
    
    // Environment storage strategy (loaded via reflection)
    private final IH2EnvStorageStrategy envStorageStrategy;
    
    // Organism storage strategy (loaded via reflection)
    private final IH2OrgStorageStrategy orgStorageStrategy;
    
    // PreparedStatement caches for organism writes (per connection)
    private final Map<Connection, PreparedStatement> orgStaticStmtCache = new ConcurrentHashMap<>();
    private final Map<Connection, PreparedStatement> orgStatesStmtCache = new ConcurrentHashMap<>();
    
    // Metadata cache (LRU with automatic eviction)
    private final Map<String, SimulationMetadata> metadataCache;
    private final int maxCacheSize;

    public H2Database(String name, Config options) {
        super(name, options);

        final String jdbcUrl = getJdbcUrl(options);
        final String username = options.hasPath("username") ? options.getString("username") : "sa";
        final String password = options.hasPath("password") ? options.getString("password") : "";

        // Validate database directory exists BEFORE attempting to connect
        // This prevents H2 from printing stack traces when the filesystem is not mounted
        validateDatabasePath(name, jdbcUrl);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setDriverClassName("org.h2.Driver"); // Explicitly set driver for Fat JAR compatibility
        hikariConfig.setMaximumPoolSize(options.hasPath("maxPoolSize") ? options.getInt("maxPoolSize") : 10);
        hikariConfig.setMinimumIdle(options.hasPath("minIdle") ? options.getInt("minIdle") : 2);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        
        // Set pool name to resource name for better logging
        hikariConfig.setPoolName(name);
        
        try {
            this.dataSource = new HikariDataSource(hikariConfig);
            log.debug("H2 database '{}' connection pool started (max={}, minIdle={})", 
                name, hikariConfig.getMaximumPoolSize(), hikariConfig.getMinimumIdle());
        } catch (Exception e) {
            // Unwrap to find root cause
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            
            String causeMsg = cause.getMessage() != null ? cause.getMessage() : "";
            
            // Known error: database already in use (file locked)
            if (causeMsg.contains("already in use") || causeMsg.contains("file is locked")) {
                // Extract database file path from JDBC URL for helpful message
                String dbFilePath = jdbcUrl.replace("jdbc:h2:", "");
                String errorMsg = String.format(
                    "Cannot open H2 database '%s': file already in use by another process. File: %s.mv.db. Solutions: (1) Stop other instances: ps aux | grep evochora, (2) Kill stale processes, (3) Remove lock files if no other process running",
                    name, dbFilePath);
                log.error(errorMsg);
                throw new RuntimeException(errorMsg, e);
            }
            
            // Known error: wrong credentials
            if (causeMsg.contains("Wrong user name or password")) {
                String errorMsg = String.format("Failed to connect to H2 database '%s': Wrong username/password. URL=%s, User=%s, Password=%s. Hint: Delete database files or use original credentials.", 
                    name, jdbcUrl, username.isEmpty() ? "(empty)" : username, password.isEmpty() ? "(empty)" : "***");
                log.error(errorMsg);
                throw new RuntimeException(errorMsg, e);
            }
            
            // Unknown error - provide helpful message
            String errorMsg = String.format("Failed to initialize H2 database '%s': %s. Database: %s. Error: %s",
                name, cause.getClass().getSimpleName(), jdbcUrl, causeMsg);
            log.error(errorMsg);
            throw new RuntimeException(errorMsg, e);
        }
        
        // Configuration: metricsWindowSeconds (default: 5)
        int metricsWindowSeconds = options.hasPath("metricsWindowSeconds")
            ? options.getInt("metricsWindowSeconds")
            : 5;
        
        this.diskWritesCounter = new SlidingWindowCounter(metricsWindowSeconds);
        
        // Load environment storage strategy via reflection
        this.envStorageStrategy = loadEnvironmentStorageStrategy(options);
        
        // Load organism storage strategy via reflection
        this.orgStorageStrategy = loadOrganismStorageStrategy(options);
        
        // Initialize metadata cache
        this.maxCacheSize = options.hasPath("metadataCacheSize") 
            ? options.getInt("metadataCacheSize") 
            : 100;
        this.metadataCache = Collections.synchronizedMap(
            new LinkedHashMap<String, SimulationMetadata>(maxCacheSize, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SimulationMetadata> eldest) {
                    return size() > maxCacheSize;
                }
            }
        );
    }

    /**
     * Loads environment storage strategy via reflection.
     * <p>
     * Configuration:
     * <pre>
     * h2EnvironmentStrategy {
     *   className = "org.evochora.datapipeline.resources.database.h2.RowPerChunkStrategy"
     *   options {
     *     compression {
     *       codec = "zstd"
     *       level = 3
     *     }
     *   }
     * }
     * </pre>
     * 
     * @param options Database configuration
     * @return Loaded strategy instance
     */
    private IH2EnvStorageStrategy loadEnvironmentStorageStrategy(Config options) {
        if (options.hasPath("h2EnvironmentStrategy")) {
            Config strategyConfig = options.getConfig("h2EnvironmentStrategy");
            String strategyClassName = strategyConfig.getString("className");
            
            // Extract options for strategy (defaults to empty config if missing)
            Config strategyOptions = strategyConfig.hasPath("options")
                ? strategyConfig.getConfig("options")
                : ConfigFactory.empty();
            
            IH2EnvStorageStrategy strategy = createStorageStrategy(strategyClassName, strategyOptions);
            log.debug("Loaded environment storage strategy: {} with options: {}", 
                     strategyClassName, strategyOptions.hasPath("compression.codec") 
                         ? strategyOptions.getString("compression.codec") 
                         : "none");
            return strategy;
        } else {
            // Default: RowPerChunkStrategy without compression
            Config emptyConfig = ConfigFactory.empty();
            IH2EnvStorageStrategy strategy = createStorageStrategy(
                "org.evochora.datapipeline.resources.database.h2.RowPerChunkStrategy",
                emptyConfig
            );
            log.debug("Using default RowPerChunkStrategy (no compression)");
            return strategy;
        }
    }
    
    /**
     * Loads organism storage strategy via reflection.
     * <p>
     * Configuration:
     * <pre>
     * h2OrganismStrategy {
     *   className = "org.evochora.datapipeline.resources.database.h2.SingleBlobOrgStrategy"
     *   options {
     *     compression {
     *       codec = "zstd"
     *       level = 3
     *     }
     *   }
     * }
     * </pre>
     * 
     * @param options Database configuration
     * @return Loaded strategy instance
     */
    private IH2OrgStorageStrategy loadOrganismStorageStrategy(Config options) {
        if (options.hasPath("h2OrganismStrategy")) {
            Config strategyConfig = options.getConfig("h2OrganismStrategy");
            String strategyClassName = strategyConfig.getString("className");
            
            // Extract options for strategy (defaults to empty config if missing)
            Config strategyOptions = strategyConfig.hasPath("options")
                ? strategyConfig.getConfig("options")
                : ConfigFactory.empty();
            
            IH2OrgStorageStrategy strategy = createOrgStorageStrategy(strategyClassName, strategyOptions);
            log.debug("Loaded organism storage strategy: {} with options: {}", 
                     strategyClassName, strategyOptions.hasPath("compression.codec") 
                         ? strategyOptions.getString("compression.codec") 
                         : "none");
            return strategy;
        } else {
            // Default: SingleBlobOrgStrategy without compression
            Config emptyConfig = ConfigFactory.empty();
            IH2OrgStorageStrategy strategy = createOrgStorageStrategy(
                "org.evochora.datapipeline.resources.database.h2.SingleBlobOrgStrategy",
                emptyConfig
            );
            log.debug("Using default SingleBlobOrgStrategy (no compression)");
            return strategy;
        }
    }
    
    /**
     * Creates organism storage strategy instance via reflection.
     * 
     * @param className Fully qualified strategy class name
     * @param strategyConfig Configuration for strategy
     * @return Strategy instance
     */
    private IH2OrgStorageStrategy createOrgStorageStrategy(String className, Config strategyConfig) {
        try {
            Class<?> strategyClass = Class.forName(className);
            return (IH2OrgStorageStrategy) strategyClass
                .getDeclaredConstructor(Config.class)
                .newInstance(strategyConfig);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                "Organism storage strategy class not found: " + className, e);
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(
                "Organism storage strategy class must implement IH2OrgStorageStrategy: " + className, e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                "Organism storage strategy must have public constructor(Config): " + className, e);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to instantiate organism storage strategy: " + className, e);
        }
    }

    /**
     * Creates storage strategy instance via reflection.
     * <p>
     * Enforces constructor contract: Strategy MUST have public constructor(Config).
     * This is guaranteed by AbstractH2EnvStorageStrategy base class.
     * 
     * @param className Fully qualified strategy class name
     * @param strategyConfig Configuration for strategy
     * @return Strategy instance
     * @throws IllegalArgumentException if class not found, wrong type, or missing constructor
     */
    private IH2EnvStorageStrategy createStorageStrategy(String className, Config strategyConfig) {
        try {
            Class<?> strategyClass = Class.forName(className);
            
            // Try constructor with Config parameter (enforced by AbstractH2EnvStorageStrategy)
            return (IH2EnvStorageStrategy) strategyClass
                .getDeclaredConstructor(Config.class)
                .newInstance(strategyConfig);
            
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                "Storage strategy class not found: " + className + 
                ". Make sure the class exists and is in the classpath.", e);
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(
                "Storage strategy class must implement IH2EnvStorageStrategy: " + className, e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                "Storage strategy must have public constructor(Config): " + className + 
                ". Extend AbstractH2EnvStorageStrategy to satisfy this contract.", e);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to instantiate storage strategy: " + className + 
                ". Error: " + e.getMessage(), e);
        }
    }

    private String getJdbcUrl(Config options) {
        if (!options.hasPath("jdbcUrl")) {
            throw new IllegalArgumentException("'jdbcUrl' must be configured for H2Database.");
        }
        return options.getString("jdbcUrl");
    }

    @Override
    protected Object acquireDedicatedConnection() throws Exception {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        return conn;
    }

    @Override
    protected boolean isConnectionClosed(Object connection) {
        if (connection instanceof Connection) {
            try {
                return ((Connection) connection).isClosed();
            } catch (SQLException e) {
                return true; // Assume closed on error
            }
        }
        return true;
    }

    @Override
    protected void closeConnection(Object connection) throws Exception {
        if (connection instanceof Connection) {
            ((Connection) connection).close();
        }
    }


    @Override
    protected void doSetSchema(Object connection, String runId) throws Exception {
        H2SchemaUtil.setSchema((Connection) connection, runId);
    }

    @Override
    protected void doCreateSchema(Object connection, String runId) throws Exception {
        // Use setupRunSchema with empty callback to create schema without setting it up
        // This allows us to use the public API even though createSchemaIfNotExists is package-private
        H2SchemaUtil.setupRunSchema((Connection) connection, runId, (conn, schemaName) -> {
            // Empty - we only want schema creation, not table setup
            // Tables are created later in doInsertMetadata()
        });
    }

    @Override
    protected void doInsertMetadata(Object connection, SimulationMetadata metadata) throws Exception {
        Connection conn = (Connection) connection;
        try {
            // Use H2SchemaUtil for CREATE TABLE to handle concurrent initialization race conditions
            H2SchemaUtil.executeDdlIfNotExists(
                conn.createStatement(),
                "CREATE TABLE IF NOT EXISTS metadata (\"key\" VARCHAR PRIMARY KEY, \"value\" TEXT)",
                "metadata"
            );
            
            Gson gson = new Gson();
            Map<String, String> kvPairs = new HashMap<>();
            
            // Environment: Use ProtobufConverter (direct Protobuf → JSON, fastest)
            kvPairs.put("environment", ProtobufConverter.toJson(metadata.getEnvironment()));

            // Simulation info: Use GSON (no Protobuf message available, safer than String.format)
            Map<String, Object> simInfoMap = Map.of(
                "runId", metadata.getSimulationRunId(),
                "startTime", metadata.getStartTimeMs(),
                "seed", metadata.getInitialSeed(),
                "samplingInterval", metadata.getSamplingInterval()
            );
            kvPairs.put("simulation_info", gson.toJson(simInfoMap));

            // Full metadata backup: Complete JSON for future extensibility without re-indexing
            kvPairs.put("full_metadata", ProtobufConverter.toJson(metadata));

            PreparedStatement stmt = conn.prepareStatement("MERGE INTO metadata (\"key\", \"value\") KEY(\"key\") VALUES (?, ?)");
            for (Map.Entry<String, String> entry : kvPairs.entrySet()) {
                stmt.setString(1, entry.getKey());
                stmt.setString(2, entry.getValue());
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
            rowsInserted.addAndGet(kvPairs.size());
            queriesExecuted.incrementAndGet();
            diskWrites.incrementAndGet();
            diskWritesCounter.recordCount();  // O(1) recording
        } catch (SQLException e) {
            // Rollback to keep connection clean for pool reuse
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                log.warn("Rollback failed (connection may be closed): {}", rollbackEx.getMessage());
            }
            throw e;  // Re-throw for wrapper to handle (wrapper will log + recordError)
        }
    }

    // ========================================================================
    // IEnvironmentDataWriter Capability
    // ========================================================================

    /**
     * Implements environment_ticks table creation via storage strategy.
     * <p>
     * Delegates to {@link IH2EnvStorageStrategy#createTables(Connection, int)}.
     * Strategy creates tables using idempotent CREATE TABLE IF NOT EXISTS.
     */
    @Override
    protected void doCreateEnvironmentDataTable(Object connection, int dimensions) throws Exception {
        Connection conn = (Connection) connection;
        
        // Delegate to storage strategy
        envStorageStrategy.createTables(conn, dimensions);
        
        // Commit transaction
        conn.commit();
    }

    /**
     * Implements environment chunks write via storage strategy.
     * <p>
     * Delegates to {@link IH2EnvStorageStrategy#writeChunks(Connection, List)}.
     * Strategy performs SQL operations, this method handles transaction lifecycle.
     * <p>
     * Chunks are stored as-is without decompression for maximum storage efficiency.
     */
    @Override
    protected void doWriteEnvironmentChunks(Object connection, 
                                            List<org.evochora.datapipeline.api.contracts.TickDataChunk> chunks) throws Exception {
        Connection conn = (Connection) connection;
        
        // Clear interrupt flag temporarily to allow H2 operations
        // H2 Database's internal locking mechanism (MVMap.tryLock()) uses Thread.sleep()
        // which throws InterruptedException if thread is interrupted
        boolean wasInterrupted = Thread.interrupted();
        
        try {
            // Delegate to storage strategy for SQL operations
            envStorageStrategy.writeChunks(conn, chunks);
            
            // Commit transaction on success
            conn.commit();
            
        } catch (SQLException e) {
            // Rollback transaction on failure to keep connection clean
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                log.warn("Rollback failed (connection may be closed): {}", rollbackEx.getMessage());
            }
            throw e;
        } finally {
            // Restore interrupt flag for proper shutdown handling
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ========================================================================
    // IOrganismDataWriter Capability
    // ========================================================================

    /**
     * Creates organism tables using the configured storage strategy.
     * <p>
     * Delegates to {@link IH2OrgStorageStrategy#createTables(Connection)} for
     * strategy-specific table creation (BLOB vs row-per-organism).
     */
    @Override
    protected void doCreateOrganismTables(Object connection) throws Exception {
        Connection conn = (Connection) connection;
        orgStorageStrategy.createTables(conn);
        conn.commit();
    }

    /**
     * Writes organism static and per-tick state using the configured storage strategy.
     * <p>
     * Delegates to {@link IH2OrgStorageStrategy} for strategy-specific write logic
     * (BLOB per tick vs row per organism per tick).
     */
    @Override
    protected void doWriteOrganismStates(Object connection, List<TickData> ticks) throws Exception {
        Connection conn = (Connection) connection;

        if (ticks.isEmpty()) {
            return;
        }

        // Note: Cached statements are intentionally NOT closed in finally block.
        // Their lifecycle is tied to the connection - HikariCP closes all open
        // statements when the connection is returned to the pool.
        try {
            // Get or create cached PreparedStatements for this connection
            PreparedStatement organismsStmt = orgStaticStmtCache.computeIfAbsent(conn, c -> {
                try {
                    return c.prepareStatement(orgStorageStrategy.getOrganismsMergeSql());
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to prepare organisms statement", e);
                }
            });
            
            PreparedStatement statesStmt = orgStatesStmtCache.computeIfAbsent(conn, c -> {
                try {
                    return c.prepareStatement(orgStorageStrategy.getStatesMergeSql());
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to prepare organism states statement", e);
                }
            });
            
            // Delegate to strategy
            orgStorageStrategy.writeOrganisms(conn, organismsStmt, ticks);
            orgStorageStrategy.writeStates(conn, statesStmt, ticks);
            
            conn.commit();

        } catch (Exception e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                log.warn("Rollback failed during organism state write: {}", rollbackEx.getMessage());
            }
            if (e instanceof SQLException) {
                throw (SQLException) e;
            }
            throw new SQLException("Failed to write organism states", e);
        }
    }

    // ========================================================================
    // IMetadataReader Capability
    // ========================================================================

    /**
     * Implements {@link org.evochora.datapipeline.api.resources.database.IMetadataReader#getMetadata(String)}.
     * Queries metadata table in current schema and deserializes from JSON.
     */
    @Override
    protected SimulationMetadata doGetMetadata(Object connection, String simulationRunId) throws Exception {
        Connection conn = (Connection) connection;
        
        try {
            // Query metadata table (schema already set by ensureConnection)
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT \"value\" FROM metadata WHERE \"key\" = ?"
            );
            stmt.setString(1, "full_metadata");
            ResultSet rs = stmt.executeQuery();
            
            queriesExecuted.incrementAndGet();
            
            if (!rs.next()) {
                throw new org.evochora.datapipeline.api.resources.database.MetadataNotFoundException(
                    "Metadata not found for run: " + simulationRunId
                );
            }
            
            String json = rs.getString("value");
            SimulationMetadata metadata = ProtobufConverter.fromJson(json, SimulationMetadata.class);
            
            return metadata;
            
        } catch (SQLException e) {
            // Table doesn't exist yet (MetadataIndexer hasn't run or is still running)
            if (e.getErrorCode() == 42104 || e.getErrorCode() == 42102 || (e.getMessage().contains("Table") && e.getMessage().contains("not found"))) {
                throw new org.evochora.datapipeline.api.resources.database.MetadataNotFoundException(
                    "Metadata table not yet created for run: " + simulationRunId
                );
            }
            throw e; // Other SQL errors
        }
    }

    /**
     * Implements {@link org.evochora.datapipeline.api.resources.database.IMetadataReader#hasMetadata(String)}.
     * Checks if metadata exists via COUNT query.
     */
    @Override
    protected boolean doHasMetadata(Object connection, String simulationRunId) throws Exception {
        Connection conn = (Connection) connection;
        
        try {
            // Query metadata existence (schema already set by ensureConnection)
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT COUNT(*) as cnt FROM metadata WHERE \"key\" = ?"
            );
            stmt.setString(1, "full_metadata");
            ResultSet rs = stmt.executeQuery();
            
            queriesExecuted.incrementAndGet();
            
            return rs.next() && rs.getInt("cnt") > 0;
            
        } catch (SQLException e) {
            // Table doesn't exist yet - metadata not available
            if (e.getErrorCode() == 42104 || e.getMessage().contains("Table") && e.getMessage().contains("not found")) {
                return false;
            }
            throw e; // Other SQL errors
        }
    }

    /**
     * Implements {@link org.evochora.datapipeline.api.resources.database.IMetadataReader#getRunIdInCurrentSchema()}.
     * Extracts simulation run ID from metadata table in current schema.
     */
    @Override
    protected String doGetRunIdInCurrentSchema(Object connection) throws Exception {
        Connection conn = (Connection) connection;
        
        try {
            // Query 'simulation_info' (small, indexed key-value - much faster than 'full_metadata')
            // This key is written by doInsertMetadata() with runId, startTime, seed, samplingInterval
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT \"value\" FROM metadata WHERE \"key\" = ?"
            );
            stmt.setString(1, "simulation_info");
            ResultSet rs = stmt.executeQuery();
            
            queriesExecuted.incrementAndGet();
            
            if (!rs.next()) {
                throw new org.evochora.datapipeline.api.resources.database.MetadataNotFoundException(
                    "Metadata not found in current schema"
                );
            }
            
            // Parse small JSON (~100 bytes) with Gson (type-safe with POJO)
            String json = rs.getString("value");
            Gson gson = new Gson();
            SimulationInfo simInfo = gson.fromJson(json, SimulationInfo.class);
            
            if (simInfo.runId == null || simInfo.runId.isEmpty()) {
                throw new org.evochora.datapipeline.api.resources.database.MetadataNotFoundException(
                    "Metadata exists but runId field is missing or empty"
                );
            }
            
            return simInfo.runId;
            
        } catch (SQLException e) {
            // Table doesn't exist yet (MetadataIndexer hasn't run)
            if (e.getErrorCode() == 42104 || e.getErrorCode() == 42102 || 
                (e.getMessage().contains("Table") && e.getMessage().contains("not found"))) {
                throw new org.evochora.datapipeline.api.resources.database.MetadataNotFoundException(
                    "Metadata table not yet created in current schema"
                );
            }
            throw e; // Other SQL errors
        }
    }
    
    /**
     * POJO for deserializing 'simulation_info' metadata key.
     * Matches structure written in doInsertMetadata().
     */
    private static class SimulationInfo {
        String runId;
        @SuppressWarnings("unused")
        long startTime;
        @SuppressWarnings("unused")
        long seed;
        @SuppressWarnings("unused")
        int samplingInterval;
    }

    /**
     * Adds H2-specific metrics via Template Method Pattern hook.
     * <p>
     * Includes:
     * <ul>
     *   <li>HikariCP connection pool metrics (O(1) via MXBean)</li>
     *   <li>Disk write rate (O(1) via SlidingWindowCounter)</li>
     *   <li>H2 cache size (fast SQL query in INFORMATION_SCHEMA)</li>
     * </ul>
     * <p>
     * Note: Recording operations (incrementing counters) must be O(1).
     * Reading metrics (this method) can perform fast queries without impacting performance.
     */
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics from AbstractDatabaseResource
        
        // Disk write rate (O(1) via SlidingWindowCounter)
        metrics.put("h2_disk_writes_per_sec", diskWritesCounter.getRate());
        
        if (dataSource != null && !dataSource.isClosed()) {
            // HikariCP connection pool metrics (O(1) via MXBean - instant reads)
            metrics.put("h2_pool_active_connections", dataSource.getHikariPoolMXBean().getActiveConnections());
            metrics.put("h2_pool_idle_connections", dataSource.getHikariPoolMXBean().getIdleConnections());
            metrics.put("h2_pool_total_connections", dataSource.getHikariPoolMXBean().getTotalConnections());
            metrics.put("h2_pool_threads_awaiting", dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection());
        }
        
        // Operating system resource limits (O(1) via MXBean)
        java.lang.management.OperatingSystemMXBean os = 
            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean) {
            com.sun.management.UnixOperatingSystemMXBean unix = 
                (com.sun.management.UnixOperatingSystemMXBean) os;
            long openFDs = unix.getOpenFileDescriptorCount();
            long maxFDs = unix.getMaxFileDescriptorCount();
            
            metrics.put("os_open_file_descriptors", openFDs);
            metrics.put("os_max_file_descriptors", maxFDs);
            metrics.put("os_fd_usage_percent", maxFDs > 0 ? (openFDs * 100.0) / maxFDs : 0.0);
        }
        
        // JVM thread metrics (O(1) via MXBean)
        java.lang.management.ThreadMXBean threads = 
            java.lang.management.ManagementFactory.getThreadMXBean();
        metrics.put("jvm_thread_count", threads.getThreadCount());
        metrics.put("jvm_daemon_thread_count", threads.getDaemonThreadCount());
        metrics.put("jvm_peak_thread_count", threads.getPeakThreadCount());
        
        if (dataSource != null && !dataSource.isClosed()) {
            // H2 cache size (fast query in INFORMATION_SCHEMA, acceptable during metrics read)
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE NAME = 'info.CACHE_SIZE'")) {
                
                if (rs.next()) {
                    metrics.put("h2_cache_size_bytes", rs.getLong("VALUE"));
                }
            } catch (SQLException e) {
                // Log but don't fail metrics collection
                log.debug("Failed to query H2 cache size: {}", e.getMessage());
            }
        }
    }

    /**
     * Closes the HikariCP connection pool for this H2 database.
     * <p>
     * This is called by {@link AbstractDatabaseResource#close()} after all wrappers
     * have been closed and connections released back to the pool.
     * <p>
     * <strong>Important:</strong> Before closing the pool, we execute a SQL {@code SHUTDOWN}
     * command to ensure the H2 MVStore flushes all pending writes to disk. This prevents
     * database corruption ("Double mark" errors) that can occur when the database file
     * is not cleanly closed. The issue is that {@code DB_CLOSE_ON_EXIT=FALSE} combined with
     * {@code DB_CLOSE_DELAY=-1} keeps the database open until explicit shutdown, but
     * HikariCP's {@code close()} only releases connections without triggering H2's shutdown.
     */
    @Override
    protected void closeConnectionPool() {
        if (dataSource != null && !dataSource.isClosed()) {
            // Execute SHUTDOWN to ensure H2 MVStore flushes all pages to disk
            // This prevents "Double mark" corruption errors on next startup
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SHUTDOWN");
                log.debug("H2 database '{}' shutdown command executed", getResourceName());
            } catch (SQLException e) {
                // H2 error code 90121 = "Database is already closed"
                // This is expected for in-memory databases without DB_CLOSE_ON_EXIT=FALSE
                // or when all connections have been released, triggering auto-close
                if (e.getErrorCode() == 90121) {
                    log.debug("H2 database '{}' already closed (in-memory or auto-closed)", getResourceName());
                } else {
                    // Unexpected error - log warning but continue with pool close
                    // Corruption is still possible but we've done our best
                    log.warn("H2 database '{}' shutdown command failed: {}",
                        getResourceName(), e.getMessage());
                }
            }

            dataSource.close();
            log.debug("H2 database '{}' connection pool closed", getResourceName());
        }
    }
    
    @Override
    public IDatabaseReader createReader(String runId) throws SQLException {
        try {
            Connection conn = dataSource.getConnection();
            H2SchemaUtil.setSchema(conn, runId);
            return new H2DatabaseReader(conn, this, envStorageStrategy, orgStorageStrategy, runId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create reader for runId: " + runId, e);
        }
    }

    @Override
    public String findLatestRunId() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Step 1: Find latest simulation schema
            String latestSchema;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA " +
                     "WHERE SCHEMA_NAME LIKE 'SIM\\_%' ESCAPE '\\' " +
                     "ORDER BY SCHEMA_NAME DESC " +
                     "LIMIT 1")) {
                if (!rs.next()) {
                    return null;  // No simulation runs found
                }
                latestSchema = rs.getString("SCHEMA_NAME");
            }
            
            // Step 2: Set schema and read run-id (maintains encapsulation)
            conn.createStatement().execute("SET SCHEMA \"" + latestSchema + "\"");
            
            try {
                return doGetRunIdInCurrentSchema(conn);
            } catch (org.evochora.datapipeline.api.resources.database.MetadataNotFoundException e) {
                return null;  // Schema exists but no metadata yet
            } catch (Exception e) {
                throw new SQLException("Failed to read run ID from schema: " + latestSchema, e);
            }
        }
    }

    SimulationMetadata getMetadataInternal(Connection conn, String runId) 
            throws SQLException, org.evochora.datapipeline.api.resources.database.MetadataNotFoundException {
        try {
            return metadataCache.computeIfAbsent(runId, key -> {
                try {
                    return doGetMetadata(conn, key);
                } catch (org.evochora.datapipeline.api.resources.database.MetadataNotFoundException e) {
                    // Re-throw MetadataNotFoundException directly (no wrapping)
                    throw new RuntimeException(e);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to load metadata for runId: " + key, e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof org.evochora.datapipeline.api.resources.database.MetadataNotFoundException) {
                throw (org.evochora.datapipeline.api.resources.database.MetadataNotFoundException) e.getCause();
            }
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
            }
            throw e;
        }
    }

    boolean hasMetadataInternal(Connection conn, String runId) throws SQLException {
        try {
            return doHasMetadata(conn, runId);
        } catch (Exception e) {
            if (e instanceof SQLException) {
                throw (SQLException) e;
            }
            throw new SQLException("Failed to check metadata existence", e);
        }
    }

    /**
     * Gets the range of available ticks for a specific run.
     * <p>
     * Queries the environment_chunks table to find the minimum and maximum tick numbers.
     * Returns null if no chunks are available.
     *
     * @param conn The database connection (schema already set)
     * @param runId The simulation run ID (for logging/debugging, schema already contains this run)
     * @return TickRange with minTick and maxTick, or null if no chunks exist
     * @throws SQLException if database query fails
     */
    org.evochora.datapipeline.api.resources.database.dto.TickRange getTickRangeInternal(
            Connection conn, String runId) throws SQLException {
        try {
            // Query min and max tick numbers from environment_chunks table
            // first_tick is the minimum tick in each chunk, last_tick is the maximum
            // Schema is already set by the connection (via H2DatabaseReader)
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT MIN(first_tick) as min_tick, MAX(last_tick) as max_tick " +
                "FROM environment_chunks"
            );
            ResultSet rs = stmt.executeQuery();
            
            queriesExecuted.incrementAndGet();
            
            if (!rs.next()) {
                // No rows in table
                return null;
            }
            
            // Check if result is null (table exists but empty, or all chunks deleted)
            long minTick = rs.getLong("min_tick");
            long maxTick = rs.getLong("max_tick");
            
            if (rs.wasNull()) {
                // Table exists but is empty
                return null;
            }
            
            return new org.evochora.datapipeline.api.resources.database.dto.TickRange(minTick, maxTick);
            
        } catch (SQLException e) {
            // Table doesn't exist yet (no chunks written)
            if (e.getErrorCode() == 42104 || e.getErrorCode() == 42102 || 
                (e.getMessage().contains("Table") && e.getMessage().contains("not found"))) {
                return null; // No ticks available
            }
            throw e; // Other SQL errors
        }
    }
    
    /**
     * Queries the organism table to find the minimum and maximum tick numbers.
     * Returns null if no ticks exist.
     * <p>
     * Delegates to {@link IH2OrgStorageStrategy#getAvailableTickRange(Connection)} for
     * strategy-specific table query (organism_ticks vs organism_states).
     *
     * @param conn The database connection (schema already set)
     * @param runId The simulation run ID (for logging/debugging, schema already contains this run)
     * @return TickRange with minTick and maxTick, or null if no ticks exist
     * @throws SQLException if database query fails
     */
    org.evochora.datapipeline.api.resources.database.dto.TickRange getOrganismTickRangeInternal(
            Connection conn, String runId) throws SQLException {
        try {
            queriesExecuted.incrementAndGet();
            return orgStorageStrategy.getAvailableTickRange(conn);
        } catch (SQLException e) {
            // Table doesn't exist yet (no ticks written)
            if (e.getErrorCode() == 42104 || e.getErrorCode() == 42102 || 
                (e.getMessage().contains("Table") && e.getMessage().contains("not found"))) {
                return null; // No ticks available
            }
            throw e; // Other SQL errors
        }
    }

    // ========================================================================
    // IMemoryEstimatable Implementation
    // ========================================================================

    /**
     * Estimates worst-case heap memory usage for this H2 database resource.
     * <p>
     * H2 heap components (worst-case estimates):
     * <ul>
     *   <li><b>Connection overhead</b>: ~10 MB per connection (with active query + large ResultSet)</li>
     *   <li><b>MVStore base</b>: ~150 MB (internal data structures, grows with DB size)</li>
     *   <li><b>CACHE_SIZE on-heap</b>: ~30% of CACHE_SIZE (rest is off-heap DirectByteBuffer)</li>
     *   <li><b>Query buffer</b>: ~100 MB (concurrent queries, temporary objects)</li>
     *   <li><b>BLOB decompression</b>: ~50 MB per concurrent read (environment BLOBs)</li>
     * </ul>
     * <p>
     * Note: CACHE_SIZE is primarily off-heap but has on-heap components for page metadata.
     *
     * @param params Simulation parameters (not used for H2 estimation, but required by interface)
     * @return List containing a single memory estimate for this database
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // Read configuration values
        int poolSize = options.hasPath("maxPoolSize") ? options.getInt("maxPoolSize") : 10;
        
        // Parse CACHE_SIZE from JDBC URL (in KB, default 16384 KB = 16 MB)
        long cacheSizeKb = parseCacheSizeFromUrl(getJdbcUrl(options));
        
        // Worst-case heap estimation:
        // 1. Connection overhead: ~10 MB per connection (active query with large ResultSet)
        long connectionOverhead = (long) poolSize * 10 * 1024 * 1024;
        
        // 2. MVStore base overhead: ~150 MB (internal structures, maps, indexes)
        long mvStoreBase = 150L * 1024 * 1024;
        
        // 3. CACHE_SIZE on-heap portion: ~30% of CACHE_SIZE
        // H2 uses DirectByteBuffer for most cache, but metadata is on-heap
        long cacheOnHeap = (cacheSizeKb * 1024 * 3) / 10;
        
        // 4. Query buffer: ~100 MB (concurrent queries, parsing, execution)
        long queryBuffer = 100L * 1024 * 1024;
        
        // 5. BLOB decompression buffer: ~50 MB per concurrent read (assume 4 concurrent HTTP reads)
        long blobBuffer = 4L * 50 * 1024 * 1024;
        
        long totalEstimate = connectionOverhead + mvStoreBase + cacheOnHeap + queryBuffer + blobBuffer;
        
        String description = String.format(
            "%d conn × 10MB + 150MB MVStore + %.0fMB cache-heap (30%% of %dMB) + 100MB query + 200MB BLOB",
            poolSize, 
            (double) cacheOnHeap / (1024 * 1024),
            cacheSizeKb / 1024);
        
        return List.of(new MemoryEstimate(
            getResourceName(),
            totalEstimate,
            description,
            MemoryEstimate.Category.DATABASE
        ));
    }
    
    /**
     * Parses CACHE_SIZE parameter from JDBC URL.
     * <p>
     * CACHE_SIZE is specified in KB in the URL, e.g.:
     * {@code jdbc:h2:...;CACHE_SIZE=262144} means 256 MB cache.
     *
     * @param jdbcUrl The JDBC URL to parse
     * @return Cache size in KB, or 16384 (16 MB) if not found
     */
    private long parseCacheSizeFromUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return 16384; // H2 default: 16 MB
        }
        
        String upperUrl = jdbcUrl.toUpperCase();
        int idx = upperUrl.indexOf("CACHE_SIZE=");
        if (idx < 0) {
            return 16384; // H2 default
        }
        
        // Find the value after CACHE_SIZE=
        int startIdx = idx + "CACHE_SIZE=".length();
        int endIdx = startIdx;
        while (endIdx < jdbcUrl.length() && Character.isDigit(jdbcUrl.charAt(endIdx))) {
            endIdx++;
        }
        
        if (endIdx > startIdx) {
            try {
                return Long.parseLong(jdbcUrl.substring(startIdx, endIdx));
            } catch (NumberFormatException e) {
                log.debug("Failed to parse CACHE_SIZE from URL: {}", jdbcUrl);
            }
        }
        
        return 16384; // H2 default
    }
    
    /**
     * Validates that the database path is accessible before attempting to connect.
     * <p>
     * This prevents H2 from printing stack traces when the filesystem is not mounted
     * or the directory doesn't exist. By checking early, we can provide a clear
     * error message without the noise of H2's internal exception handling.
     *
     * @param name    Resource name for error messages
     * @param jdbcUrl JDBC URL to validate
     * @throws RuntimeException if the database path is not accessible
     */
    private static void validateDatabasePath(String name, String jdbcUrl) {
        // Extract database file path from JDBC URL
        // Example: jdbc:h2:/home/user/data/db;MODE=... -> /home/user/data/db
        // Example: jdbc:h2:file:/tmp/test;MODE=... -> /tmp/test
        if (!jdbcUrl.startsWith("jdbc:h2:")) {
            return; // Not a file-based H2 URL, skip validation
        }
        
        String dbPath = jdbcUrl.substring(8); // Remove "jdbc:h2:"
        
        // Skip validation for in-memory databases
        if (dbPath.startsWith("mem:")) {
            return;
        }
        
        // Remove file: prefix if present
        if (dbPath.startsWith("file:")) {
            dbPath = dbPath.substring(5);
        }
        
        // Remove URL parameters
        int semicolonIndex = dbPath.indexOf(';');
        if (semicolonIndex > 0) {
            dbPath = dbPath.substring(0, semicolonIndex);
        }
        
        // Skip validation for temp directories (used in tests)
        if (dbPath.startsWith("/tmp/") || dbPath.startsWith(System.getProperty("java.io.tmpdir"))) {
            return;
        }
        
        java.io.File dbFile = new java.io.File(dbPath);
        java.io.File parentDir = dbFile.getParentFile();
        
        if (parentDir != null && !parentDir.exists()) {
            // Create entire directory structure if necessary
            if (!parentDir.mkdirs()) {
                throw new RuntimeException(String.format(
                    "Cannot create H2 database '%s': failed to create directory structure: %s",
                    name, parentDir.getAbsolutePath()));
            }
        }
    }
}