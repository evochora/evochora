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
import org.evochora.datapipeline.utils.MetadataConfigHelper;
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
    
    // Environment storage strategy: eager when explicitly configured, lazy otherwise
    private volatile IH2EnvStorageStrategy envStorageStrategy;
    
    // Organism storage strategy (loaded via reflection)
    private final IH2OrgStorageStrategy orgStorageStrategy;
    
    // Stage 7: remove after test migration to doWriteOrganismTick/doCommitOrganismWrites
    private final Map<Connection, PreparedStatement> orgStaticStmtCache = new ConcurrentHashMap<>();
    // Stage 7: remove after test migration to doWriteOrganismTick/doCommitOrganismWrites
    private final Map<Connection, PreparedStatement> orgStatesStmtCache = new ConcurrentHashMap<>();
    
    // Metadata cache (LRU with automatic eviction)
    private final Map<String, SimulationMetadata> metadataCache;
    private final int maxCacheSize;

    // Reader connection tracking (only createReader connections, not wrapper connections)
    private final ConcurrentHashMap<Connection, Long> readerCheckoutTimes = new ConcurrentHashMap<>();
    private final long readerConnectionWarningThresholdMs;

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

        // Configuration: readerConnectionWarningThresholdMs (default: 30000)
        this.readerConnectionWarningThresholdMs = options.hasPath("readerConnectionWarningThresholdMs")
            ? options.getLong("readerConnectionWarningThresholdMs")
            : 30_000L;

        // Load organism storage strategy via reflection
        this.orgStorageStrategy = loadOrganismStorageStrategy(options);

        // Load environment storage strategy eagerly when explicitly configured
        // (validates className/options immediately), lazy when absent (default strategy
        // requires chunkDirectory which may not be available in all contexts)
        if (options.hasPath("h2EnvironmentStrategy")) {
            this.envStorageStrategy = loadEnvironmentStorageStrategy(options);
        }

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
    }
    
    /**
     * Returns the environment storage strategy.
     * <p>
     * The strategy is loaded eagerly when {@code h2EnvironmentStrategy} is explicitly
     * configured. When absent, this method throws because the default strategy requires
     * configuration (e.g., {@code chunkDirectory}) that cannot be inferred.
     *
     * @return the environment storage strategy, never null
     * @throws IllegalStateException if no environment strategy is configured
     */
    private IH2EnvStorageStrategy getEnvStrategy() {
        IH2EnvStorageStrategy s = envStorageStrategy;
        if (s == null) {
            throw new IllegalStateException(
                "Environment storage strategy not configured. " +
                "Add h2EnvironmentStrategy to database configuration.");
        }
        return s;
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
            try (Statement ddlStmt = conn.createStatement()) {
                H2SchemaUtil.executeDdlIfNotExists(
                    ddlStmt,
                    "CREATE TABLE IF NOT EXISTS metadata (\"key\" VARCHAR PRIMARY KEY, \"value\" TEXT)",
                    "metadata"
                );
            }
            
            Gson gson = new Gson();
            Map<String, String> kvPairs = new HashMap<>();

            // Environment: Extract from resolvedConfigJson
            int[] shape = MetadataConfigHelper.getEnvironmentShape(metadata);
            boolean toroidal = MetadataConfigHelper.isEnvironmentToroidal(metadata);
            Map<String, Object> envMap = new LinkedHashMap<>();
            envMap.put("dimensions", shape.length);
            envMap.put("shape", shape);
            envMap.put("toroidal", toroidal);
            kvPairs.put("environment", gson.toJson(envMap));

            // Simulation info: Use GSON
            Map<String, Object> simInfoMap = Map.of(
                "runId", metadata.getSimulationRunId(),
                "startTime", metadata.getStartTimeMs(),
                "seed", metadata.getInitialSeed(),
                "samplingInterval", MetadataConfigHelper.getSamplingInterval(metadata)
            );
            kvPairs.put("simulation_info", gson.toJson(simInfoMap));

            // Full metadata backup: Complete JSON for future extensibility without re-indexing
            kvPairs.put("full_metadata", ProtobufConverter.toJson(metadata));

            try (PreparedStatement stmt = conn.prepareStatement("MERGE INTO metadata (\"key\", \"value\") KEY(\"key\") VALUES (?, ?)")) {
                for (Map.Entry<String, String> entry : kvPairs.entrySet()) {
                    stmt.setString(1, entry.getKey());
                    stmt.setString(2, entry.getValue());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
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
        getEnvStrategy().createTables(conn, dimensions);
        
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
            getEnvStrategy().writeChunks(conn, chunks);
            
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

    /**
     * Delegates a single raw chunk write to the environment storage strategy.
     * <p>
     * No commit — chunks are accumulated and committed via
     * {@link #doCommitRawEnvironmentChunks(Object)}.
     */
    @Override
    protected void doWriteRawEnvironmentChunk(Object connection,
                                              long firstTick, long lastTick,
                                              int tickCount, byte[] rawProtobufData) throws Exception {
        Connection conn = (Connection) connection;
        getEnvStrategy().writeRawChunk(conn, firstTick, lastTick, tickCount, rawProtobufData);
    }

    /**
     * Commits accumulated raw environment chunks via the storage strategy.
     * <p>
     * Delegates batch execution to the strategy, then commits the transaction.
     * Rolls back on failure and restores the interrupt flag if it was set.
     */
    @Override
    protected void doCommitRawEnvironmentChunks(Object connection) throws Exception {
        Connection conn = (Connection) connection;

        // Clear interrupt flag temporarily to allow H2 operations
        boolean wasInterrupted = Thread.interrupted();

        try {
            getEnvStrategy().commitRawChunks(conn);
            conn.commit();
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                log.warn("Rollback failed (connection may be closed): {}", rollbackEx.getMessage());
            }
            throw e;
        } finally {
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

    // Stage 7: remove after test migration to doWriteOrganismTick/doCommitOrganismWrites
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

    /**
     * Delegates a single organism tick write to the storage strategy.
     * <p>
     * No commit — ticks are accumulated and committed via
     * {@link #doCommitOrganismWrites(Object)}.
     *
     * @param connection JDBC connection (cast to {@link Connection})
     * @param tick       Tick data containing organism states
     * @throws SQLException if batch addition fails
     */
    @Override
    protected void doWriteOrganismTick(Object connection, TickData tick) throws Exception {
        Connection conn = (Connection) connection;
        orgStorageStrategy.addOrganismTick(conn, tick);
    }

    /**
     * Commits accumulated organism tick writes via the storage strategy.
     * <p>
     * Delegates batch execution to the strategy, then commits the transaction.
     * Rolls back on failure and restores the interrupt flag if it was set.
     *
     * @param connection JDBC connection (cast to {@link Connection})
     * @throws SQLException if batch execution, commit, or rollback fails
     */
    @Override
    protected void doCommitOrganismWrites(Object connection) throws Exception {
        Connection conn = (Connection) connection;

        boolean wasInterrupted = Thread.interrupted();

        try {
            orgStorageStrategy.commitOrganismWrites(conn);
            conn.commit();
        } catch (SQLException e) {
            // Reset strategy session state to prevent reuse of stale batch buffers
            orgStorageStrategy.resetStreamingState();
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                log.warn("Rollback failed during organism writes commit: {}", rollbackEx.getMessage());
            }
            throw e;
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ========================================================================
    // IMetadataReader Capability
    // ========================================================================

    /**
     * Retrieves simulation metadata from the metadata table in the current schema.
     * <p>
     * Queries the "full_metadata" key and deserializes the stored JSON back to a
     * {@link SimulationMetadata} protobuf via {@link ProtobufConverter#fromJson(String, Class)}.
     *
     * @param connection     The JDBC connection (cast to {@link Connection}), must have the
     *                       correct schema already set.
     * @param simulationRunId The simulation run ID (used for error messages only).
     * @return The deserialized {@link SimulationMetadata} protobuf.
     * @throws org.evochora.datapipeline.api.resources.database.MetadataNotFoundException
     *         if the metadata row does not exist or the table has not been created yet.
     * @throws SQLException if a database access error occurs.
     *
     * <p><strong>Thread Safety:</strong> Not synchronized. Thread-safe only if the provided
     * connection is not shared concurrently (guaranteed by the wrapper connection model).
     */
    @Override
    protected SimulationMetadata doGetMetadata(Object connection, String simulationRunId) throws Exception {
        Connection conn = (Connection) connection;
        
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT \"value\" FROM metadata WHERE \"key\" = ?")) {
            stmt.setString(1, "full_metadata");
            try (ResultSet rs = stmt.executeQuery()) {
                queriesExecuted.incrementAndGet();

                if (!rs.next()) {
                    throw new org.evochora.datapipeline.api.resources.database.MetadataNotFoundException(
                        "Metadata not found for run: " + simulationRunId
                    );
                }

                String json = rs.getString("value");
                return ProtobufConverter.fromJson(json, SimulationMetadata.class);
            }
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
     * Checks whether simulation metadata exists in the current schema.
     * <p>
     * Queries for the "full_metadata" key in the metadata table. Returns {@code false}
     * if the table does not yet exist (H2 error 42104/42102), rather than throwing.
     *
     * @param connection     The JDBC connection (cast to {@link Connection}), must have the
     *                       correct schema already set.
     * @param simulationRunId The simulation run ID (unused, present for API contract).
     * @return {@code true} if metadata exists, {@code false} otherwise.
     * @throws SQLException if a database access error occurs (other than missing table).
     *
     * <p><strong>Thread Safety:</strong> Not synchronized. Thread-safe only if the provided
     * connection is not shared concurrently (guaranteed by the wrapper connection model).
     */
    @Override
    protected boolean doHasMetadata(Object connection, String simulationRunId) throws Exception {
        Connection conn = (Connection) connection;
        
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT COUNT(*) as cnt FROM metadata WHERE \"key\" = ?")) {
            stmt.setString(1, "full_metadata");
            try (ResultSet rs = stmt.executeQuery()) {
                queriesExecuted.incrementAndGet();
                return rs.next() && rs.getInt("cnt") > 0;
            }
        } catch (SQLException e) {
            // Table doesn't exist yet - metadata not available
            if (e.getErrorCode() == 42104 || e.getMessage().contains("Table") && e.getMessage().contains("not found")) {
                return false;
            }
            throw e; // Other SQL errors
        }
    }

    /**
     * Retrieves the simulation run ID stored in the current schema's metadata table.
     * <p>
     * Reads the "simulation_info" key from the metadata table and extracts the
     * {@code runId} field from the JSON value using Gson deserialization.
     *
     * @param connection The JDBC connection (cast to {@link Connection}), must have the
     *                   correct schema already set.
     * @return The simulation run ID string.
     * @throws org.evochora.datapipeline.api.resources.database.MetadataNotFoundException
     *         if the metadata row is missing, the table does not exist, or the runId field
     *         is null/empty.
     * @throws SQLException if a database access error occurs (other than missing table).
     *
     * <p><strong>Thread Safety:</strong> Not synchronized. Thread-safe only if the provided
     * connection is not shared concurrently (guaranteed by the wrapper connection model).
     */
    @Override
    protected String doGetRunIdInCurrentSchema(Object connection) throws Exception {
        Connection conn = (Connection) connection;
        
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT \"value\" FROM metadata WHERE \"key\" = ?")) {
            stmt.setString(1, "simulation_info");
            try (ResultSet rs = stmt.executeQuery()) {
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
            }
            
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

    /**
     * Performs fast shutdown by executing SHUTDOWN IMMEDIATELY before closing wrappers.
     * <p>
     * This overrides the default close() behavior to execute SHUTDOWN IMMEDIATELY first,
     * which closes all connections without waiting for rollback. This is critical for
     * large databases (1+ TB) where rollback of uncommitted transactions can take hours.
     * <p>
     * <strong>Data Safety:</strong> Uncommitted transactions are discarded, but this is
     * safe because:
     * <ul>
     *   <li>All pipeline writes use idempotent MERGE statements</li>
     *   <li>Discarded batches will be reprocessed on next startup</li>
     *   <li>Only in-flight data is lost, not committed data</li>
     * </ul>
     * <p>
     * <strong>Recovery:</strong> H2 MVStore performs automatic recovery on next startup,
     * rolling back any partially written transactions.
     */
    @Override
    public void close() {
        // Execute SHUTDOWN IMMEDIATELY first to avoid long rollback on wrapper close
        if (dataSource != null && !dataSource.isClosed()) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                // SHUTDOWN IMMEDIATELY: closes all connections immediately without rollback
                // This avoids hours-long rollback on large databases during shutdown
                stmt.execute("SHUTDOWN IMMEDIATELY");
                log.debug("H2 database '{}' shutdown immediately executed", getResourceName());
            } catch (SQLException e) {
                // 90121 = "Database is already closed" (normal auto-close)
                // 90098 = "The database has been closed" (MVStore I/O error killed the database)
                if (e.getErrorCode() == 90121 || e.getErrorCode() == 90098) {
                    log.debug("H2 database '{}' already closed", getResourceName());
                } else {
                    log.warn("H2 database '{}' SHUTDOWN IMMEDIATELY failed: {}",
                        getResourceName(), e.getMessage());
                    recordError("SHUTDOWN_FAILED", "SHUTDOWN IMMEDIATELY failed",
                        "Database: " + getResourceName() + ", Error: " + e.getMessage());
                }
                // Database is dead or already closed — don't attempt more SQL operations.
                // Just close wrappers (releasing pooled connections) and force-close the pool.
                try {
                    closeAllWrappers();
                } finally {
                    try {
                        dataSource.close();
                    } catch (Exception poolEx) {
                        log.warn("H2 database '{}' connection pool close failed: {}",
                            getResourceName(), poolEx.getMessage());
                        recordError("POOL_CLOSE_FAILED", "Connection pool close failed during force-close",
                            "Database: " + getResourceName() + ", Error: " + poolEx.getMessage());
                    }
                }
                log.debug("H2 database '{}' connection pool force-closed", getResourceName());
                return;
            }
        }

        // Normal path: SHUTDOWN IMMEDIATELY succeeded — close wrappers and pool
        super.close();
    }
    
    /**
     * Creates a new {@link IDatabaseReader} bound to the given run ID's schema.
     * <p>
     * Acquires a pooled connection from HikariCP, sets the H2 schema to the run ID
     * via {@link H2SchemaUtil#setSchema(Connection, String)}, and wraps it in an
     * {@link H2DatabaseReader}. The connection is tracked in {@code readerCheckoutTimes}
     * for stale-connection diagnostics.
     * <p>
     * Before acquiring, calls {@link #warnStaleReaderConnections()} to log warnings
     * for any connections held longer than the configured threshold.
     * <p>
     * If schema setup fails, the connection is closed before the exception propagates
     * (no connection leak on failure).
     *
     * @param runId The simulation run ID identifying the H2 schema.
     * @return A new {@link IDatabaseReader} that must be closed by the caller.
     * @throws SQLException if the schema does not exist (H2 error 90079) or a connection
     *         cannot be acquired.
     * @throws IllegalArgumentException if runId is null.
     *
     * <p><strong>Thread Safety:</strong> Thread-safe. Uses HikariCP's thread-safe pool
     * and {@link java.util.concurrent.ConcurrentHashMap} for reader tracking.
     */
    @Override
    public IDatabaseReader createReader(String runId) throws SQLException {
        // Check for stale reader connections before acquiring a new one
        warnStaleReaderConnections();

        Connection conn = dataSource.getConnection();
        boolean success = false;
        try {
            H2SchemaUtil.setSchema(conn, runId);
            H2DatabaseReader reader = new H2DatabaseReader(conn, this, envStorageStrategy, orgStorageStrategy, runId);
            readerCheckoutTimes.put(conn, System.currentTimeMillis());
            success = true;
            return reader;
        } finally {
            if (!success) {
                conn.close();
            }
        }
    }

    /**
     * Unregisters a reader connection from the tracking map.
     * Called by {@link H2DatabaseReader#close()}.
     *
     * @param conn The connection being returned.
     *
     * <p><strong>Thread Safety:</strong> Thread-safe. Uses {@link java.util.concurrent.ConcurrentHashMap#remove(Object)}.
     */
    void untrackReaderConnection(Connection conn) {
        readerCheckoutTimes.remove(conn);
    }

    /**
     * Checks for reader connections held longer than the configured threshold
     * and logs a warning if any are found.
     * <p>
     * <strong>Best-effort:</strong> This method iterates over a {@link ConcurrentHashMap}
     * snapshot. Connections added concurrently by other threads may not appear in the
     * current pass. This is acceptable since the method serves as a diagnostic warning,
     * not a correctness guarantee. Also cleans up entries for connections that were
     * closed without a corresponding {@link #untrackReaderConnection(Connection)} call.
     */
    private void warnStaleReaderConnections() {
        long now = System.currentTimeMillis();
        int staleCount = 0;
        long oldestAgeMs = 0;

        for (Map.Entry<Connection, Long> entry : readerCheckoutTimes.entrySet()) {
            long ageMs = now - entry.getValue();
            if (ageMs > readerConnectionWarningThresholdMs) {
                staleCount++;
                oldestAgeMs = Math.max(oldestAgeMs, ageMs);
            }
            // Clean up entries for connections that were closed without untrack
            try {
                if (entry.getKey().isClosed()) {
                    readerCheckoutTimes.remove(entry.getKey());
                }
            } catch (SQLException e) {
                readerCheckoutTimes.remove(entry.getKey());
            }
        }

        if (staleCount > 0) {
            log.warn("H2 reader connection(s) held too long: count={}, oldest={}ms, threshold={}ms, pool='{}'",
                staleCount, oldestAgeMs, readerConnectionWarningThresholdMs, getResourceName());
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
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT MIN(first_tick) as min_tick, MAX(last_tick) as max_tick " +
                "FROM environment_chunks")) {
            try (ResultSet rs = stmt.executeQuery()) {
                queriesExecuted.incrementAndGet();

                if (!rs.next()) {
                    return null;
                }

                long minTick = rs.getLong("min_tick");
                long maxTick = rs.getLong("max_tick");

                if (rs.wasNull()) {
                    return null;
                }

                return new org.evochora.datapipeline.api.resources.database.dto.TickRange(minTick, maxTick);
            }
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