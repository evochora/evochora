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
    private final PreparedStatement readStatementWithTimeout;
    private final PreparedStatement readStatementNoTimeout;
    private final PreparedStatement ackInsertStatement;
    private final PreparedStatement ackReleaseStatement;
    private final BlockingQueue<Long> notificationQueue;
    
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
            
            int claimTimeout = parent.getClaimTimeoutSeconds();
            
            // Prepare READ statement WITH stuck message reassignment (claimTimeout > 0)
            if (claimTimeout > 0) {
                String sqlWithTimeout = String.format("""
                    UPDATE %s tm
                    SET claimed_by = ?, 
                        claimed_at = CURRENT_TIMESTAMP,
                        claim_version = claim_version + 1
                    WHERE id = (
                        SELECT tm2.id 
                        FROM %s tm2
                        LEFT JOIN %s cga 
                            ON tm2.topic_name = cga.topic_name 
                            AND tm2.message_id = cga.message_id 
                            AND cga.consumer_group = ?
                        WHERE cga.message_id IS NULL
                        AND tm2.topic_name = ?
                        AND (
                            tm2.claimed_by IS NULL 
                            OR tm2.claimed_at < DATEADD('SECOND', -%d, CURRENT_TIMESTAMP)
                        )
                        ORDER BY tm2.id 
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                    )
                    RETURNING id, message_id, envelope, claimed_by AS previous_claim, claim_version
                    """, parent.getMessagesTable(), parent.getMessagesTable(), parent.getAcksTable(), claimTimeout);
                this.readStatementWithTimeout = connection.prepareStatement(sqlWithTimeout);
            } else {
                this.readStatementWithTimeout = null;
            }
            
            // Prepare READ statement WITHOUT stuck message reassignment (always needed)
            String sqlNoTimeout = String.format("""
                UPDATE %s tm
                SET claimed_by = ?, 
                    claimed_at = CURRENT_TIMESTAMP,
                    claim_version = claim_version + 1
                WHERE id = (
                    SELECT tm2.id 
                    FROM %s tm2
                    LEFT JOIN %s cga 
                        ON tm2.topic_name = cga.topic_name 
                        AND tm2.message_id = cga.message_id 
                        AND cga.consumer_group = ?
                    WHERE cga.message_id IS NULL
                    AND tm2.topic_name = ?
                    AND tm2.claimed_by IS NULL
                    ORDER BY tm2.id 
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, message_id, envelope, NULL AS previous_claim, claim_version
                """, parent.getMessagesTable(), parent.getMessagesTable(), parent.getAcksTable());
            this.readStatementNoTimeout = connection.prepareStatement(sqlNoTimeout);
            
            // Prepare ACK MERGE statement (idempotent)
            String ackMergeSql = String.format(
                "MERGE INTO %s (topic_name, consumer_group, message_id) KEY(topic_name, consumer_group, message_id) VALUES (?, ?, ?)",
                parent.getAcksTable()
            );
            this.ackInsertStatement = connection.prepareStatement(ackMergeSql);
            
            // Prepare ACK RELEASE statement WITH VERSION CHECK
            String ackReleaseSql = String.format(
                "UPDATE %s SET claimed_by = NULL, claimed_at = NULL WHERE id = ? AND claim_version = ?",
                parent.getMessagesTable()
            );
            this.ackReleaseStatement = connection.prepareStatement(ackReleaseSql);
            
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
            // Same schema setup as writer
            boolean wasAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            
            try {
                H2SchemaUtil.createSchemaIfNotExists(connection, simulationRunId);
                H2SchemaUtil.setSchema(connection, simulationRunId);
                connection.setAutoCommit(wasAutoCommit);
                
                log.debug("H2 topic reader '{}' (group='{}') switched to schema for run: {}",
                    parent.getResourceName(), consumerGroup, simulationRunId);
                    
            } catch (SQLException schemaError) {
                connection.setAutoCommit(wasAutoCommit);
                throw schemaError;
            }
            
            // Ensure parent has trigger registered for this schema
            parent.ensureTriggerRegistered(simulationRunId);
                
        } catch (SQLException e) {
            String msg = String.format(
                "Failed to set H2 schema for simulation run '%s' in topic reader '%s'",
                simulationRunId, parent.getResourceName()
            );
            log.error(msg);
            recordError("SCHEMA_SETUP_FAILED", msg, "SQLException: " + e.getMessage());
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
            // Reuse PreparedStatement (no SQL parsing overhead!)
            stmt.setString(1, consumerId);                  // claimed_by
            stmt.setString(2, consumerGroup);               // consumer_group filter
            stmt.setString(3, parent.getResourceName());    // topic_name filter
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    String messageId = rs.getString("message_id");
                    byte[] envelopeBytes = rs.getBytes("envelope");
                    String previousClaim = rs.getString("previous_claim");
                    int claimVersion = rs.getInt("claim_version");
                    
                    TopicEnvelope envelope = TopicEnvelope.parseFrom(envelopeBytes);
                    
                    // Track metrics (O(1) operations, cannot fail)
                    parent.recordRead();  // Parent's aggregate counter + throughput
                    // Note: messagesReceived + readThroughput are tracked by AbstractTopicDelegateReader in receive() method
                    
                    // Check if this was a stuck message reassignment
                    if (previousClaim != null && !previousClaim.equals(consumerId)) {
                        log.warn("Reassigned stuck message from topic '{}': messageId={}, previousClaim={}, newConsumer={}, claimVersion={}, timeout={}s", 
                            parent.getResourceName(), messageId, previousClaim, consumerId, claimVersion, claimTimeout);
                        recordError("STUCK_MESSAGE_REASSIGNED", "Message claim timeout expired", 
                            "Topic: " + parent.getResourceName() + ", MessageId: " + messageId + 
                            ", PreviousClaim: " + previousClaim + ", ClaimVersion: " + claimVersion + ", Timeout: " + claimTimeout + "s");
                        parent.recordStuckMessageReassignment();
                    } else {
                        log.debug("Claimed message from topic '{}': messageId={}, consumerId={}, claimVersion={}", 
                            parent.getResourceName(), messageId, consumerId, claimVersion);
                    }
                    
                    // ACK token contains row ID and claim version (for stale ACK detection)
                    AckToken ackToken = new AckToken(id, claimVersion);
                    return new ReceivedEnvelope<>(envelope, ackToken);
                }
            }
        } catch (SQLException e) {
            readErrors.incrementAndGet();  // Track errors
            log.warn("Failed to claim message from topic '{}': consumerGroup={}", parent.getResourceName(), consumerGroup);
            recordError("CLAIM_FAILED", "SQL UPDATE...RETURNING failed", 
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
            
            // Step 2: Record acknowledgment in junction table (idempotent MERGE)
            try {
                ackInsertStatement.setString(1, parent.getResourceName());  // topic_name
                ackInsertStatement.setString(2, consumerGroup);             // consumer_group
                ackInsertStatement.setString(3, messageId);                   // message_id
                ackInsertStatement.executeUpdate();
            } catch (SQLException e) {
                connection.rollback();
                connection.setAutoCommit(true);
                ackErrors.incrementAndGet();  // Track errors
                log.warn("Failed to record acknowledgment in topic '{}': messageId={}", 
                    parent.getResourceName(), messageId);
                recordError("ACK_RECORD_FAILED", "Failed to record acknowledgment", 
                    "Topic: " + parent.getResourceName() + ", MessageId: " + messageId);
                return;
            }
            
            // Step 3: Release claim with version check (prevents stale ACKs)
            try {
                ackReleaseStatement.setLong(1, rowId);
                ackReleaseStatement.setInt(2, claimVersion);
                int rowsUpdated = ackReleaseStatement.executeUpdate();
                
                if (rowsUpdated == 0) {
                    // Stale ACK detected - claim version mismatch
                    staleAcksRejected.incrementAndGet();  // Track stale ACKs
                    log.warn("Stale ACK rejected in topic '{}': messageId={}, rowId={}, expectedVersion={}, timeout={}s", 
                        parent.getResourceName(), messageId, rowId, claimVersion, parent.getClaimTimeoutSeconds());
                    recordError("STALE_ACK_REJECTED", "Stale acknowledgment rejected", 
                        "Topic: " + parent.getResourceName() + ", MessageId: " + messageId + 
                        ", RowId: " + rowId + ", ExpectedVersion: " + claimVersion);
                    connection.rollback();
                    connection.setAutoCommit(true);
                    return;
                }
                
                // Success - commit transaction
                connection.commit();
                connection.setAutoCommit(true);
                
                // Track metrics (O(1) operations, cannot fail)
                parent.recordAcknowledge();  // Parent's aggregate counter
                
                log.debug("Acknowledged message in topic '{}': messageId={}, rowId={}, claimVersion={}", 
                    parent.getResourceName(), messageId, rowId, claimVersion);
                
            } catch (SQLException e) {
                connection.rollback();
                connection.setAutoCommit(true);
                ackErrors.incrementAndGet();  // Track errors
                log.warn("Failed to release claim in topic '{}': messageId={}, rowId={}", 
                    parent.getResourceName(), messageId, rowId);
                recordError("ACK_RELEASE_FAILED", "Failed to release claim", 
                    "Topic: " + parent.getResourceName() + ", MessageId: " + messageId + ", RowId: " + rowId);
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
            if (ackInsertStatement == null || ackInsertStatement.isClosed()) {
                return UsageState.FAILED;
            }
            if (ackReleaseStatement == null || ackReleaseStatement.isClosed()) {
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
        if (ackInsertStatement != null && !ackInsertStatement.isClosed()) {
            ackInsertStatement.close();
        }
        if (ackReleaseStatement != null && !ackReleaseStatement.isClosed()) {
            ackReleaseStatement.close();
        }
        
        // Return connection to HikariCP pool
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}

