package org.evochora.datapipeline.resources.topics;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.Topic;

import org.evochora.datapipeline.api.contracts.TopicEnvelope;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Message;

/**
 * ActiveMQ Artemis implementation of a topic writer.
 * <p>
 * Uses JMS Sessions to publish messages.
 * <strong>Thread Safety:</strong>
 * Creates a new JMS Session for each send operation to ensure thread safety,
 * as JMS Sessions are not thread-safe. Artemis In-VM session creation is extremely lightweight.
 */
public class ArtemisTopicWriterDelegate<T extends Message> extends AbstractTopicDelegateWriter<ArtemisTopicResource<T>, T> {

    private static final Logger log = LoggerFactory.getLogger(ArtemisTopicWriterDelegate.class);
    
    // We keep the connection open, but create sessions on demand for thread safety
    private final Connection connection;

    public ArtemisTopicWriterDelegate(ArtemisTopicResource<T> parent, ResourceContext context) {
        super(parent, context);
        try {
            this.connection = parent.createConnection();
        } catch (JMSException e) {
            throw new RuntimeException("Failed to create JMS connection for writer", e);
        }
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
            // JMS Session is NOT thread-safe, so we create one per send.
            // In Artemis (especially In-VM), this is very cheap.
            try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Topic topic = session.createTopic(currentTopicName);
                MessageProducer producer = session.createProducer(topic);
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                
                BytesMessage message = session.createBytesMessage();
                message.writeBytes(envelope.toByteArray());
                
                // Add properties for filtering/routing
                message.setStringProperty("message_id", envelope.getMessageId());
                
                producer.send(message);
                
                // Record metrics (O(1) - AtomicLong increment + SlidingWindowCounter)
                parent.recordWrite();
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
        // Simple check if connection is active
        return UsageState.ACTIVE;
    }

    @Override
    public void close() throws Exception {
        // Connection is managed by parent (tracked in openConnections),
        // but we should close our specific usage if we had long-lived producers.
        // Since we create per-send, nothing to close here except notifying parent?
        // Parent.closeConnection(connection) handles the actual close.
        parent.closeConnection(connection);
    }
}

