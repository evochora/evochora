package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.TopicEnvelope;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H2-based reader delegate for topic messages with PreparedStatement pooling.
 * <p>
 * This delegate reads {@link TopicEnvelope} messages from the H2 database using
 * a junction table approach for proper consumer group isolation.
 * <p>
 * <strong>Consumer Group Logic (Junction Table):</strong>
 * <ul>
 *   <li>Messages are read from {@code topic_messages} table</li>
 *   <li>Acknowledgments tracked in {@code topic_consumer_group_acks} table</li>
 *   <li>Each consumer group sees only messages NOT in their acks table</li>
 *   <li>Acknowledgment by one group does NOT affect other groups</li>
 * </ul>
 * <p>
 * <strong>Competing Consumers:</strong>
 * Multiple consumers in the same consumer group use {@code FOR UPDATE SKIP LOCKED}
 * to automatically distribute work without coordination overhead.
 * <p>
 * <strong>Acknowledgment:</strong>
 * Acknowledged messages are recorded in {@code topic_consumer_group_acks} table.
 * Messages remain in {@code topic_messages} permanently (no deletion).
 * <p>
 * <strong>PreparedStatement Pooling:</strong>
 * Each delegate holds a HikariCP connection and prepares SQL statements once during
 * construction. Read and ACK operations reuse these statements, improving performance.
 * <p>
 * <strong>Thread Safety:</strong>
 * Safe for concurrent readers in the same consumer group (competing consumers).
 * Lock conflicts are handled via {@code SKIP LOCKED}.
 *
 * @param <T> The message type.
 */
public class H2TopicReaderDelegate<T extends Message> extends AbstractTopicDelegateReader<H2TopicResource<T>, T, AckToken> {
    
    private static final Logger log = LoggerFactory.getLogger(H2TopicReaderDelegate.class);
    
    private final Connection connection;
    private final String serviceName;  // Service name from config (used in claimed_by)
    private PreparedStatement readStatementWithTimeout;       // SELECT candidates (lazy init after schema switch)
    private PreparedStatement insertClaimStatement;           // INSERT new claim (first time)
    private PreparedStatement updateClaimStatement;           // UPDATE existing claim (reassignment)
    private PreparedStatement ackStatement;                   // Mark as acknowledged
    private final int claimTimeout;  // Store for lazy init
    
    // H2-specific metrics (in addition to abstract delegate metrics)
    private final AtomicLong readErrors = new AtomicLong(0);
    private final AtomicLong ackErrors = new AtomicLong(0);
    private final AtomicLong staleAcksRejected = new AtomicLong(0);
    
    // Claim conflict tracking (O(1) metrics)
    private final SlidingWindowCounter claimAttemptsWindow;
    private final SlidingWindowCounter claimConflictsWindow;
    // Note: readThroughput is now inherited from AbstractTopicDelegateReader
    
    /**
     * Creates a new H2 reader delegate with PreparedStatement pooling.
     * <p>
     * Obtains a connection from HikariCP pool and prepares all SQL statements once.
     *
     * @param parent The parent H2TopicResource.
     * @param context The resource context (must include consumerGroup parameter).
     * @throws RuntimeException if connection or statement preparation fails.
     */
    public H2TopicReaderDelegate(H2TopicResource<T> parent, ResourceContext context) {
        super(parent, context);
        // Note: readThroughput is initialized by AbstractTopicDelegateReader
        
        try {
            // Obtain connection from HikariCP pool (held for delegate lifetime)
            this.connection = parent.getConnection();
            
            // Store service name (used for claimed_by column)
            this.serviceName = context.serviceName();
            
            // Store claim timeout for lazy PreparedStatement initialization
            this.claimTimeout = parent.getClaimTimeoutSeconds();
            
            // Initialize claim conflict tracking with configurable window (default: 5 seconds)
            Config options = parent.getOptions();
            int conflictWindowSeconds = options.hasPath("claimConflictWindowSeconds") 
                ? options.getInt("claimConflictWindowSeconds") 
                : 5;
            this.claimAttemptsWindow = new SlidingWindowCounter(conflictWindowSeconds);
            this.claimConflictsWindow = new SlidingWindowCounter(conflictWindowSeconds);
            
            // Note: PreparedStatements will be created in onSimulationRunSet() after schema switch
            
            log.debug("Created H2 reader delegate for topic '{}', consumerGroup='{}', claimTimeout={}s, conflictWindow={}s",
                parent.getResourceName(), consumerGroup, claimTimeout, conflictWindowSeconds);
            
        } catch (Exception e) {
            log.error("Failed to create H2 reader delegate for topic '{}'", parent.getResourceName());
            throw new RuntimeException("H2 reader delegate initialization failed", e);
        }
    }
    
