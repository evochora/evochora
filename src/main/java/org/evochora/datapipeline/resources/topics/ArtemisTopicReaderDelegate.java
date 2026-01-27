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
    private final String consumerGroup; // The base name for the subscription
    
    // Volatile and lazily initialized to ensure thread-safety and robustness
    // against out-of-order calls to setSimulationRun()
    private volatile MessageConsumer consumer;
    
    // Keep track of the topic and subscription this consumer is attached to
    private volatile String activeTopicName;
    private volatile String activeSubscriptionName;
    
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
        this.consumerGroup = context.parameters().get("consumerGroup");
        
        try {
            this.connection = parent.createConnection();
            
            // Use INDIVIDUAL_ACKNOWLEDGE for precise message acknowledgment.
            this.session = connection.createSession(false, ActiveMQJMSConstants.INDIVIDUAL_ACKNOWLEDGE);
            
            // Consumer is now created lazily on the first receiveEnvelope() call.
            
            // Register with parent for watchdog monitoring
            parent.registerReader(this);
            
            log.debug("Created Artemis reader delegate for consumer group='{}', claimTimeout={}s",
                consumerGroup, parent.getClaimTimeoutSeconds());
            
        } catch (JMSException e) {
            throw new RuntimeException("Failed to initialize Artemis reader delegate", e);
        }
    }

    @Override
    protected synchronized void onSimulationRunSet(String simulationRunId) {
        super.onSimulationRunSet(simulationRunId);
        // The parent resource has updated its effectiveTopicName.
        // We must recreate our consumer to subscribe to the new topic and use a new unique subscription name.
        try {
            ensureConsumerInitialized();
            log.debug("Artemis consumer for group '{}' was recreated for new runId '{}'. New subscription: '{}' on topic '{}'.",
                this.consumerGroup, simulationRunId, this.activeSubscriptionName, this.activeTopicName);
        } catch (JMSException e) {
            // This is a critical failure. The reader will not be able to receive messages for the new run.
            String topicName = parent.getTopicName();
            log.error("CRITICAL: Failed to recreate Artemis consumer for group '{}' on topic '{}' for runId '{}'. This reader will be non-functional.",
                this.consumerGroup, topicName, simulationRunId, e);
            recordError("CONSUMER_RECREATE_FAILED", "Failed to recreate consumer for new runId, reader is non-functional.",
                "Topic: " + topicName + ", RunId: " + simulationRunId);
        }
    }

    /**
     * Closes the current consumer if it exists and sets it to null.
     * This forces lazy recreation on the next receive call.
     */
    private synchronized void invalidateConsumer() {
        if (this.consumer != null) {
            try {
                // IMPORTANT: In JMS, closing the consumer of a durable subscription does NOT
                // remove the subscription from the broker. This is by design.
                // Since our subscription name is now dynamic per runId, we don't need to
                // worry about unsubscribing, as the old subscription will simply be abandoned.
                this.consumer.close();
            } catch (JMSException e) {
                log.warn("Error closing invalidated Artemis consumer for subscription '{}'. A new consumer will be created anyway.", activeSubscriptionName, e);
            } finally {
                this.consumer = null;
                this.activeTopicName = null;
                this.activeSubscriptionName = null;
            }
        }
    }

    /**
     * Ensures the consumer is initialized and subscribed to the correct topic.
     * <p>
     * Implements lazy initialization and re-creation after invalidation.
     * <p>
     * <b>Journal Retention Replay:</b>
     * If journal retention is enabled and this is a NEW subscription (queue
     * doesn't exist in broker), we trigger a replay of all retained messages
     * after creating the subscription. This gives new consumer groups access
     * to all historical messages.
     *
     * @throws JMSException if consumer creation fails
     */
    private synchronized void ensureConsumerInitialized() throws JMSException {
        final String targetTopicName = parent.getTopicName();
        final String runId = parent.getSimulationRunId();

        // Build subscription name: consumerGroup + runId for uniqueness
        final String targetSubscriptionName = this.consumerGroup
            + (runId != null ? "_" + runId.trim() : "");

        // If consumer exists and is on correct subscription, nothing to do
        if (this.consumer != null && targetSubscriptionName.equals(this.activeSubscriptionName)) {
            return;
        }

        // Invalidate old consumer if exists
        invalidateConsumer();

        // =================================================================
        // Journal Retention: Detect if this is a NEW subscription
        // =================================================================
        boolean shouldReplay = false;
        String internalQueueName = targetTopicName + "::" + targetSubscriptionName;

        if (ArtemisTopicResource.isJournalRetentionEnabled()) {
            // Step 1: Fast-path check - have we seen this subscription in this JVM?
            boolean firstTimeInJvm = ArtemisTopicResource.registerSubscriptionInMemory(
                targetTopicName, targetSubscriptionName);

            if (firstTimeInJvm) {
                // Step 2: Check if queue actually exists in broker (persisted from previous run)
                boolean queueExistsInBroker = ArtemisTopicResource.queueExistsInBroker(internalQueueName);

                if (!queueExistsInBroker) {
                    // This is a truly NEW subscription - will need replay after creation
                    shouldReplay = true;
                    log.debug("Detected NEW subscription '{}' on topic '{}' - will replay history",
                        targetSubscriptionName, targetTopicName);
                } else {
                    log.debug("Subscription '{}' already exists in broker - no replay needed",
                        targetSubscriptionName);
                }
            }
        }
        // =================================================================

        // Create the JMS consumer (this creates the queue in broker if not exists)
        Topic topic = session.createTopic(targetTopicName);
        this.consumer = session.createSharedDurableConsumer(topic, targetSubscriptionName);
        this.activeTopicName = targetTopicName;
        this.activeSubscriptionName = targetSubscriptionName;

        log.debug("Artemis consumer initialized for topic '{}' with subscription '{}'",
            this.activeTopicName, this.activeSubscriptionName);

        // =================================================================
        // Journal Retention: Trigger replay for new subscriptions
        // =================================================================
        if (shouldReplay) {
            try {
                ArtemisTopicResource.triggerReplay(targetTopicName, internalQueueName);
                log.debug("Replay triggered for new consumer group '{}' on topic '{}'",
                    this.consumerGroup, targetTopicName);
            } catch (Exception e) {
                // Replay failure is not fatal - consumer still works for new messages
                log.warn("Failed to replay historical messages for consumer group '{}': {}. "
                    + "Consumer will only receive NEW messages.",
                    this.consumerGroup, e.getMessage());
                recordError("REPLAY_FAILED",
                    "Historical message replay failed for new consumer group",
                    "ConsumerGroup: " + this.consumerGroup + ", Error: " + e.getMessage());
            }
        }
        // =================================================================
    }

    @Override
    protected ReceivedEnvelope<javax.jms.Message> receiveEnvelope(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            // Lazy initialization: ensure consumer exists and is on the correct topic
            ensureConsumerInitialized();
            
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
                log.warn("Received non-BytesMessage in Artemis topic '{}': {}", activeTopicName, message.getClass().getName());
                // Ack it to get it out of the way? Or DLQ?
                message.acknowledge();
                clearClaimTracking();
                return null;
            }
            
        } catch (JMSException e) {
            // Check if this is caused by thread interruption (graceful shutdown)
            if (isInterruptedException(e)) {
                log.debug("JMS receive interrupted during shutdown (topic: {})", activeTopicName);
                throw new InterruptedException("JMS receive interrupted");
            }
            
            // Transient error - log at WARN level (AGENTS.md: Resources use WARN for transient errors)
            log.warn("JMS receive failed on topic '{}'", activeTopicName);
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
                activeTopicName, activeSubscriptionName);
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
        // Return the dynamic, active subscription name
        return activeSubscriptionName;
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
        
        log.debug("Session recovered for Artemis reader (topic: {}, subscription: {})", activeTopicName, activeSubscriptionName);
    }

    @Override
    public UsageState getUsageState(String usageType) {
        return UsageState.ACTIVE;
    }

    @Override
    public void close() throws Exception {
        // Unregister from parent watchdog
        parent.unregisterReader(this);
        
        // Use the invalidate method to cleanly close the consumer
        invalidateConsumer();
        
        try {
            session.close();
            // Connection closed by parent
            parent.closeConnection(connection);
        } catch (JMSException e) {
            log.debug("Error closing Artemis reader session (expected during shutdown)");
        }
    }
}
