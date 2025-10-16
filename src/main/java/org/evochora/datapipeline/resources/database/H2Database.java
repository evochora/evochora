package org.evochora.datapipeline.resources.database;

import com.google.gson.Gson;
import com.typesafe.config.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.evochora.datapipeline.utils.PathExpansion;
import org.evochora.datapipeline.utils.protobuf.ProtobufConverter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H2 database implementation using HikariCP for connection pooling.
 */
public class H2Database extends AbstractDatabaseResource {

    private static final Logger log = LoggerFactory.getLogger(H2Database.class);
    private final HikariDataSource dataSource;
    private final AtomicLong diskWrites = new AtomicLong(0);
    private final SlidingWindowCounter diskWritesCounter;

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
        
        try {
            this.dataSource = new HikariDataSource(hikariConfig);
            log.debug("Successfully connected to H2 database: {}", jdbcUrl);
        } catch (Exception e) {
            // Unwrap to find root cause
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            
            // Known error: wrong credentials
            if (cause.getMessage() != null && cause.getMessage().contains("Wrong user name or password")) {
                String errorMsg = String.format("Failed to connect to H2 database '%s': Wrong username/password. URL=%s, User=%s, Password=%s. Hint: Delete database files or use original credentials.", 
                    name, jdbcUrl, username.isEmpty() ? "(empty)" : username, password.isEmpty() ? "(empty)" : "***");
                log.error(errorMsg);
                throw new RuntimeException(errorMsg);
            }
            
            // Unknown error - rethrow for debugging
            throw e;
        }
        
        // Configuration: metricsWindowSeconds (default: 5)
        int metricsWindowSeconds = options.hasPath("metricsWindowSeconds")
            ? options.getInt("metricsWindowSeconds")
            : 5;
        
        this.diskWritesCounter = new SlidingWindowCounter(metricsWindowSeconds);
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
            conn.createStatement().execute("CREATE TABLE IF NOT EXISTS metadata (\"key\" VARCHAR PRIMARY KEY, \"value\" TEXT)");
            
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
            try {
                conn.rollback();
            } catch (SQLException re) {
                log.warn("Rollback failed", re);
            }
            writeErrors.incrementAndGet();
            log.warn("Failed to insert metadata");
            recordError("INSERT_METADATA_FAILED", "Failed to insert metadata", "Error: " + e.getMessage());
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

    public void stop() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}