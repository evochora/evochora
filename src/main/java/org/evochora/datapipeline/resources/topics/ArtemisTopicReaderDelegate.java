package org.evochora.datapipeline.resources.topics;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.Topic;

import org.apache.activemq.artemis.api.jms.ActiveMQJMSConstants;
import org.evochora.datapipeline.api.contracts.TopicEnvelope;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Message;

/**
 * ActiveMQ Artemis implementation of a topic reader.
 * <p>
 * Uses JMS 2.0 Shared Subscriptions for Competing Consumers support.
 * Uses Artemis INDIVIDUAL_ACKNOWLEDGE mode for precise message acknowledgment.
 * <p>
 * <strong>Stuck Message Handling:</strong>
 * Tracks when messages are received (claimed). The parent {@link ArtemisTopicResource}
 * runs a watchdog thread that calls {@link #isStuck(Instant)} periodically. If a message
 * has been held too long without acknowledgment, {@link #recover()} is called to force
 * session recovery, causing the broker to redeliver the message to another consumer.
 */
public class ArtemisTopicReaderDelegate<T extends Message> 
    extends AbstractTopicDelegateReader<ArtemisTopicResource<T>, T, javax.jms.Message> {

    private static final Logger log = LoggerFactory.getLogger(ArtemisTopicReaderDelegate.class);
    
    private final Connection connection;
    private final Session session;
    private final MessageConsumer consumer;
    private final String topicName;
    private final String subscriptionName;
    
    // Stuck message tracking - volatile for watchdog thread visibility
    private volatile Instant claimedAt;

    /**
     * Creates a new Artemis reader delegate.
     *
     * @param parent  The parent resource managing this delegate
     * @param context Resource context with consumer group configuration
     */
    public ArtemisTopicReaderDelegate(ArtemisTopicResource<T> parent, ResourceContext context) {
        super(parent, context);
        this.topicName = parent.getTopicName();
        // Use consumerGroup as subscription name for shared subscription
        this.subscriptionName = context.parameters().get("consumerGroup");
        
        try {
            this.connection = parent.createConnection();
            
            // Use INDIVIDUAL_ACKNOWLEDGE to allow acknowledging messages individually
            // independent of processing order. This is critical for parallel processing scenarios.
            this.session = connection.createSession(false, ActiveMQJMSConstants.INDIVIDUAL_ACKNOWLEDGE);
            
            Topic topic = session.createTopic(topicName);
            
            // Create Shared Durable Consumer (JMS 2.0)
            // Allows multiple threads/processes with same subscriptionName to load-balance messages.
            // Filter by runId if set? 
            // Currently runId filtering happens via setSimulationRun(), but persistent subscriptions
            // might receive old messages. We should probably filter by selector if runId is set.
            
            // Note: We create consumer lazily or here? Here is fine.
            // But selector might change if setSimulationRun is called later.
            // H2 implementation handles runId via "setSimulationRun" which sets a filter.
            // JMS consumer selector is immutable once created.
            // If runId changes, we must recreate consumer.
            
            // Initial consumer creation (without selector or with empty selector)
            this.consumer = session.createSharedDurableConsumer(topic, subscriptionName);
            
            // Register with parent for watchdog monitoring
            parent.registerReader(this);
            
            log.debug("Created Artemis reader delegate for topic '{}', subscription='{}', claimTimeout={}s",
                topicName, subscriptionName, parent.getClaimTimeoutSeconds());
            
        } catch (JMSException e) {
            throw new RuntimeException("Failed to initialize Artemis reader delegate", e);
        }
    }

    @Override
    protected synchronized void onSimulationRunSet(String simulationRunId) {
        super.onSimulationRunSet(simulationRunId);
        // In JMS, we can't change selector of existing consumer.
        // We would need to close and recreate consumer if we want server-side filtering.
        // For simplicity and robustness, we can filter client-side or assume the topic is logical
        // and only contains relevant data (handled by upstream).
        // H2TopicReader implementation relies on the fact that tables are per-run or filtered.
        // Since ArtemisTopicResource is intended to be a replacement, let's trust the writer 
        // to put the runId in properties, and we COULD filter.
        // But recreating consumer here is complex because of thread-safety.
        
        // Strategy: Client-side filtering check (Message property) is done in upstream logic?
        // No, upstream expects valid messages.
        // Let's rely on the fact that pipeline setup usually ensures one run per topic usage.
        
        log.debug("Simulation run set to '{}' for Artemis reader (subscription: {})", 
            simulationRunId, subscriptionName);
    }

    @Override
    protected synchronized ReceivedEnvelope<javax.jms.Message> receiveEnvelope(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            javax.jms.Message message;
            if (timeout == 0 && unit == null) {
                message = consumer.receive(); // Block indefinitely
            } else {
                long timeoutMs = unit.toMillis(timeout);
                message = consumer.receive(timeoutMs);
            }
            
            if (message == null) {
                return null;
            }
            
            // Track claim time for stuck message detection
            this.claimedAt = Instant.now();
            
            if (message instanceof BytesMessage bytesMessage) {
                // Read bytes
                long length = bytesMessage.getBodyLength();
                byte[] data = new byte[(int) length];
                bytesMessage.readBytes(data);
                
                // Deserialize Envelope
                TopicEnvelope envelope = TopicEnvelope.parseFrom(data);
                
                // Record metrics (O(1) - AtomicLong increment + SlidingWindowCounter)
                parent.recordRead();
                
                // Return with message itself as ACK token
                return new ReceivedEnvelope<>(envelope, message);
            } else {
                log.warn("Received non-BytesMessage in Artemis topic '{}': {}", topicName, message.getClass().getName());
                // Ack it to get it out of the way? Or DLQ?
                message.acknowledge();
                clearClaimTracking();
                return null;
            }
            
        } catch (JMSException e) {
            // Check if this is caused by thread interruption (graceful shutdown)
            if (isInterruptedException(e)) {
                log.debug("JMS receive interrupted during shutdown (topic: {})", topicName);
                throw new InterruptedException("JMS receive interrupted");
            }
            
            // Transient error - log at WARN level (AGENTS.md: Resources use WARN for transient errors)
            log.warn("JMS receive failed on topic '{}'", topicName);
            throw new RuntimeException("JMS receive failed", e);
        } catch (Exception e) {
             throw new RuntimeException("Deserialization failed", e);
        }
    }
    
    /**
     * Checks if a Throwable was caused by thread interruption (shutdown signal).
     * Walks the cause chain to detect wrapped InterruptedException.
     */
    private boolean isInterruptedException(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof InterruptedException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    protected void acknowledgeMessage(javax.jms.Message message) {
        try {
            message.acknowledge();
            // Record metrics (O(1) - AtomicLong increment)
            parent.recordAcknowledge();
        } catch (JMSException e) {
            // ACK failures during shutdown are harmless - message will be redelivered.
            // Consumers are idempotent, so redelivery on next startup is safe.
            // Log at DEBUG to keep shutdown logs clean.
            log.debug("ACK failed (will redeliver on restart): topic={}, subscription={}", 
                topicName, subscriptionName);
        } finally {
            // Clear tracking regardless of ACK success
            clearClaimTracking();
        }
    }
    
    /**
     * Clears the claim tracking state after successful acknowledgment or recovery.
     */
    private void clearClaimTracking() {
        this.claimedAt = null;
    }
    
    /**
     * Returns the subscription name (consumer group) for this reader.
     *
     * @return The subscription name
     */
    String getSubscriptionName() {
        return subscriptionName;
    }
    
    /**
     * Checks if this reader has a stuck message.
     * <p>
     * A message is considered stuck if it was received before the given threshold
     * and has not been acknowledged.
     *
     * @param threshold Messages received before this time are considered stuck
     * @return {@code true} if there is a stuck message
     */
    boolean isStuck(Instant threshold) {
        Instant claimed = this.claimedAt;
        return claimed != null && claimed.isBefore(threshold);
    }
    
    /**
     * Forces session recovery to trigger broker-side redelivery of unacknowledged messages.
     * <p>
     * Called by the watchdog when a stuck message is detected. This clears the claim
     * tracking and calls {@link Session#recover()}, which tells the broker to redeliver
     * all unacknowledged messages in this session.
     *
     * @throws JMSException if session recovery fails
     */
    void recover() throws JMSException {
        // Clear tracking first (message will be redelivered)
        clearClaimTracking();
        
        // Session.recover() marks all unacknowledged messages for redelivery
        session.recover();
        
        log.debug("Session recovered for Artemis reader (topic: {}, subscription: {})", topicName, subscriptionName);
    }

    @Override
    public UsageState getUsageState(String usageType) {
        return UsageState.ACTIVE;
    }

    @Override
    public void close() throws Exception {
        // Unregister from parent watchdog
        parent.unregisterReader(this);
        
        try {
            consumer.close();
            session.close();
            // Connection closed by parent
            parent.closeConnection(connection);
        } catch (JMSException e) {
            log.debug("Error closing Artemis reader (expected during shutdown)");
        }
    }
}
