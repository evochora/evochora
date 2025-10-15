package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Message;
import org.evochora.datapipeline.api.contracts.TopicEnvelope;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H2-based writer delegate for topic messages with PreparedStatement pooling.
 * <p>
 * This delegate writes {@link TopicEnvelope} messages directly to the H2 database
 * using SQL INSERT statements. Multiple writers can operate concurrently thanks
 * to H2's MVCC (Multi-Version Concurrency Control) and HikariCP connection pooling.
 * <p>
 * <strong>PreparedStatement Pooling:</strong>
 * Each delegate creates a single {@link PreparedStatement} during construction and
 * reuses it for all writes. This eliminates the overhead of preparing the same SQL
 * statement repeatedly, improving write performance by ~30-50%.
 * <p>
 * <strong>HikariCP Integration:</strong>
 * The connection is obtained from HikariCP pool during construction and held for
 * the lifetime of this delegate. The PreparedStatement is created on this connection
 * and reused for all writes.
 * <p>
 * <strong>Delegate-Specific Metrics:</strong>
 * Tracks per-service metrics in addition to parent's aggregate metrics:
 * <ul>
 *   <li>{@code delegate_messages_written} - Total messages written by this delegate</li>
 *   <li>{@code delegate_write_throughput_per_sec} - Messages/sec over sliding window (configurable)</li>
 *   <li>{@code delegate_write_errors} - Write failures for this delegate</li>
 * </ul>
 * <p>
 * <strong>Enhanced UsageState:</strong>
 * Checks delegate-specific state (connection, statement) before falling back to parent state.
 * Returns {@code FAILED} if this delegate's connection or PreparedStatement is closed.
 * <p>
 * <strong>Thread Safety:</strong>
 * This class is NOT thread-safe for concurrent writes from the same delegate instance.
 * Each service should have its own writer delegate instance (as per ServiceManager design).
 * <p>
 * <strong>Error Handling:</strong>
 * SQL errors are logged and recorded via {@link #recordError(String, String, String)}.
 *
 * @param <T> The message type.
 */
public class H2TopicWriterDelegate<T extends Message> extends AbstractTopicDelegateWriter<H2TopicResource<T>, T> {
    
    private static final Logger log = LoggerFactory.getLogger(H2TopicWriterDelegate.class);
    
    private final Connection connection;
    private PreparedStatement insertStatement;  // Lazy init after schema switch
    
    // H2-specific metrics (in addition to abstract delegate metrics)
    private final AtomicLong writeErrors = new AtomicLong(0);
    // Note: writeThroughput is now inherited from AbstractTopicDelegateWriter
    
    /**
     * Creates a new H2 writer delegate with PreparedStatement pooling.
     * <p>
     * Obtains a connection from HikariCP pool and prepares the INSERT statement once.
     *
     * @param parent The parent H2TopicResource.
     * @param context The resource context.
     * @throws RuntimeException if connection or statement preparation fails.
     */
    public H2TopicWriterDelegate(H2TopicResource<T> parent, ResourceContext context) {
        super(parent, context);
        // Note: writeThroughput is initialized by AbstractTopicDelegateWriter
        
        try {
            // Obtain connection from HikariCP pool (held for delegate lifetime)
            this.connection = parent.getConnection();
            
            // Explicitly set auto-commit mode for single-statement INSERTs (defensive programming)
            // HikariCP defaults to true, but we verify to ensure atomic writes work correctly
            connection.setAutoCommit(true);
            
            // Note: PreparedStatement will be created in onSimulationRunSet() after schema switch
            
            log.debug("Created H2 writer delegate for topic '{}'", parent.getResourceName());
            
        } catch (SQLException e) {
            log.error("Failed to create H2 writer delegate for topic '{}'", parent.getResourceName());
            throw new RuntimeException("H2 writer delegate initialization failed", e);
        }
    }
    
    @Override
    protected void onSimulationRunSet(String simulationRunId) {
        try {
            // Switch this delegate's connection to the correct schema
            H2SchemaUtil.setSchema(connection, simulationRunId);
            
            // Now prepare INSERT statement (schema is set, tables exist)
            String sql = String.format(
                "INSERT INTO %s (topic_name, message_id, timestamp, envelope) VALUES (?, ?, ?, ?)",
                parent.getMessagesTable()
            );
            this.insertStatement = connection.prepareStatement(sql);
            
            log.debug("H2 topic writer '{}' prepared for run: {}", 
                parent.getResourceName(), simulationRunId);
                
        } catch (SQLException e) {
            String msg = String.format(
                "Failed to prepare writer for simulation run '%s' in topic '%s'",
                simulationRunId, parent.getResourceName()
            );
            log.error(msg);
            recordError("WRITER_SETUP_FAILED", msg, "SQLException: " + e.getMessage());
            throw new RuntimeException(msg, e);
        }
    }
    
    @Override
    protected void sendEnvelope(TopicEnvelope envelope) throws InterruptedException {
        try {
            // Note: No explicit transaction management needed here.
            // Single INSERT is atomic (ACID guarantee). Connection is in auto-commit mode (set in constructor).
            // If executeUpdate() throws SQLException, message is NOT committed (auto-rollback).
            // recordWrite() is just a counter increment and cannot fail.
            
            // Reuse PreparedStatement (no re-preparation overhead!)
            insertStatement.setString(1, parent.getResourceName());  // topic_name
            insertStatement.setString(2, envelope.getMessageId());
            insertStatement.setLong(3, envelope.getTimestamp());
            insertStatement.setBytes(4, envelope.toByteArray());
            
            insertStatement.executeUpdate();  // Atomic commit in auto-commit mode
            
            // Record metrics (O(1) operations, cannot fail)
            parent.recordWrite();  // Parent's aggregate counter + throughput
            // Note: messagesSent + writeThroughput are tracked by AbstractTopicDelegateWriter in send() method
            
            log.debug("Wrote message to topic '{}': messageId={}", parent.getResourceName(), envelope.getMessageId());
            
        } catch (SQLException e) {
            // Message was NOT committed (auto-rollback on exception)
            writeErrors.incrementAndGet();  // Track errors
            log.warn("Failed to write message to topic '{}'", parent.getResourceName());
            recordError("WRITE_FAILED", "SQL INSERT failed", 
                "Topic: " + parent.getResourceName() + ", MessageId: " + envelope.getMessageId());
            throw new RuntimeException("Failed to write message to H2 topic", e);
        }
    }
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Includes parent metrics + delegate_messages_sent + delegate_write_throughput_per_sec

        // H2-specific metrics (errors only, throughput is in abstract)
        metrics.put("delegate_write_errors", writeErrors.get());
    }
    
    @Override
    public UsageState getUsageState(String usageType) {
        // Validate usage type
        if (!"topic-write".equals(usageType)) {
            throw new IllegalArgumentException(String.format(
                "Writer delegate only supports 'topic-write', got: '%s'", usageType));
        }
        
        // Check THIS delegate's state (not parent!)
        // UsageState reflects the health of THIS specific service-to-resource connection
        try {
            if (connection == null || connection.isClosed()) {
                return UsageState.FAILED;  // This delegate's connection is dead
            }
            
            if (insertStatement == null || insertStatement.isClosed()) {
                return UsageState.FAILED;  // PreparedStatement lost
            }
            
            // This delegate is ready to write
            return UsageState.ACTIVE;
            
        } catch (SQLException e) {
            return UsageState.FAILED;  // Cannot determine state = assume failed
        }
    }
    
    @Override
    public void close() throws Exception {
        log.debug("Closing H2 writer delegate for topic '{}'", parent.getResourceName());
        
        // Close PreparedStatement (may be null if setSimulationRun was never called)
        if (insertStatement != null) {
            try {
                if (!insertStatement.isClosed()) {
                    insertStatement.close();
                }
            } catch (SQLException e) {
                log.warn("Failed to close PreparedStatement for topic '{}'", parent.getResourceName());
            }
        }
        
        // Return connection to HikariCP pool
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}

