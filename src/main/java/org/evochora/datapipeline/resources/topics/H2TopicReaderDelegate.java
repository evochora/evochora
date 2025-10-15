package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
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
import java.util.concurrent.BlockingQueue;
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
    private PreparedStatement readStatementWithTimeout;       // Lazy init after schema switch
    private PreparedStatement readStatementNoTimeout;         // Lazy init after schema switch
    private PreparedStatement insertClaimStatement;           // INSERT new claim (first time)
    private PreparedStatement updateClaimStatement;           // UPDATE existing claim (reassignment)
    private PreparedStatement ackStatement;                   // Mark as acknowledged
    private final BlockingQueue<Long> notificationQueue;
    private final int claimTimeout;  // Store for lazy init
    
    // H2-specific metrics (in addition to abstract delegate metrics)
    private final AtomicLong readErrors = new AtomicLong(0);
    private final AtomicLong ackErrors = new AtomicLong(0);
    private final AtomicLong staleAcksRejected = new AtomicLong(0);
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
        
        // Get notification queue from parent (event-driven delivery)
        this.notificationQueue = parent.getMessageNotifications();
        
        try {
            // Obtain connection from HikariCP pool (held for delegate lifetime)
            this.connection = parent.getConnection();
            
            // Store claim timeout for lazy PreparedStatement initialization
            this.claimTimeout = parent.getClaimTimeoutSeconds();
            
            // Note: PreparedStatements will be created in onSimulationRunSet() after schema switch
            
            log.debug("Created H2 reader delegate for topic '{}', consumerGroup='{}', claimTimeout={}s",
                parent.getResourceName(), consumerGroup, claimTimeout);
            
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
            
            // SELECT with timeout (stuck message reassignment)
            if (claimTimeout > 0) {
                String sqlWithTimeout = String.format("""
                    SELECT tm.id, tm.message_id, tm.envelope, cg.claimed_by AS previous_claim, cg.claim_version
                    FROM %s tm
                    LEFT JOIN %s cg 
                        ON tm.topic_name = cg.topic_name 
                        AND tm.message_id = cg.message_id 
                        AND cg.consumer_group = ?
                    WHERE tm.topic_name = ?
                    AND (
                        cg.message_id IS NULL
                        OR (cg.acknowledged_at IS NULL 
                            AND (cg.claimed_by IS NULL 
                                 OR cg.claimed_at < DATEADD('SECOND', -%d, CURRENT_TIMESTAMP)))
                    )
                    ORDER BY tm.id 
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                    """, parent.getMessagesTable(), parent.getConsumerGroupTable(), claimTimeout);
                this.readStatementWithTimeout = connection.prepareStatement(sqlWithTimeout);
            }
            
            // SELECT without timeout
            String sqlNoTimeout = String.format("""
                SELECT tm.id, tm.message_id, tm.envelope, cg.claimed_by AS previous_claim, cg.claim_version
                FROM %s tm
                LEFT JOIN %s cg 
                    ON tm.topic_name = cg.topic_name 
                    AND tm.message_id = cg.message_id 
                    AND cg.consumer_group = ?
                WHERE tm.topic_name = ?
                AND (
                    cg.message_id IS NULL
                    OR (cg.acknowledged_at IS NULL AND cg.claimed_by IS NULL)
                )
                ORDER BY tm.id 
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """, parent.getMessagesTable(), parent.getConsumerGroupTable());
            this.readStatementNoTimeout = connection.prepareStatement(sqlNoTimeout);
            
            // INSERT to claim message (first time this group sees this message)
            String insertClaimSql = String.format("""
                INSERT INTO %s (topic_name, consumer_group, message_id, claimed_by, claimed_at, claim_version, acknowledged_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, 1, NULL)
                """, parent.getConsumerGroupTable());
            this.insertClaimStatement = connection.prepareStatement(insertClaimSql);
            
            // UPDATE to reclaim message (stuck message reassignment)
            String updateClaimSql = String.format("""
                UPDATE %s
                SET claimed_by = ?,
                    claimed_at = CURRENT_TIMESTAMP,
                    claim_version = claim_version + 1
                WHERE topic_name = ?
                AND consumer_group = ?
                AND message_id = ?
                AND acknowledged_at IS NULL
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
        while (true) {
            // Try to read a message immediately
            ReceivedEnvelope<AckToken> message = tryReadMessage();
            if (message != null) {
                return message;
            }
            
            // No message available - wait for notification or timeout
            if (timeout == 0 && unit == null) {
                // Block indefinitely - wait for trigger notification
                notificationQueue.take();  // Blocks until notification arrives
                // Loop back to try reading again
            } else {
                // Wait with timeout
                Long notification = notificationQueue.poll(timeout, unit);
                if (notification == null) {
                    return null;  // Timeout - no message
                }
                // Loop back to try reading again
            }
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
        // Generate unique consumer ID for this delegate instance
        String consumerId = parent.getResourceName() + ":" + consumerGroup + ":" + System.currentTimeMillis();
        int claimTimeout = parent.getClaimTimeoutSeconds();
        
        // Select appropriate prepared statement based on claimTimeout
        PreparedStatement stmt = (claimTimeout > 0) ? readStatementWithTimeout : readStatementNoTimeout;
        
        try {
            // Start transaction (SELECT FOR UPDATE requires a transaction to hold the lock)
            connection.setAutoCommit(false);
            
            try {
                // Step 1: SELECT FOR UPDATE (locks the row until commit/rollback)
                stmt.setString(1, consumerGroup);               // consumer_group filter
                stmt.setString(2, parent.getResourceName());    // topic_name filter
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        long id = rs.getLong("id");
                        String messageId = rs.getString("message_id");
                        byte[] envelopeBytes = rs.getBytes("envelope");
                        String previousClaim = rs.getString("previous_claim");
                        int currentClaimVersion = rs.getInt("claim_version");  // 0 if NULL (new entry)
                        
                        // Step 2: Claim the message in consumer group table
                        // Try INSERT first (new message for this group), fallback to UPDATE (reassignment)
                        boolean claimed = false;
                        int newClaimVersion = 1;
                        
                        try {
                            // Try INSERT (first time this group sees this message)
                            insertClaimStatement.setString(1, parent.getResourceName());  // topic_name
                            insertClaimStatement.setString(2, consumerGroup);             // consumer_group
                            insertClaimStatement.setString(3, messageId);                 // message_id
                            insertClaimStatement.setString(4, consumerId);                // claimed_by
                            insertClaimStatement.executeUpdate();
                            claimed = true;
                            newClaimVersion = 1;
                        } catch (SQLException insertEx) {
                            // Duplicate key - entry exists, use UPDATE instead
                            if (insertEx.getErrorCode() == 23505) {  // H2 duplicate key error
                                updateClaimStatement.setString(1, consumerId);                // claimed_by
                                updateClaimStatement.setString(2, parent.getResourceName());  // topic_name
                                updateClaimStatement.setString(3, consumerGroup);             // consumer_group
                                updateClaimStatement.setString(4, messageId);                 // message_id
                                int rowsUpdated = updateClaimStatement.executeUpdate();
                                if (rowsUpdated > 0) {
                                    claimed = true;
                                    newClaimVersion = currentClaimVersion + 1;
                                }
                            } else {
                                throw insertEx;  // Unexpected SQL error
                            }
                        }
                        
                        if (!claimed) {
                            // Failed to claim (shouldn't happen with FOR UPDATE SKIP LOCKED)
                            connection.rollback();
                            connection.setAutoCommit(true);
                            log.warn("Failed to claim message in topic '{}': messageId={}", 
                                parent.getResourceName(), messageId);
                            return null;
                        }
                        
                        // Commit transaction (releases lock)
                        connection.commit();
                        connection.setAutoCommit(true);
                        
                        // Parse envelope
                        TopicEnvelope envelope = TopicEnvelope.parseFrom(envelopeBytes);
                        
                        // Track metrics (O(1) operations, cannot fail)
                        parent.recordRead();  // Parent's aggregate counter + throughput
                        // Note: messagesReceived + readThroughput are tracked by AbstractTopicDelegateReader in receive() method
                        
                        // Check if this was a stuck message reassignment
                        if (previousClaim != null && !previousClaim.equals(consumerId)) {
                            log.warn("Reassigned stuck message from topic '{}': messageId={}, previousClaim={}, newConsumer={}, claimVersion={}, timeout={}s", 
                                parent.getResourceName(), messageId, previousClaim, consumerId, newClaimVersion, claimTimeout);
                            recordError("STUCK_MESSAGE_REASSIGNED", "Message claim timeout expired", 
                                "Topic: " + parent.getResourceName() + ", MessageId: " + messageId + 
                                ", PreviousClaim: " + previousClaim + ", ClaimVersion: " + newClaimVersion + ", Timeout: " + claimTimeout + "s");
                            parent.recordStuckMessageReassignment();
                        } else {
                            log.debug("Claimed message from topic '{}': messageId={}, consumerId={}, claimVersion={}", 
                                parent.getResourceName(), messageId, consumerId, newClaimVersion);
                        }
                        
                        // ACK token contains message ID and claim version (for stale ACK detection)
                        AckToken ackToken = new AckToken(id, newClaimVersion);
                        return new ReceivedEnvelope<>(envelope, ackToken);
                    } else {
                        // No message available - rollback and restore auto-commit
                        connection.rollback();
                        connection.setAutoCommit(true);
                    }
                }
            } catch (Exception e) {
                // Rollback on any error
                connection.rollback();
                connection.setAutoCommit(true);
                throw e;  // Re-throw to outer catch block
            }
        } catch (SQLException e) {
            readErrors.incrementAndGet();  // Track errors
            log.warn("Failed to claim message from topic '{}': consumerGroup={}", parent.getResourceName(), consumerGroup);
            recordError("CLAIM_FAILED", "SQL transaction failed (SELECT FOR UPDATE + UPDATE)", 
                "Topic: " + parent.getResourceName() + ", ConsumerGroup: " + consumerGroup);
        } catch (InvalidProtocolBufferException e) {
            readErrors.incrementAndGet();  // Track errors
            log.warn("Failed to parse envelope from topic '{}': consumerGroup={}", parent.getResourceName(), consumerGroup);
            recordError("PARSE_FAILED", "Protobuf parse failed", 
                "Topic: " + parent.getResourceName() + ", ConsumerGroup: " + consumerGroup);
        }
        
        return null;
    }
    
    @Override
    protected void acknowledgeMessage(AckToken token) {
        long rowId = token.rowId();
        int claimVersion = token.claimVersion();
        
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
                log.warn("Failed to get message_id for acknowledgment in topic '{}': rowId={}", 
                    parent.getResourceName(), rowId);
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
                log.warn("Failed to acknowledge message in topic '{}': messageId={}", 
                    parent.getResourceName(), messageId);
                recordError("ACK_FAILED", "Failed to acknowledge message", 
                    "Topic: " + parent.getResourceName() + ", MessageId: " + messageId);
            }
            
        } catch (SQLException e) {
            // Transaction management failed
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException rollbackEx) {
                log.warn("Failed to rollback transaction in topic '{}'", parent.getResourceName());
            }
            ackErrors.incrementAndGet();  // Track errors
            log.warn("Failed to acknowledge message in topic '{}': rowId={}", parent.getResourceName(), rowId);
            recordError("ACK_TRANSACTION_FAILED", "Transaction management failed", 
                "Topic: " + parent.getResourceName() + ", RowId: " + rowId);
        }
    }
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Includes parent + abstract delegate metrics (including delegate_read_throughput_per_sec)
        
        // H2-specific metrics (errors only, throughput is in abstract)
        metrics.put("delegate_read_errors", readErrors.get());
        metrics.put("delegate_ack_errors", ackErrors.get());
        metrics.put("delegate_stale_acks_rejected", staleAcksRejected.get());
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
            if (readStatementNoTimeout == null || readStatementNoTimeout.isClosed()) {
                return UsageState.FAILED;
            }
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
        if (readStatementNoTimeout != null && !readStatementNoTimeout.isClosed()) {
            readStatementNoTimeout.close();
        }
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

