package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Message;
import com.typesafe.config.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.PathExpansion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
public class H2TopicResource<T extends Message> extends AbstractTopicResource<T, AckToken> implements AutoCloseable {
    
    private static final Logger log = LoggerFactory.getLogger(H2TopicResource.class);
    
    private final HikariDataSource dataSource;
    private final int claimTimeoutSeconds;  // 0 = disabled, > 0 = timeout for stuck message reassignment
    private final BlockingQueue<Long> messageNotifications;  // Event-driven notification from H2 trigger
    private final AtomicLong stuckMessagesReassigned;  // O(1) metric for reassignments
    // Note: writeThroughput and readThroughput are now inherited from AbstractTopicResource
    
    // Lazy trigger registration (schema-aware for run isolation)
    private volatile String currentSchemaName = null;  // Current registered schema (null = not registered)
    private final Object triggerLock = new Object();   // Synchronization for trigger registration
    
    // Centralized table names (shared by all topics)
    private static final String MESSAGES_TABLE = "topic_messages";
    private static final String ACKS_TABLE = "topic_consumer_group_acks";
    
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
        
        // Path expansion (same as H2Database)
        String dbPath = options.hasPath("dbPath") 
            ? options.getString("dbPath") 
            : "./data/topics/" + name;
        String expandedPath = PathExpansion.expandPath(dbPath);
        if (!dbPath.equals(expandedPath)) {
            log.debug("Expanded dbPath: '{}' -> '{}'", dbPath, expandedPath);
        }
        
        // Build JDBC URL
        // AUTO_SERVER only for file-based databases (not for mem:)
        String jdbcUrl = expandedPath.startsWith("mem:")
            ? "jdbc:h2:" + expandedPath
            : "jdbc:h2:" + expandedPath + ";AUTO_SERVER=TRUE";
        
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
        
        this.claimTimeoutSeconds = options.hasPath("claimTimeout")
            ? options.getInt("claimTimeout")
            : 300;  // Default: 5 minutes
        
        try {
            this.dataSource = new HikariDataSource(hikariConfig);
            log.info("H2 topic '{}' connected: {}", name, jdbcUrl);
            
            // Initialize centralized tables (shared by all topics)
            initializeCentralizedTables();
            
            // Initialize notification queue for event-driven message delivery
            this.messageNotifications = new LinkedBlockingQueue<>();
            
            // Note: Trigger registration is LAZY - happens in ensureTriggerRegistered()
            
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
     * Returns the acks table name.
     *
     * @return "topic_consumer_group_acks"
     */
    protected String getAcksTable() {
        return ACKS_TABLE;
    }
    
    /**
     * Returns the claim timeout in seconds.
     *
     * @return Claim timeout (0 = disabled).
     */
    protected int getClaimTimeoutSeconds() {
        return claimTimeoutSeconds;
    }
    
    /**
     * Returns the message notification queue.
     *
     * @return The blocking queue for trigger notifications.
     */
    protected BlockingQueue<Long> getMessageNotifications() {
        return messageNotifications;
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
     * Initializes the centralized tables for topic messages and acknowledgments.
     * <p>
     * <strong>Centralized Design (Shared Table Structure per Run):</strong>
     * Creates two shared tables in the current schema that are used by ALL topics in this simulation run:
     * <ul>
     *   <li>{@code topic_messages} - Stores messages from all topics</li>
     *   <li>{@code topic_consumer_group_acks} - Tracks acknowledgments across all topics</li>
     * </ul>
     * <p>
     * The {@code topic_name} column enables logical partitioning within these shared tables.
     * <p>
     * <strong>Schema Isolation:</strong>
     * Tables are created in the schema corresponding to the current simulation run.
     * Different runs have completely separate tables in different schemas.
     * <p>
     * <strong>Thread Safety:</strong>
     * This method is called from the constructor (single-threaded initialization).
     * SQL uses {@code CREATE IF NOT EXISTS} for idempotency.
     *
     * @throws SQLException if table creation fails.
     */
    private void initializeCentralizedTables() throws SQLException {
        // Create centralized messages table (shared by all topics)
        String createMessagesSql = """
            CREATE TABLE IF NOT EXISTS topic_messages (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                topic_name VARCHAR(255) NOT NULL,
                message_id VARCHAR(255) NOT NULL,
                timestamp BIGINT NOT NULL,
                envelope BINARY NOT NULL,
                claimed_by VARCHAR(255),
                claimed_at TIMESTAMP,
                claim_version INT DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                
                CONSTRAINT uk_topic_message UNIQUE (topic_name, message_id)
            )
            """;
        
        // Create indexes for messages table (H2 requires separate CREATE INDEX statements)
        String createIndexUnclaimed = 
            "CREATE INDEX IF NOT EXISTS idx_topic_unclaimed ON topic_messages (topic_name, claimed_by, id)";
        String createIndexClaimed = 
            "CREATE INDEX IF NOT EXISTS idx_topic_claimed ON topic_messages (topic_name, claimed_by, claimed_at)";
        String createIndexClaimStatus = 
            "CREATE INDEX IF NOT EXISTS idx_topic_claim_status ON topic_messages (topic_name, claimed_by, claimed_at)";
        
        // Create centralized consumer group acknowledgments table (shared by all topics)
        String createAcksSql = """
            CREATE TABLE IF NOT EXISTS topic_consumer_group_acks (
                topic_name VARCHAR(255) NOT NULL,
                consumer_group VARCHAR(255) NOT NULL,
                message_id VARCHAR(255) NOT NULL,
                acknowledged_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                
                PRIMARY KEY (topic_name, consumer_group, message_id)
            )
            """;
        
        // Create index for acks table
        String createIndexGroupAcks = 
            "CREATE INDEX IF NOT EXISTS idx_topic_group_acks ON topic_consumer_group_acks (topic_name, consumer_group, message_id)";
        
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createMessagesSql);
            stmt.execute(createIndexUnclaimed);
            stmt.execute(createIndexClaimed);
            stmt.execute(createIndexClaimStatus);
            stmt.execute(createAcksSql);
            stmt.execute(createIndexGroupAcks);
            log.debug("Initialized centralized topic tables for resource '{}'", getResourceName());
        }
    }
    
