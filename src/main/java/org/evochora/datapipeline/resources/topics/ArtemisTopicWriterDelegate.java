package org.evochora.datapipeline.resources.topics;

import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.Topic;

import org.evochora.datapipeline.api.contracts.TopicEnvelope;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Message;

/**
 * ActiveMQ Artemis implementation of a topic writer.
 * <p>
 * Uses session pooling via {@link org.messaginghub.pooled.jms.JmsPoolConnectionFactory}
 * for efficient session reuse. Each send borrows a pooled session, creates a producer,
 * sends, then returns the session to the pool. This avoids TCP round-trips for session
 * creation while maintaining thread safety (JMS sessions are not thread-safe).
 */
public class ArtemisTopicWriterDelegate<T extends Message> extends AbstractTopicDelegateWriter<ArtemisTopicResource<T>, T> {

    private static final Logger log = LoggerFactory.getLogger(ArtemisTopicWriterDelegate.class);

    // Session pool shared with other writer delegates via the parent resource
    private final ConnectionFactory writerPool;

    public ArtemisTopicWriterDelegate(ArtemisTopicResource<T> parent, ResourceContext context) {
        super(parent, context);
        this.writerPool = parent.getWriterPool();
    }

    @Override
    protected void sendEnvelope(TopicEnvelope envelope) throws InterruptedException {
        // Fail fast: Do not allow writing messages until the simulation runId is set.
        // This prevents messages from being sent to a base topic without a runId suffix,
        // which could lead to them being ignored by all runId-specific consumers.
        if (getSimulationRunId() == null) {
            throw new IllegalStateException(
                "Attempted to send a message before simulation runId was set on the topic resource. " +
                "This would cause the message to be lost. Halting operation."
            );
        }

        // Always get the latest topic name from the parent resource, as it can change when the runId is set.
        final String currentTopicName = parent.getTopicName();

        try {
            // Borrow a pooled connection+session, send, then return to pool.
            try (Connection conn = writerPool.createConnection()) {
                conn.start();
                try (Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                    Topic topic = session.createTopic(currentTopicName);
                    MessageProducer producer = session.createProducer(topic);
                    producer.setDeliveryMode(DeliveryMode.PERSISTENT);

                    BytesMessage message = session.createBytesMessage();
                    message.writeBytes(envelope.toByteArray());

                    // Add properties for filtering/routing
                    message.setStringProperty("message_id", envelope.getMessageId());

                    producer.send(message);
                    log.debug("Sent message to Artemis topic: {}", currentTopicName);

                    // Record metrics (O(1) - AtomicLong increment + SlidingWindowCounter)
                    parent.recordWrite();
                }
            }
        } catch (JMSException e) {
            // Check if this is caused by thread interruption (graceful shutdown)
            if (isInterruptedException(e)) {
                log.debug("JMS send interrupted during shutdown (topic: {})", currentTopicName);
                throw new InterruptedException("JMS send interrupted");
            }

            // Transient error - log at WARN level (AGENTS.md: Resources use WARN for transient errors)
            log.warn("Failed to send message to Artemis topic '{}'", currentTopicName);
            recordError("SEND_FAILED", "JMS send failed", "Topic: " + currentTopicName + ", Cause: " + e.getMessage());
            throw new RuntimeException("Failed to send JMS message", e);
        }
    }

    /**
     * Checks if a JMSException was caused by thread interruption (shutdown signal).
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
    public UsageState getUsageState(String usageType) {
        return UsageState.ACTIVE;
    }

    @Override
    public void close() throws Exception {
        // Writer pool is managed by the parent resource — nothing to close here.
    }
}