    @Override
    protected void onSimulationRunSet(String simulationRunId) {
        try {
            // Switch this delegate's connection to the correct schema
            H2SchemaUtil.setSchema(connection, simulationRunId);
            
            // Now prepare all SQL statements (schema is set, tables exist)
            
            // SELECT candidate messages (no locking, race-safe claim via INSERT/UPDATE)
            String sql = String.format("""
                SELECT tm.id, tm.message_id, tm.envelope
                FROM %s tm
                LEFT JOIN %s cg 
                    ON tm.topic_name = cg.topic_name 
                    AND tm.message_id = cg.message_id 
                    AND cg.consumer_group = ?
                WHERE tm.topic_name = ?
                AND (
                    cg.message_id IS NULL
                    OR (cg.acknowledged_at IS NULL
                        AND (cg.claimed_at IS NULL
                             OR cg.claimed_at < DATEADD('SECOND', -?, CURRENT_TIMESTAMP)
                        )
                    )
                )
                ORDER BY tm.id
                LIMIT 10
                """, parent.getMessagesTable(), parent.getConsumerGroupTable());
            this.readStatementWithTimeout = connection.prepareStatement(sql);
            
            // INSERT to claim message (first time this group sees this message)
            String insertClaimSql = String.format("""
                INSERT INTO %s (topic_name, consumer_group, message_id, claimed_by, claimed_at, claim_version, acknowledged_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, 1, NULL)
                """, parent.getConsumerGroupTable());
            this.insertClaimStatement = connection.prepareStatement(insertClaimSql);
            
            // UPDATE to reclaim message (conditional takeover - only if expired and not acked)
            String updateClaimSql = String.format("""
                UPDATE %s
                SET claimed_by = ?,
                    claimed_at = CURRENT_TIMESTAMP,
                    claim_version = claim_version + 1
                WHERE topic_name = ?
                AND consumer_group = ?
                AND message_id = ?
                AND acknowledged_at IS NULL
                AND (claimed_at IS NULL OR claimed_at < DATEADD('SECOND', -?, CURRENT_TIMESTAMP))
                """, parent.getConsumerGroupTable());
            this.updateClaimStatement = connection.prepareStatement(updateClaimSql);
            
            // UPDATE to mark as acknowledged (with version check)
            String ackSql = String.format("""
                UPDATE %s
                SET acknowledged_at = CURRENT_TIMESTAMP,
                    claimed_by = NULL,
                    claimed_at = NULL
                WHERE topic_name = ? 
                AND consumer_group = ? 
                AND message_id = ? 
                AND claim_version = ?
                AND acknowledged_at IS NULL
                """, parent.getConsumerGroupTable());
            this.ackStatement = connection.prepareStatement(ackSql);
            
            log.debug("H2 topic reader '{}' (group='{}') prepared for run: {}", 
                parent.getResourceName(), consumerGroup, simulationRunId);
                
        } catch (SQLException e) {
            String msg = String.format(
                "Failed to prepare reader for simulation run '%s' in topic '%s'",
                simulationRunId, parent.getResourceName()
            );
            log.error(msg);
            recordError("READER_SETUP_FAILED", msg, "SQLException: " + e.getMessage());
            throw new RuntimeException(msg, e);
        }
    }
    
    @Override
    protected ReceivedEnvelope<AckToken> receiveEnvelope(long timeout, TimeUnit unit) throws InterruptedException {
        // Handle blocking mode (timeout=0, unit=null) - poll indefinitely
        boolean blockIndefinitely = (unit == null);
        long timeoutMs = blockIndefinitely ? Long.MAX_VALUE : unit.toMillis(timeout);
        long startTime = System.currentTimeMillis();
        long pollIntervalMs = 500;
        
        while (true) {
            // Try to read a message
            ReceivedEnvelope<AckToken> message = tryReadMessage();
            if (message != null) {
                return message;
            }
            
            // Check if timeout expired
            long elapsedMs = System.currentTimeMillis() - startTime;
            if (elapsedMs >= timeoutMs) {
                return null; // Timeout reached, no message available
            }
            
            // Sleep for poll interval or remaining time, whichever is shorter
            long remainingMs = timeoutMs - elapsedMs;
            long sleepMs = Math.min(pollIntervalMs, remainingMs);
            Thread.sleep(sleepMs);
        }
    }
    
