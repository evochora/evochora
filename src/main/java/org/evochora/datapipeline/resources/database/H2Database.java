package org.evochora.datapipeline.resources.database;

import com.google.gson.Gson;
import com.typesafe.config.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.utils.PathExpansion;
import org.evochora.datapipeline.utils.protobuf.ProtobufConverter;
import org.evochora.datapipeline.utils.monitoring.RateBucket;
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
    private final Map<String, RateBucket> rateBuckets = new ConcurrentHashMap<>();

    public H2Database(String name, Config options) {
        super(name, options);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(getJdbcUrl(options));
        hikariConfig.setMaximumPoolSize(options.hasPath("maxPoolSize") ? options.getInt("maxPoolSize") : 10);
        hikariConfig.setMinimumIdle(options.hasPath("minIdle") ? options.getInt("minIdle") : 2);
        this.dataSource = new HikariDataSource(hikariConfig);
        int metricsWindowSizeMs = options.hasPath("metricsWindowSizeMs") ? options.getInt("metricsWindowSizeMs") : 5000;
        rateBuckets.put("disk_writes", new RateBucket(metricsWindowSizeMs));
    }

    private String getJdbcUrl(Config options) {
        if (options.hasPath("jdbcUrl")) {
            return options.getString("jdbcUrl");
        }
        if (!options.hasPath("dataDirectory")) {
            throw new IllegalArgumentException("Either 'jdbcUrl' or 'dataDirectory' must be configured for H2Database.");
        }
        String dataDir = options.getString("dataDirectory");
        String expandedPath = PathExpansion.expandPath(dataDir);
        if (!dataDir.equals(expandedPath)) {
            log.debug("Expanded dataDirectory: '{}' -> '{}'", dataDir, expandedPath);
        }
        return "jdbc:h2:" + expandedPath + "/evochora;MODE=PostgreSQL";
    }

    @Override
    protected Object acquireDedicatedConnection() throws Exception {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        return conn;
    }

    /**
     * Converts a simulation run ID to a valid H2 schema name.
     * <p>
     * Sanitization rules:
     * <ul>
     *   <li>Prepends "sim_" prefix</li>
     *   <li>Replaces all non-alphanumeric characters with underscore</li>
     *   <li>Converts to uppercase (H2 stores identifiers in uppercase)</li>
     *   <li>Validates length (H2 identifier limit: 256 characters)</li>
     * </ul>
     * <p>
     * Example:
     * <pre>
     * 20251006143025-550e8400-e29b-41d4-a716-446655440000
     * → SIM_20251006143025_550E8400_E29B_41D4_A716_446655440000
     * </pre>
     *
     * @param simulationRunId Raw simulation run ID
     * @return Sanitized schema name in uppercase
     * @throws IllegalArgumentException if runId is null, empty, or results in name exceeding 256 chars
     */
    @Override
    protected String toSchemaName(String simulationRunId) {
        if (simulationRunId == null || simulationRunId.isEmpty()) {
            throw new IllegalArgumentException("Simulation run ID cannot be null or empty");
        }
        
        // Sanitize: replace all non-alphanumeric characters with underscore
        String sanitized = "sim_" + simulationRunId.replaceAll("[^a-zA-Z0-9]", "_");
        
        // Validate length (H2 identifier limit is 256 chars)
        if (sanitized.length() > 256) {
            throw new IllegalArgumentException(
                "Schema name too long (" + sanitized.length() + " chars, max 256). " +
                "RunId: " + simulationRunId.substring(0, Math.min(50, simulationRunId.length())) + "..."
            );
        }
        
        // H2 is case-insensitive and stores identifiers in uppercase
        // Return uppercase for consistency with H2's internal representation
        return sanitized.toUpperCase();
    }

    @Override
    protected void doSetSchema(Object connection, String schemaName) throws Exception {
        ((Connection) connection).createStatement().execute("SET SCHEMA " + schemaName);
    }

    @Override
    protected void doCreateSchema(Object connection, String schemaName) throws Exception {
        Connection conn = (Connection) connection;
        conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
        conn.commit();
    }

    @Override
    protected void doInsertMetadata(Object connection, SimulationMetadata metadata) throws Exception {
        Connection conn = (Connection) connection;
        try {
            conn.createStatement().execute("CREATE TABLE IF NOT EXISTS metadata (\"key\" VARCHAR PRIMARY KEY, \"value\" JSON)");
            
            Gson gson = new Gson();
            Map<String, String> kvPairs = new HashMap<>();
            
            // Environment: Use ProtobufConverter (direct Protobuf → JSON, fastest)
            kvPairs.put("environment", ProtobufConverter.toJson(metadata.getEnvironment()));
            
            // Simulation info: Use GSON (no Protobuf message available, safer than String.format)
            Map<String, Object> simInfoMap = Map.of(
                "runId", metadata.getSimulationRunId(),
                "startTime", metadata.getStartTimeMs(),
                "seed", metadata.getInitialSeed()
            );
            kvPairs.put("simulation_info", gson.toJson(simInfoMap));

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
            rateBuckets.get("disk_writes").record(System.currentTimeMillis());
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException re) {
                log.warn("Rollback failed", re);
            }
            recordError("INSERT_METADATA_FAILED", "Failed to insert metadata", "Error: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Adds H2-specific metrics via Template Method Pattern hook.
     * <p>
     * Includes:
     * <ul>
     *   <li>HikariCP connection pool metrics (O(1) via MXBean)</li>
     *   <li>Disk write rate (O(1) via RateBucket)</li>
     *   <li>H2 cache size (fast SQL query in INFORMATION_SCHEMA)</li>
     * </ul>
     * <p>
     * Note: Recording operations (incrementing counters) must be O(1).
     * Reading metrics (this method) can perform fast queries without impacting performance.
     */
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        long now = System.currentTimeMillis();
        
        // Disk write rate (O(1) via RateBucket)
        metrics.put("h2_disk_writes_per_sec", rateBuckets.get("disk_writes").getRate(now));
        
        if (dataSource != null && !dataSource.isClosed()) {
            // HikariCP connection pool metrics (O(1) via MXBean - instant reads)
            metrics.put("h2_pool_active_connections", dataSource.getHikariPoolMXBean().getActiveConnections());
            metrics.put("h2_pool_idle_connections", dataSource.getHikariPoolMXBean().getIdleConnections());
            metrics.put("h2_pool_total_connections", dataSource.getHikariPoolMXBean().getTotalConnections());
            metrics.put("h2_pool_threads_awaiting", dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection());
            
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