    /**
     * Ensures the H2 trigger is registered for the given simulation run (lazy registration).
     * <p>
     * This method is called by delegates during {@code onSimulationRunSet()}. It registers
     * the trigger only once per schema, using a schema-qualified registry key for run isolation.
     * <p>
     * <strong>Thread Safety:</strong>
     * Multiple delegates may call this concurrently. The method uses double-check locking
     * to ensure the trigger is registered exactly once per schema.
     * <p>
     * <strong>Run Isolation:</strong>
     * Different simulation runs use different schemas (e.g., {@code SIM_RUN1}, {@code SIM_RUN2}).
     * The trigger registry key includes both topic name AND schema name to prevent cross-run
     * notification leakage.
     *
     * @param simulationRunId The simulation run ID (used to derive schema name).
     * @throws RuntimeException if trigger registration fails.
     */
    void ensureTriggerRegistered(String simulationRunId) {
        String schemaName = org.evochora.datapipeline.utils.H2SchemaUtil.toSchemaName(simulationRunId);
        
        // Fast path: already registered for this schema
        if (schemaName.equals(currentSchemaName)) {
            return;
        }
        
        synchronized (triggerLock) {
            // Double-check: another thread may have registered while we waited
            if (schemaName.equals(currentSchemaName)) {
                return;
            }
            
            // Deregister old schema (if switching runs - unlikely but safe)
            if (currentSchemaName != null) {
                H2InsertTrigger.deregisterNotificationQueue(getResourceName(), currentSchemaName);
                log.debug("Deregistered trigger for topic '{}' from old schema '{}'", 
                    getResourceName(), currentSchemaName);
            }
            
            // Register for new schema
            try {
                registerInsertTrigger(schemaName);
                currentSchemaName = schemaName;
                log.debug("Registered trigger for topic '{}' in schema '{}'", 
                    getResourceName(), schemaName);
            } catch (SQLException e) {
                String msg = String.format(
                    "Failed to register H2 trigger for topic '%s' in schema '%s'",
                    getResourceName(), schemaName
                );
                log.error(msg);
                throw new RuntimeException(msg, e);
            }
        }
    }
    
    /**
     * Registers an H2 trigger to provide instant notifications on message INSERT.
     * <p>
     * This enables event-driven message delivery instead of polling. When a message is inserted,
     * the trigger pushes the message ID into the {@link #messageNotifications} queue, instantly
     * waking up waiting readers.
     * <p>
     * <strong>Event-Driven Architecture:</strong>
     * <pre>
     * Writer → INSERT → H2 Trigger → BlockingQueue → Reader (INSTANT!)
     * </pre>
     * Instead of polling every 100ms, readers use {@code BlockingQueue.take()} which blocks
     * efficiently until a notification arrives.
     * <p>
     * <strong>Run Isolation:</strong>
     * The notification queue is registered with a schema-qualified key ({@code topicName:schemaName})
     * to ensure different simulation runs don't interfere with each other.
     *
     * @param schemaName The H2 schema name (e.g., "SIM_20251006_UUID").
     * @throws SQLException if trigger creation fails.
     */
    private void registerInsertTrigger(String schemaName) throws SQLException {
        // With shared table structure, we use a single trigger for all topics
        // The trigger reads topic_name from the inserted row and routes to the correct queue
        String triggerName = "topic_messages_notify_trigger";
        String triggerSql = String.format("""
            CREATE TRIGGER IF NOT EXISTS %s
            AFTER INSERT ON %s
            FOR EACH ROW
            CALL "org.evochora.datapipeline.resources.topics.H2InsertTrigger"
            """, triggerName, MESSAGES_TABLE);
        
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(triggerSql);
        }
        
        // Register this topic's notification queue with schema-qualified key for run isolation
        H2InsertTrigger.registerNotificationQueue(getResourceName(), schemaName, messageNotifications);
        
        log.debug("Registered H2 trigger '{}' for topic '{}' in schema '{}' (event-driven notifications)", 
            triggerName, getResourceName(), schemaName);
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
    public void close() throws Exception {
        super.close();  // Close all delegates
        
        // Deregister trigger if registered
        if (currentSchemaName != null) {
            H2InsertTrigger.deregisterNotificationQueue(getResourceName(), currentSchemaName);
            log.debug("Deregistered trigger for topic '{}' from schema '{}'", 
                getResourceName(), currentSchemaName);
        }
        
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("H2 topic resource '{}' closed", getResourceName());
        }
    }
}