    /**
     * Attempts to claim and read one message from the database.
     * <p>
     * Uses atomic UPDATE...RETURNING to claim a message for this consumer group.
     * Handles both normal claims and stuck message reassignment.
     *
     * @return The received envelope with AckToken, or null if no message available.
     */
    private ReceivedEnvelope<AckToken> tryReadMessage() {
        int claimTimeout = parent.getClaimTimeoutSeconds();
        
        // Clear interrupt flag temporarily to allow H2 operations
        // H2 Database's internal locking mechanism (MVMap.tryLock()) uses Thread.sleep()
        // which throws InterruptedException if thread is interrupted
        boolean wasInterrupted = Thread.interrupted();
        try {
            // Use same statement for both (timeout is always in WHERE clause, just with 0 or actual value)
            PreparedStatement stmt = readStatementWithTimeout;
            
            // Step 1: SELECT candidate messages (up to 10)
            stmt.setString(1, consumerGroup);                    // consumer_group filter in JOIN
            stmt.setString(2, parent.getResourceName());         // topic_name filter
            stmt.setInt(3, claimTimeout > 0 ? claimTimeout : Integer.MAX_VALUE);  // timeout (or very large value if disabled)
            
            try (ResultSet rs = stmt.executeQuery()) {
                // Try to claim each candidate until one succeeds
                while (rs.next()) {
                    long rowId = rs.getLong("id");
                    String messageId = rs.getString("message_id");
                    byte[] envelopeBytes = rs.getBytes("envelope");
                    
                    // Track claim attempt (O(1) metric)
                    claimAttemptsWindow.recordCount();
                    
                    // Step 2: Try to claim this message atomically
                    boolean claimed = false;
                    int newClaimVersion = 1;
                    boolean isReassignment = false;
                    
                    try {
                        // Try INSERT first (new claim for this consumer group)
                        insertClaimStatement.setString(1, parent.getResourceName());  // topic_name
                        insertClaimStatement.setString(2, consumerGroup);             // consumer_group
                        insertClaimStatement.setString(3, messageId);                 // message_id
                        insertClaimStatement.setString(4, serviceName);               // claimed_by
                        int rowsInserted = insertClaimStatement.executeUpdate();
                        if (rowsInserted > 0) {
                            claimed = true;
                            newClaimVersion = 1;
                        }
                    } catch (SQLException insertEx) {
                        // Row exists - try conditional UPDATE (takeover if expired)
                        if (insertEx.getErrorCode() == 23505) {  // H2 duplicate key error
                            updateClaimStatement.setString(1, serviceName);               // claimed_by
                            updateClaimStatement.setString(2, parent.getResourceName());  // topic_name
                            updateClaimStatement.setString(3, consumerGroup);             // consumer_group
                            updateClaimStatement.setString(4, messageId);                 // message_id
                            updateClaimStatement.setInt(5, claimTimeout > 0 ? claimTimeout : Integer.MAX_VALUE);  // timeout
                            int rowsUpdated = updateClaimStatement.executeUpdate();
                            if (rowsUpdated > 0) {
                                claimed = true;
                                isReassignment = true;
                                // Fetch new claim_version
                                try (PreparedStatement versionStmt = connection.prepareStatement(
                                    "SELECT claim_version FROM " + parent.getConsumerGroupTable() + 
                                    " WHERE topic_name = ? AND consumer_group = ? AND message_id = ?")) {
                                    versionStmt.setString(1, parent.getResourceName());
                                    versionStmt.setString(2, consumerGroup);
                                    versionStmt.setString(3, messageId);
                                    try (ResultSet versionRs = versionStmt.executeQuery()) {
                                        if (versionRs.next()) {
                                            newClaimVersion = versionRs.getInt("claim_version");
                                        }
                                    }
                                }
                            }
                        } else {
                            throw insertEx;  // Unexpected SQL error
                        }
                    }
                    
                    if (!claimed) {
                        // Failed to claim this candidate - try next one
                        claimConflictsWindow.recordCount();
                        log.debug("Failed to claim candidate message in topic '{}': messageId={} (already claimed or acked)", 
                            parent.getResourceName(), messageId);
                        continue;  // Try next candidate
                    }
                    
                    // Successfully claimed! Parse and return
                    TopicEnvelope envelope = TopicEnvelope.parseFrom(envelopeBytes);
                    
                    // Track metrics
                    parent.recordRead();
                    
                    // Log reassignment if applicable
                    if (isReassignment) {
                        log.debug("Reassigned stuck message from topic '{}': messageId={}, serviceName={}, claimVersion={}, timeout={}s", 
                            parent.getResourceName(), messageId, serviceName, newClaimVersion, claimTimeout);
                        parent.recordStuckMessageReassignment();
                    } else {
                        log.debug("Claimed message from topic '{}': messageId={}, serviceName={}, claimVersion={}", 
                            parent.getResourceName(), messageId, serviceName, newClaimVersion);
                    }
                    
                    // Return with ACK token
                    AckToken ackToken = new AckToken(rowId, newClaimVersion);
                    return new ReceivedEnvelope<>(envelope, ackToken);
                }
            }
        } catch (SQLException e) {
            readErrors.incrementAndGet();
            log.warn("Failed to query/claim message from topic '{}': consumerGroup={}, errorCode={}, sqlState={}, message='{}'", 
                parent.getResourceName(), consumerGroup, e.getErrorCode(), e.getSQLState(), e.getMessage());
            recordError("CLAIM_FAILED", "SQL error during claim attempt", 
                "Topic: " + parent.getResourceName() + ", ConsumerGroup: " + consumerGroup);
        } catch (InvalidProtocolBufferException e) {
            readErrors.incrementAndGet();
            log.warn("Failed to parse envelope from topic '{}': consumerGroup={}", 
                parent.getResourceName(), consumerGroup);
            recordError("PARSE_FAILED", "Protobuf parse failed", 
                "Topic: " + parent.getResourceName() + ", ConsumerGroup: " + consumerGroup);
        } finally {
            // Restore interrupt flag for proper shutdown handling
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
        
        return null;  // No message available or all claims failed
    }
    
    @Override
    protected void acknowledgeMessage(AckToken token) {
        long rowId = token.rowId();
        int claimVersion = token.claimVersion();
        
        // Clear interrupt flag temporarily to allow H2 operations
        // H2 Database's internal locking mechanism (MVMap.tryLock()) uses Thread.sleep()
        // which throws InterruptedException if thread is interrupted
        boolean wasInterrupted = Thread.interrupted();
        try {
            // Start transaction - all 3 steps must be atomic
            connection.setAutoCommit(false);
            
            // Step 1: Get message_id for this row ID (lightweight SELECT by primary key)
            String messageId;
            try {
                String getMessageIdSql = String.format(
                    "SELECT message_id FROM %s WHERE id = ?", parent.getMessagesTable());
                try (PreparedStatement stmt = connection.prepareStatement(getMessageIdSql)) {
                    stmt.setLong(1, rowId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            log.debug("Message not found in topic '{}': rowId={}", parent.getResourceName(), rowId);
                            connection.rollback();
                            connection.setAutoCommit(true);
                            return;
                        }
                        messageId = rs.getString("message_id");
                    }
                }
            } catch (SQLException e) {
                connection.rollback();
                connection.setAutoCommit(true);
                log.warn("Failed to get message_id for acknowledgment in topic '{}': rowId={}, errorCode={}, sqlState={}, message='{}'", 
                    parent.getResourceName(), rowId, e.getErrorCode(), e.getSQLState(), e.getMessage());
                recordError("ACK_LOOKUP_FAILED", "Failed to get message_id", 
                    "Topic: " + parent.getResourceName() + ", RowId: " + rowId);
                return;
            }
            
            // Step 2: Mark as acknowledged in consumer group table with version check
            try {
                ackStatement.setString(1, parent.getResourceName());  // topic_name
                ackStatement.setString(2, consumerGroup);             // consumer_group
                ackStatement.setString(3, messageId);                 // message_id
                ackStatement.setInt(4, claimVersion);                 // claim_version (WHERE clause)
                int rowsUpdated = ackStatement.executeUpdate();
                
                if (rowsUpdated == 0) {
                    // Stale ACK detected - claim version mismatch or entry doesn't exist
                    staleAcksRejected.incrementAndGet();
                    log.warn("Stale ACK rejected in topic '{}': messageId={}, expectedVersion={}", 
                        parent.getResourceName(), messageId, claimVersion);
                    recordError("STALE_ACK_REJECTED", "Stale acknowledgment rejected", 
                        "Topic: " + parent.getResourceName() + ", MessageId: " + messageId + 
                        ", ExpectedVersion: " + claimVersion);
                    connection.rollback();
                    connection.setAutoCommit(true);
                    return;
                }
                
                // Success - commit transaction
                connection.commit();
                connection.setAutoCommit(true);
                
                // Track metrics
                parent.recordAcknowledge();
                
                log.debug("Acknowledged message in topic '{}': messageId={}, claimVersion={}", 
                    parent.getResourceName(), messageId, claimVersion);
                
            } catch (SQLException e) {
                connection.rollback();
                connection.setAutoCommit(true);
                ackErrors.incrementAndGet();
                log.warn("Failed to acknowledge message in topic '{}': messageId={}, errorCode={}, sqlState={}, message='{}'", 
                    parent.getResourceName(), messageId, e.getErrorCode(), e.getSQLState(), e.getMessage());
                recordError("ACK_FAILED", "Failed to acknowledge message", 
                    "Topic: " + parent.getResourceName() + ", MessageId: " + messageId);
            }
            
        } catch (SQLException e) {
            // Transaction management failed
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException rollbackEx) {
                log.warn("Failed to rollback transaction in topic '{}': errorCode={}, sqlState={}, message='{}'", 
                    parent.getResourceName(), rollbackEx.getErrorCode(), rollbackEx.getSQLState(), rollbackEx.getMessage());
            }
            ackErrors.incrementAndGet();  // Track errors
            log.warn("Failed to acknowledge message in topic '{}': rowId={}, errorCode={}, sqlState={}, message='{}'", 
                parent.getResourceName(), rowId, e.getErrorCode(), e.getSQLState(), e.getMessage());
            recordError("ACK_TRANSACTION_FAILED", "Transaction management failed", 
                "Topic: " + parent.getResourceName() + ", RowId: " + rowId);
        } finally {
            // Restore interrupt flag for proper shutdown handling
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Includes parent + abstract delegate metrics (including delegate_read_throughput_per_sec)
        
        // H2-specific error metrics
        metrics.put("delegate_read_errors", readErrors.get());
        metrics.put("delegate_ack_errors", ackErrors.get());
        metrics.put("delegate_stale_acks_rejected", staleAcksRejected.get());
        
        // Claim conflict metrics (O(1) calculation)
        long attemptsInWindow = claimAttemptsWindow.getWindowSum();
        long conflictsInWindow = claimConflictsWindow.getWindowSum();
        double conflictRatio = attemptsInWindow > 0 
            ? (double) conflictsInWindow / attemptsInWindow 
            : 0.0;
        
        metrics.put("claim_attempts_in_window", attemptsInWindow);
        metrics.put("claim_conflicts_in_window", conflictsInWindow);
        metrics.put("claim_conflict_ratio", conflictRatio);
    }
    
