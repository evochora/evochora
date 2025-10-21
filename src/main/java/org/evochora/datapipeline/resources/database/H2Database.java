package org.evochora.datapipeline.resources.database;

import com.google.gson.Gson;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.resources.database.h2.IH2EnvStorageStrategy;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.evochora.datapipeline.utils.PathExpansion;
import org.evochora.datapipeline.utils.protobuf.ProtobufConverter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.runtime.model.EnvironmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H2 database implementation using HikariCP for connection pooling.
 * <p>
 * Implements {@link AutoCloseable} to ensure proper cleanup of database connections
 * and connection pool resources during shutdown.
 */
public class H2Database extends AbstractDatabaseResource implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(H2Database.class);
    private final HikariDataSource dataSource;
    private final AtomicLong diskWrites = new AtomicLong(0);
    private final SlidingWindowCounter diskWritesCounter;
    
    // Environment storage strategy (loaded via reflection)
    private final IH2EnvStorageStrategy envStorageStrategy;
    
    // PreparedStatement cache for environment writes (per connection)
    private final Map<Connection, PreparedStatement> envWriteStmtCache = new ConcurrentHashMap<>();

    public H2Database(String name, Config options) {
        super(name, options);

        final String jdbcUrl = getJdbcUrl(options);
        final String username = options.hasPath("username") ? options.getString("username") : "sa";
        final String password = options.hasPath("password") ? options.getString("password") : "";

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
    }

    /**
     * Loads environment storage strategy via reflection.
     * <p>
     * Configuration:
     * <pre>
     * h2EnvironmentStrategy {
     *   className = "org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy"
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
            // Default: SingleBlobStrategy without compression
            Config emptyConfig = ConfigFactory.empty();
            IH2EnvStorageStrategy strategy = createStorageStrategy(
                "org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy",
                emptyConfig
            );
            log.debug("Using default SingleBlobStrategy (no compression)");
            return strategy;
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
        String jdbcUrl = options.getString("jdbcUrl");
        String expandedUrl = PathExpansion.expandPath(jdbcUrl);
        if (!jdbcUrl.equals(expandedUrl)) {
            log.debug("Expanded jdbcUrl: '{}' -> '{}'", jdbcUrl, expandedUrl);
        }
        return expandedUrl;
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
     * Implements environment cells write via storage strategy.
     * <p>
     * Delegates to {@link IH2EnvStorageStrategy#writeTicks(Connection, PreparedStatement, List, EnvironmentProperties)}.
     * Strategy performs SQL operations, this method handles transaction lifecycle and PreparedStatement caching.
     */
    @Override
    protected void doWriteEnvironmentCells(Object connection, List<TickData> ticks,
                                           EnvironmentProperties envProps) throws Exception {
        Connection conn = (Connection) connection;
        
        try {
            // Get or create PreparedStatement for this connection
            PreparedStatement stmt = envWriteStmtCache.computeIfAbsent(conn, c -> {
                try {
                    return c.prepareStatement(envStorageStrategy.getMergeSql());
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to prepare statement", e);
                }
            });
            
            // Delegate to storage strategy for SQL operations with prepared statement
            envStorageStrategy.writeTicks(conn, stmt, ticks, envProps);
            
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
        long startTime;
        long seed;
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
     */
    @Override
    protected void closeConnectionPool() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.debug("H2 database '{}' connection pool closed", getResourceName());
        }
    }
}