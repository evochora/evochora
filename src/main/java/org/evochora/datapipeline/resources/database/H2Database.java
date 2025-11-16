package org.evochora.datapipeline.resources.database;

import com.google.gson.Gson;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.evochora.datapipeline.api.contracts.DataPointerList;
import org.evochora.datapipeline.api.contracts.OrganismRuntimeState;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IDatabaseReaderProvider;
import org.evochora.datapipeline.resources.database.H2DatabaseReader;
import org.evochora.datapipeline.resources.database.h2.IH2EnvStorageStrategy;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.evochora.datapipeline.utils.PathExpansion;
import org.evochora.datapipeline.utils.compression.CompressionCodecFactory;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;
import org.evochora.datapipeline.utils.protobuf.ProtobufConverter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.runtime.model.EnvironmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
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

/**
 * H2 database implementation using HikariCP for connection pooling.
 * <p>
 * Implements {@link AutoCloseable} to ensure proper cleanup of database connections
 * and connection pool resources during shutdown.
 */
public class H2Database extends AbstractDatabaseResource implements AutoCloseable, IDatabaseReaderProvider {

    private static final Logger log = LoggerFactory.getLogger(H2Database.class);
    private final HikariDataSource dataSource;
    private final AtomicLong diskWrites = new AtomicLong(0);
    private final SlidingWindowCounter diskWritesCounter;
    
    // Environment storage strategy (loaded via reflection)
    private final IH2EnvStorageStrategy envStorageStrategy;
    
    // PreparedStatement cache for environment writes (per connection)
    private final Map<Connection, PreparedStatement> envWriteStmtCache = new ConcurrentHashMap<>();
    
    // Metadata cache (LRU with automatic eviction)
    private final Map<String, SimulationMetadata> metadataCache;
    private final int maxCacheSize;

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
        
