package org.evochora.datapipeline.resources.topics;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Message;
import com.typesafe.config.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Acknowledgment token for H2-based topics.
 * <p>
 * Contains both the row ID and claim version to enable stale ACK detection.
 * When a message is reassigned (timeout), the claim version is incremented.
 * This prevents the original (stale) consumer from successfully acknowledging
 * a message that was already reassigned to another consumer.
 *
 * @param rowId The database row ID (primary key).
 * @param claimVersion The claim version at the time of read (for stale ACK detection).
 */
record AckToken(long rowId, int claimVersion) {}

/**
 * H2 database-based topic implementation with HikariCP connection pooling.
 * <p>
 * This implementation uses an H2 file-based database to provide:
 * <ul>
 *   <li><strong>Persistence:</strong> Messages survive process restarts</li>
 *   <li><strong>Multi-Writer:</strong> Concurrent writes via H2 MVCC and HikariCP pooling</li>
 *   <li><strong>Connection Pooling:</strong> HikariCP for efficient connection management</li>
 *   <li><strong>Explicit ACK:</strong> Junction table tracks acknowledgments per consumer group</li>
 *   <li><strong>Consumer Groups:</strong> Junction table ensures proper isolation</li>
 *   <li><strong>Competing Consumers:</strong> FOR UPDATE SKIP LOCKED for automatic load balancing</li>
 *   <li><strong>Permanent Storage:</strong> Messages never deleted (historical replay support)</li>
 *   <li><strong>Type Agnostic:</strong> Dynamic type resolution from google.protobuf.Any (no config needed)</li>
 *   <li><strong>Simplicity:</strong> Standard JDBC, no API limitations</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong>
 * This class is thread-safe. Multiple writers and readers can operate concurrently.
 * HikariCP manages connection thread safety internally.
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 */
public class H2TopicResource<T extends Message> extends AbstractTopicResource<T, AckToken> 
        implements IMemoryEstimatable {
    
    private static final Logger log = LoggerFactory.getLogger(H2TopicResource.class);
    
    private final HikariDataSource dataSource;
    private final int claimTimeoutSeconds;  // 0 = disabled, > 0 = timeout for stuck message reassignment
    private final AtomicLong stuckMessagesReassigned;  // O(1) metric for reassignments
    // Note: writeThroughput and readThroughput are now inherited from AbstractTopicResource
    
    // Synchronization for schema setup
    private final Object initLock = new Object();
    
    // Centralized table names (shared by all topics)
    private static final String MESSAGES_TABLE = "topic_messages";
    private static final String CONSUMER_GROUP_TABLE = "topic_consumer_group";
    
    /**
     * Creates a new H2TopicResource with HikariCP connection pooling.
     * <p>
     * <strong>Type Agnostic:</strong>
     * This resource does not require a {@code messageType} configuration. The concrete message type
     * is determined dynamically from the {@code google.protobuf.Any} type URL when messages are read.
     * <p>
     * <strong>HikariCP Configuration:</strong>
     * <ul>
     *   <li>{@code dbPath} - Database file path (supports path expansion)</li>
     *   <li>{@code maxPoolSize} - Maximum number of connections (default: 10)</li>
     *   <li>{@code minIdle} - Minimum idle connections (default: 2)</li>
     *   <li>{@code username} - Database username (default: "sa")</li>
     *   <li>{@code password} - Database password (default: "")</li>
     *   <li>{@code claimTimeout} - Seconds before stuck message reassignment (default: 300, 0=disabled)</li>
     * </ul>
     *
     * @param name The resource name.
     * @param options The configuration options.
     * @throws RuntimeException if database initialization fails.
     */
    public H2TopicResource(String name, Config options) {
        super(name, options);
        
        // Get JDBC URL with variable expansion (same as H2Database)
        if (!options.hasPath("jdbcUrl")) {
            throw new IllegalArgumentException("'jdbcUrl' must be configured for H2TopicResource.");
        }
        String jdbcUrl = options.getString("jdbcUrl");
        
        // HikariCP configuration (same pattern as H2Database)
        String username = options.hasPath("username") ? options.getString("username") : "sa";
        String password = options.hasPath("password") ? options.getString("password") : "";
        int maxPoolSize = options.hasPath("maxPoolSize") ? options.getInt("maxPoolSize") : 10;
        int minIdle = options.hasPath("minIdle") ? options.getInt("minIdle") : 2;
        
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setDriverClassName("org.h2.Driver");  // Explicit for Fat JAR compatibility
        hikariConfig.setMaximumPoolSize(maxPoolSize);
        hikariConfig.setMinimumIdle(minIdle);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        
        // Set pool name to resource name for better logging
        hikariConfig.setPoolName(name);
        
        this.claimTimeoutSeconds = options.hasPath("claimTimeout")
            ? options.getInt("claimTimeout")
            : 300;  // Default: 5 minutes
        
        try {
            this.dataSource = new HikariDataSource(hikariConfig);
            log.debug("H2 topic '{}' connection pool started (max={}, minIdle={}, claimTimeout={}s)", 
                name, maxPoolSize, minIdle, claimTimeoutSeconds);
            
            // Note: Tables are created LAZY in setSimulationRun() for schema isolation
            
            // Initialize H2-specific metrics
            this.stuckMessagesReassigned = new AtomicLong(0);
            // Note: writeThroughput and readThroughput are initialized by AbstractTopicResource
            
            log.debug("H2 topic resource '{}' initialized (claimTimeout={}s)", name, claimTimeoutSeconds);
            
        } catch (Exception e) {
            log.error("Failed to initialize H2 topic resource '{}'", name);
            recordError("INIT_FAILED", "H2 topic initialization failed", "Topic: " + name);
            throw new RuntimeException("Failed to initialize H2 topic: " + name, e);
        }
    }
    
    /**
     * Gets a connection from the HikariCP pool.
     *
     * @return A database connection.
     * @throws SQLException if connection cannot be obtained.
     */
    protected Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    /**
     * Returns the messages table name.
     *
     * @return "topic_messages"
     */
    protected String getMessagesTable() {
        return MESSAGES_TABLE;
    }
    
    /**
     * Returns the consumer group tracking table name.
     *
     * @return "topic_consumer_group"
     */
    protected String getConsumerGroupTable() {
        return CONSUMER_GROUP_TABLE;
    }
    
    /**
     * Returns the claim timeout in seconds.
     *
     * @return Claim timeout (0 = disabled).
     */
    protected int getClaimTimeoutSeconds() {
        return claimTimeoutSeconds;
    }
    
    // Note: recordWrite() and recordRead() are inherited from AbstractTopicResource
    // They update both counter (messagesPublished/Received) and throughput (writeThroughput/readThroughput)
    
    /**
     * Records a stuck message reassignment.
     */
    protected void recordStuckMessageReassignment() {
        stuckMessagesReassigned.incrementAndGet();
    }
    
    /**
     * Setup callback for H2SchemaUtil: creates tables.
     * <p>
     * This method performs ALL schema-specific setup:
     * <ul>
     *   <li>Creates centralized topic tables ({@code topic_messages}, {@code topic_consumer_group_acks})</li>
     *   <li>Creates all necessary indexes</li>
     * </ul>
     * <p>
     * <strong>Thread Safety:</strong>
     * This method is called from {@link #setSimulationRun(String)} within a synchronized block.
     * <p>
     * <strong>Callback for H2SchemaUtil:</strong>
     * This method is passed as a {@link org.evochora.datapipeline.utils.H2SchemaUtil.SchemaSetupCallback}
     * callback to {@link org.evochora.datapipeline.utils.H2SchemaUtil#setupRunSchema}.
     *
     * @param connection The database connection (already switched to target schema).
     * @param schemaName The sanitized schema name (provided by H2SchemaUtil).
     * @throws SQLException if setup fails.
     */
    private void setupSchemaResources(Connection connection, String schemaName) throws SQLException {
        // Create tables
        createTablesInSchema(connection);
    }
    
    /**
     * Creates centralized tables for topic messages and acknowledgments in the current schema.
     * <p>
     * <strong>Centralized Design (Shared Table Structure per Run):</strong>
     * Creates two shared tables in the current schema that are used by ALL topics in this simulation run:
     * <ul>
     *   <li>{@code topic_messages} - Stores messages from all topics</li>
     *   <li>{@code topic_consumer_group_acks} - Tracks acknowledgments across all topics</li>
     * </ul>
     * <p>
     * The {@code topic_name} column enables logical partitioning within these shared tables.
     *
     * @param connection The database connection (already switched to target schema).
     * @throws SQLException if table creation fails.
     */
    private void createTablesInSchema(Connection connection) throws SQLException {
        // Create centralized messages table (shared by all topics)
        // This table only stores message payloads, no claim/ack state
        String createMessagesSql = """
            CREATE TABLE IF NOT EXISTS topic_messages (
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                topic_name VARCHAR(255) NOT NULL,
                message_id VARCHAR(255) NOT NULL,
                timestamp BIGINT NOT NULL,
                envelope BYTEA NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                
                CONSTRAINT uk_topic_message UNIQUE (topic_name, message_id)
            )
            """;
        
        // Create index for messages table
        String createIndexMessages = 
            "CREATE INDEX IF NOT EXISTS idx_topic_messages ON topic_messages (topic_name, id)";
        
        // Create centralized consumer group tracking table (shared by all topics)
        // Each consumer group has its own claim/ack state for each message
        String createConsumerGroupSql = """
            CREATE TABLE IF NOT EXISTS topic_consumer_group (
                topic_name VARCHAR(255) NOT NULL,
                consumer_group VARCHAR(255) NOT NULL,
                message_id VARCHAR(255) NOT NULL,
                claimed_by VARCHAR(255),
                claimed_at TIMESTAMP,
                claim_version INT DEFAULT 0,
                acknowledged_at TIMESTAMP,
                
                PRIMARY KEY (topic_name, consumer_group, message_id)
            )
            """;
        
        // Create indexes for consumer group table (optimized for queries)
        String createIndexUnclaimed = 
            "CREATE INDEX IF NOT EXISTS idx_consumer_group_unclaimed ON topic_consumer_group (topic_name, consumer_group, claimed_by, message_id)";
        String createIndexClaimed = 
            "CREATE INDEX IF NOT EXISTS idx_consumer_group_claimed ON topic_consumer_group (topic_name, consumer_group, claimed_at)";
        
        // Execute all CREATE statements (connection already provided by H2SchemaUtil.setupRunSchema)
        try (Statement stmt = connection.createStatement()) {
            // Use H2SchemaUtil for CREATE TABLE to handle concurrent initialization race conditions
            H2SchemaUtil.executeDdlIfNotExists(stmt, createMessagesSql, "topic_messages");
            executeIndexCreation(stmt, createIndexMessages, "idx_topic_messages");
            H2SchemaUtil.executeDdlIfNotExists(stmt, createConsumerGroupSql, "topic_consumer_group");
            executeIndexCreation(stmt, createIndexUnclaimed, "idx_consumer_group_unclaimed");
            executeIndexCreation(stmt, createIndexClaimed, "idx_consumer_group_claimed");
            log.debug("Created centralized topic tables for resource '{}'", getResourceName());
        }
    }
    
    /**
     * Executes CREATE INDEX statement with H2-specific error handling.
     * <p>
     * H2 has a bug where CREATE INDEX IF NOT EXISTS sometimes throws
     * "object already exists" exception even with IF NOT EXISTS clause.
     * This method catches and ignores such errors.
     *
     * @param stmt The statement to execute with.
     * @param sql The CREATE INDEX SQL.
     * @param indexName The index name for logging.
     * @throws SQLException if creation fails for reasons other than "already exists".
     */
    private void executeIndexCreation(Statement stmt, String sql, String indexName) throws SQLException {
        try {
            stmt.execute(sql);
        } catch (SQLException e) {
            // H2 error code 50000 = General error (includes "object already exists")
            if (e.getErrorCode() == 50000 && e.getMessage().contains("object already exists")) {
                log.debug("Index '{}' already exists, skipping creation", indexName);
            } else {
                throw e; // Re-throw if it's a different error
            }
        }
    }
    
    
    @Override
    protected ITopicReader<T, AckToken> createReaderDelegate(ResourceContext context) {
        return new H2TopicReaderDelegate<>(this, context);
    }
    
    @Override
    protected ITopicWriter<T> createWriterDelegate(ResourceContext context) {
        return new H2TopicWriterDelegate<>(this, context);
    }
    
    @Override
    protected UsageState getWriteUsageState() {
        // Fast checks (O(1), no blocking)
        if (dataSource == null || dataSource.isClosed()) {
            return UsageState.FAILED;
        }
        
        // Check if pool has available connections (HikariCP metrics are instant)
        int activeConnections = dataSource.getHikariPoolMXBean().getActiveConnections();
        int maxPoolSize = dataSource.getHikariPoolMXBean().getTotalConnections();
        
        if (activeConnections >= maxPoolSize) {
            return UsageState.WAITING;  // Pool exhausted, but temporary
        }
        
        return UsageState.ACTIVE;
    }
    
    @Override
    protected UsageState getReadUsageState() {
        // Same logic as writes - H2 MVCC allows concurrent reads/writes
        return getWriteUsageState();
    }
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Includes aggregate metrics from AbstractTopicResource
        
        // H2-specific metrics
        metrics.put("stuck_messages_reassigned", stuckMessagesReassigned.get());
    }
    
    @Override
    protected void onSimulationRunSet(String simulationRunId) {
        synchronized (initLock) {
            try (Connection conn = getConnection()) {
                // Setup schema + tables for this run (called only once, guaranteed by AbstractTopicResource)
                H2SchemaUtil.setupRunSchema(conn, simulationRunId, this::setupSchemaResources);
                
                log.debug("H2 topic '{}' setup complete for run: {}", getResourceName(), simulationRunId);
                
            } catch (SQLException e) {
                log.error("Failed to setup schema for topic '{}', run: {} - Cause: {}", getResourceName(), simulationRunId, e.getMessage());
                recordError("SCHEMA_SETUP_FAILED", "Schema setup failed", 
                    "Topic: " + getResourceName() + ", Run: " + simulationRunId + ", Cause: " + e.getMessage());
                throw new RuntimeException("Failed to setup schema for run: " + simulationRunId, e);
            }
        }
    }
    
    // ========================================================================
    // IMemoryEstimatable Implementation
    // ========================================================================

    /**
     * Estimates worst-case heap memory usage for this H2 topic resource.
     * <p>
     * H2TopicResource uses a separate HikariCP connection pool. The memory footprint
     * is primarily from connection overhead. Note that if this topic shares the same
     * H2 database file as H2Database, the MVStore overhead is NOT counted here
     * (it's counted in H2Database).
     * <p>
     * Components:
     * <ul>
     *   <li><b>Connection overhead</b>: ~5 MB per connection (topic queries are lighter than DB)</li>
     *   <li><b>Message buffer</b>: ~10 MB for pending messages</li>
     * </ul>
     *
     * @param params Simulation parameters (not used for topic estimation)
     * @return List containing a single memory estimate for this topic
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        int poolSize = options.hasPath("maxPoolSize") ? options.getInt("maxPoolSize") : 10;
        
        // Topic connections are lighter (no BLOB reads), ~5 MB per connection
        long connectionOverhead = (long) poolSize * 5 * 1024 * 1024;
        
        // Message buffer for pending messages
        long messageBuffer = 10L * 1024 * 1024;
        
        long totalEstimate = connectionOverhead + messageBuffer;
        
        String description = String.format(
            "%d conn × 5MB + 10MB buffer",
            poolSize);
        
        return List.of(new MemoryEstimate(
            getResourceName(),
            totalEstimate,
            description,
            MemoryEstimate.Category.TOPIC
        ));
    }
    
    @Override
    public void close() throws Exception {
        // Step 1: Close all delegates (releases connections back to pool)
        super.close();
        
        // Step 2: Close connection pool (after all delegates are closed)
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.debug("H2 topic '{}' connection pool closed", getResourceName());
        }
    }
}