    @Override
    public UsageState getUsageState(String usageType) {
        // Validate usage type
        if (!"topic-read".equals(usageType)) {
            throw new IllegalArgumentException(String.format(
                "Reader delegate only supports 'topic-read', got: '%s'", usageType));
        }
        
        // Check THIS delegate's state (not parent!)
        try {
            if (connection == null || connection.isClosed()) {
                return UsageState.FAILED;
            }
            if (readStatementWithTimeout == null || readStatementWithTimeout.isClosed()) {
                return UsageState.FAILED;
            }
            // Note: readStatementNoTimeout was removed (only readStatementWithTimeout is used now)
            if (insertClaimStatement == null || insertClaimStatement.isClosed()) {
                return UsageState.FAILED;
            }
            if (updateClaimStatement == null || updateClaimStatement.isClosed()) {
                return UsageState.FAILED;
            }
            if (ackStatement == null || ackStatement.isClosed()) {
                return UsageState.FAILED;
            }
            
            // This delegate is ready to read
            return UsageState.ACTIVE;
            
        } catch (SQLException e) {
            return UsageState.FAILED;
        }
    }
    
    @Override
    public void close() throws Exception {
        log.debug("Closing H2 reader delegate for topic '{}', consumerGroup='{}'",
            parent.getResourceName(), consumerGroup);
        
        // Close all PreparedStatements
        if (readStatementWithTimeout != null && !readStatementWithTimeout.isClosed()) {
            readStatementWithTimeout.close();
        }
        // Note: readStatementNoTimeout was removed (only readStatementWithTimeout is used now)
        if (insertClaimStatement != null && !insertClaimStatement.isClosed()) {
            insertClaimStatement.close();
        }
        if (updateClaimStatement != null && !updateClaimStatement.isClosed()) {
            updateClaimStatement.close();
        }
        if (ackStatement != null && !ackStatement.isClosed()) {
            ackStatement.close();
        }
        
        // Return connection to HikariCP pool
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}