        // Clear interrupt flag temporarily to allow H2 operations
        // H2 Database's internal locking mechanism (MVMap.tryLock()) uses Thread.sleep()
        // which throws InterruptedException if thread is interrupted
        boolean wasInterrupted = Thread.interrupted();
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
     * Creates {@code organisms} and {@code organism_states} tables for the current schema.
     * <p>
     * Uses {@link H2SchemaUtil#executeDdlIfNotExists(Statement, String, String)} for
     * concurrent-safe DDL execution.
     */
    @Override
    protected void doCreateOrganismTables(Object connection) throws Exception {
        Connection conn = (Connection) connection;

        try (Statement stmt = conn.createStatement()) {
            // Static organism metadata table
            H2SchemaUtil.executeDdlIfNotExists(
                stmt,
                "CREATE TABLE IF NOT EXISTS organisms (" +
                "  organism_id INT PRIMARY KEY," +
                "  parent_id INT NULL," +
                "  birth_tick BIGINT NOT NULL," +
                "  program_id TEXT NOT NULL," +
                "  initial_position BYTEA NOT NULL" +
                ")",
                "organisms"
            );

            // Per-tick organism state table
            H2SchemaUtil.executeDdlIfNotExists(
                stmt,
                "CREATE TABLE IF NOT EXISTS organism_states (" +
                "  tick_number BIGINT NOT NULL," +
                "  organism_id INT NOT NULL," +
                "  energy INT NOT NULL," +
                "  ip BYTEA NOT NULL," +
                "  dv BYTEA NOT NULL," +
                "  data_pointers BYTEA NOT NULL," +
                "  active_dp_index INT NOT NULL," +
                "  runtime_state_blob BYTEA NOT NULL," +
                "  PRIMARY KEY (tick_number, organism_id)" +
                ")",
                "organism_states"
            );

            // Optional helper index for per-organism history queries
            H2SchemaUtil.executeDdlIfNotExists(
                stmt,
                "CREATE INDEX IF NOT EXISTS idx_organism_states_org ON organism_states (organism_id)",
                "idx_organism_states_org"
            );
        }

        conn.commit();
    }

    /**
     * Writes organism static and per-tick state using MERGE for idempotency.
     */
    @Override
    protected void doWriteOrganismStates(Object connection, List<TickData> ticks) throws Exception {
        Connection conn = (Connection) connection;

        if (ticks.isEmpty()) {
            return;
        }

        ICompressionCodec codec = CompressionCodecFactory.create(
                getOptions().hasPath("organismRuntimeStateCompression")
                        ? getOptions().getConfig("organismRuntimeStateCompression")
                        : ConfigFactory.empty()
        );

        PreparedStatement organismsStmt = null;
        PreparedStatement statesStmt = null;

        try {
            organismsStmt = conn.prepareStatement(
                    "MERGE INTO organisms (" +
                            "organism_id, parent_id, birth_tick, program_id, initial_position" +
                            ") KEY (organism_id) VALUES (?, ?, ?, ?, ?)"
            );

            statesStmt = conn.prepareStatement(
                    "MERGE INTO organism_states (" +
                            "tick_number, organism_id, energy, ip, dv, data_pointers, active_dp_index, runtime_state_blob" +
                            ") KEY (tick_number, organism_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            );

            for (TickData tick : ticks) {
                long tickNumber = tick.getTickNumber();
                for (OrganismState org : tick.getOrganismsList()) {
                    // Static table MERGE
                    organismsStmt.setInt(1, org.getOrganismId());
                    if (org.hasParentId()) {
                        organismsStmt.setInt(2, org.getParentId());
                    } else {
                        organismsStmt.setNull(2, java.sql.Types.INTEGER);
                    }
                    organismsStmt.setLong(3, org.getBirthTick());
                    organismsStmt.setString(4, org.getProgramId());
                    organismsStmt.setBytes(5, org.getInitialPosition().toByteArray());
                    organismsStmt.addBatch();

                    // Runtime state blob
                    OrganismRuntimeState runtimeState = OrganismRuntimeState.newBuilder()
                            .addAllDataRegisters(org.getDataRegistersList())
                            .addAllProcedureRegisters(org.getProcedureRegistersList())
                            .addAllFormalParamRegisters(org.getFormalParamRegistersList())
                            .addAllLocationRegisters(org.getLocationRegistersList())
                            .addAllDataStack(org.getDataStackList())
                            .addAllLocationStack(org.getLocationStackList())
                            .addAllCallStack(org.getCallStackList())
                            .setInstructionFailed(org.getInstructionFailed())
                            .setFailureReason(org.hasFailureReason() ? org.getFailureReason() : "")
                            .addAllFailureCallStack(org.getFailureCallStackList())
                            .build();

                    byte[] blobBytes;
                    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                         OutputStream out = codec.wrapOutputStream(baos)) {
                        runtimeState.writeTo(out);
                        out.flush();
                        blobBytes = baos.toByteArray();
                    }

                    // Per-tick state MERGE
                    statesStmt.setLong(1, tickNumber);
                    statesStmt.setInt(2, org.getOrganismId());
                    statesStmt.setInt(3, org.getEnergy());
                    statesStmt.setBytes(4, org.getIp().toByteArray());
                    statesStmt.setBytes(5, org.getDv().toByteArray());

                    // data_pointers: serialize list of Vectors into DataPointerList
                    DataPointerList dpList = DataPointerList.newBuilder()
                            .addAllDataPointers(org.getDataPointersList())
                            .build();
                    statesStmt.setBytes(6, dpList.toByteArray());

                    statesStmt.setInt(7, org.getActiveDpIndex());
                    statesStmt.setBytes(8, blobBytes);
                    statesStmt.addBatch();
                }
            }

            organismsStmt.executeBatch();
            statesStmt.executeBatch();

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
        } finally {
            if (organismsStmt != null) {
                try {
                    organismsStmt.close();
                } catch (SQLException ignored) { }
            }
            if (statesStmt != null) {
                try {
                    statesStmt.close();
                } catch (SQLException ignored) { }
            }
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
    
    @Override
    public IDatabaseReader createReader(String runId) throws SQLException {
        try {
            Connection conn = dataSource.getConnection();
            H2SchemaUtil.setSchema(conn, runId);
            return new H2DatabaseReader(conn, this, envStorageStrategy, runId);
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
     * Queries the environment_ticks table to find the minimum and maximum tick numbers.
     * Returns null if no ticks are available.
     *
     * @param conn The database connection (schema already set)
     * @param runId The simulation run ID (for logging/debugging, schema already contains this run)
     * @return TickRange with minTick and maxTick, or null if no ticks exist
     * @throws SQLException if database query fails
     */
    org.evochora.datapipeline.api.resources.database.TickRange getTickRangeInternal(
            Connection conn, String runId) throws SQLException {
        try {
            // Query min and max tick numbers from environment_ticks table
            // Schema is already set by the connection (via H2DatabaseReader)
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT MIN(tick_number) as min_tick, MAX(tick_number) as max_tick " +
                "FROM environment_ticks"
            );
            ResultSet rs = stmt.executeQuery();
            
            queriesExecuted.incrementAndGet();
            
            if (!rs.next()) {
                // No rows in table
                return null;
            }
            
            // Check if result is null (table exists but empty, or all ticks deleted)
            long minTick = rs.getLong("min_tick");
            long maxTick = rs.getLong("max_tick");
            
            if (rs.wasNull()) {
                // Table exists but is empty
                return null;
            }
            
            return new org.evochora.datapipeline.api.resources.database.TickRange(minTick, maxTick);
            
        } catch (SQLException e) {
            // Table doesn't exist yet (no ticks written)
            if (e.getErrorCode() == 42104 || e.getErrorCode() == 42102 || 
                (e.getMessage().contains("Table") && e.getMessage().contains("not found"))) {
                return null; // No ticks available
            }
            throw e; // Other SQL errors
        }
    }
}