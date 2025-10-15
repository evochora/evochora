package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import org.evochora.datapipeline.api.contracts.TopicEnvelope;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for topic reader delegates.
 * <p>
 * This class implements {@link ITopicReader#receive()} and {@link ITopicReader#poll(long, TimeUnit)}
 * to automatically unwrap {@link TopicEnvelope} before returning to the service.
 * <p>
 * <strong>Message Unwrapping:</strong>
 * The {@code receive()} and {@code poll()} methods are {@code final} to enforce the following flow:
 * <ol>
 *   <li>Delegate to {@link #receiveEnvelope(long, TimeUnit)} for technology-specific reading</li>
 *   <li>Extract {@link TopicEnvelope} and acknowledgment token from {@link ReceivedEnvelope}</li>
 *   <li>Unpack {@code google.protobuf.Any} payload using dynamic type resolution</li>
 *   <li>Return {@link TopicMessage} with unwrapped payload and acknowledgment token</li>
 * </ol>
 * <p>
 * <strong>Consumer Group Validation:</strong>
 * The consumer group is validated in the constructor (defense-in-depth).
 * <p>
 * <strong>Subclass Responsibilities:</strong>
 * <ul>
 *   <li>Implement {@link #receiveEnvelope(long, TimeUnit)} to read from the underlying topic</li>
 *   <li>Implement {@link #acknowledgeMessage(Object)} for technology-specific acknowledgment</li>
 *   <li>Optionally override {@link #onSimulationRunSet(String)} for run-specific setup (inherited from {@link AbstractTopicDelegate})</li>
 * </ul>
 *
 * @param <P> The parent topic resource type.
 * @param <T> The message type.
 * @param <ACK> The acknowledgment token type.
 */
public abstract class AbstractTopicDelegateReader<P extends AbstractTopicResource<T, ACK>, T extends Message, ACK> 
    extends AbstractTopicDelegate<P> implements ITopicReader<T, ACK> {
    
    private static final Logger log = LoggerFactory.getLogger(AbstractTopicDelegateReader.class);
    
    // Delegate-level metrics (O(1), tracked by abstract class)
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesAcknowledged = new AtomicLong(0);
    private final SlidingWindowCounter readThroughput;
    
    /**
     * Creates a new reader delegate.
     * <p>
     * <strong>Single Validation Point:</strong>
     * This is the ONLY place where consumerGroup validation occurs. All concrete implementations
     * (H2, Chronicle, Kafka) delegate validation to this abstract class to ensure consistency.
     *
     * @param parent The parent topic resource.
     * @param context The resource context (must include consumerGroup parameter).
     * @throws IllegalArgumentException if consumerGroup is null or blank.
     */
    protected AbstractTopicDelegateReader(P parent, ResourceContext context) {
        super(parent, context);

        // Single validation point - ensures consistent error messages across all implementations
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException(String.format(
                "Consumer group parameter is required for topic reader. " +
                "Expected format: 'topic-read:%s?consumerGroup=<group-name>'",
                parent.getResourceName()));
        }
        
        // Initialize throughput metric with configurable window
        int metricsWindow = parent.getOptions().hasPath("metricsWindowSeconds")
            ? parent.getOptions().getInt("metricsWindowSeconds")
            : 60;  // Default: 60 seconds
        this.readThroughput = new SlidingWindowCounter(metricsWindow);
    }
    
    @Override
    public final TopicMessage<T, ACK> receive() throws InterruptedException {
        ReceivedEnvelope<ACK> received = receiveEnvelope(0, null);  // Block indefinitely
        if (received == null) {
            throw new IllegalStateException("receiveEnvelope() returned null in blocking mode");
        }
        TopicMessage<T, ACK> message = unwrapEnvelope(received);
        
        // Track message received (O(1), after successful receive)
        messagesReceived.incrementAndGet();
        readThroughput.recordCount();
        
        return message;
    }
    
    @Override
    public final TopicMessage<T, ACK> poll(long timeout, TimeUnit unit) throws InterruptedException {
        ReceivedEnvelope<ACK> received = receiveEnvelope(timeout, unit);
        if (received == null) {
            return null;  // Timeout, no message
        }
        
        TopicMessage<T, ACK> message = unwrapEnvelope(received);
        
        // Track message received (O(1), after successful receive)
        messagesReceived.incrementAndGet();
        readThroughput.recordCount();
        
        return message;
    }
    
    @Override
    public final void ack(TopicMessage<T, ACK> message) {
        if (message == null) {
            throw new NullPointerException("Message cannot be null");
        }
        acknowledgeMessage(message.acknowledgeToken());
        
        // Track acknowledgment (O(1), after successful ack)
        messagesAcknowledged.incrementAndGet();
    }
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Parent's aggregate metrics

        // Delegate-level metrics (tracked by abstract class)
        metrics.put("delegate_messages_received", messagesReceived.get());
        metrics.put("delegate_messages_acknowledged", messagesAcknowledged.get());
        metrics.put("delegate_read_throughput_per_sec", readThroughput.getWindowSum());
    }
    
    /**
     * Receives a wrapped envelope from the underlying topic implementation.
     * <p>
     * <strong>Subclass Implementation:</strong>
     * <ul>
     *   <li>H2: Execute SQL SELECT ... FOR UPDATE, return ReceivedEnvelope with row ID</li>
     *   <li>Chronicle: Read from tailer, return ReceivedEnvelope with queue index</li>
     *   <li>Kafka: Poll consumer, return ReceivedEnvelope with offset</li>
     * </ul>
     * <p>
     * <strong>Blocking Behavior:</strong>
     * <ul>
     *   <li>If {@code timeout == 0} and {@code unit == null}: Block indefinitely</li>
     *   <li>Otherwise: Wait up to {@code timeout} and return {@code null} if no message</li>
     * </ul>
     *
     * @param timeout Maximum time to wait (0 for indefinite blocking).
     * @param unit Time unit (null for indefinite blocking).
     * @return The received envelope with acknowledgment token, or null if timeout.
     * @throws InterruptedException if interrupted while waiting.
     */
    protected abstract ReceivedEnvelope<ACK> receiveEnvelope(long timeout, TimeUnit unit) throws InterruptedException;
    
    /**
     * Acknowledges a message using the implementation-specific token.
     * <p>
     * <strong>Subclass Implementation:</strong>
     * <ul>
     *   <li>H2: Execute SQL DELETE WHERE id = ?</li>
     *   <li>Chronicle: No-op (implicit acknowledgment via tailer advance)</li>
     *   <li>Kafka: Commit offset</li>
     * </ul>
     *
     * @param acknowledgeToken The acknowledgment token.
     */
    protected abstract void acknowledgeMessage(ACK acknowledgeToken);
    
    /**
     * Unwraps a TopicEnvelope to extract the payload and create a TopicMessage.
     * <p>
     * Uses dynamic type resolution from {@code google.protobuf.Any}. The type URL embedded
     * in the Any payload (e.g., {@code "type.googleapis.com/org.evochora.datapipeline.api.contracts.BatchInfo"})
     * is used to load the appropriate message class at runtime.
     * <p>
     * <strong>Type Agnostic:</strong>
     * This approach does not require a static {@code messageType} field or configuration.
     * The same topic infrastructure can handle any Protobuf message type, making it flexible
     * for future extensions.
     *
     * @param received The received envelope with acknowledgment token.
     * @return The unwrapped TopicMessage.
     * @throws RuntimeException if the message class cannot be loaded or deserialization fails.
     */
    private TopicMessage<T, ACK> unwrapEnvelope(ReceivedEnvelope<ACK> received) {
        TopicEnvelope envelope = received.envelope();
        
        try {
            com.google.protobuf.Any anyPayload = envelope.getPayload();
            
            // Extract the fully qualified class name from the type URL
            // Format: "type.googleapis.com/org.evochora.datapipeline.api.contracts.BatchInfo"
            // Extract everything after the FIRST '/' to get full package.ClassName
            String typeUrl = anyPayload.getTypeUrl();
            String className = typeUrl.contains("/") 
                ? typeUrl.substring(typeUrl.indexOf('/') + 1)  // Full path after first '/'
                : typeUrl;  // Fallback if no domain prefix
            
            // Dynamically load the message class
            @SuppressWarnings("unchecked")
            Class<? extends Message> messageClass = (Class<? extends Message>) Class.forName(className);
            
            // Unpack the payload
            @SuppressWarnings("unchecked")
            T payload = (T) anyPayload.unpack(messageClass);
            
            return new TopicMessage<>(
                payload,
                envelope.getTimestamp(),
                envelope.getMessageId(),
                consumerGroup,
                received.acknowledgeToken()
            );
            
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to deserialize message from topic '{}'", parent.getResourceName());
            recordError("DESERIALIZATION_ERROR", "Protobuf deserialization failed", 
                "Topic: " + parent.getResourceName() + ", MessageId: " + envelope.getMessageId());
            throw new RuntimeException("Deserialization failed for messageId: " + envelope.getMessageId(), e);
        } catch (ClassNotFoundException e) {
            log.error("Unknown message type in topic '{}'", parent.getResourceName());
            recordError("UNKNOWN_TYPE", "Message type not found", 
                "Topic: " + parent.getResourceName() + ", TypeUrl: " + envelope.getPayload().getTypeUrl());
            throw new RuntimeException("Unknown message type: " + envelope.getPayload().getTypeUrl(), e);
        }
    }
    
    // Note: getUsageState(String) is inherited from IResource and MUST be implemented
    // by concrete subclasses (H2TopicReaderDelegate, ChronicleTopicReaderDelegate, etc.)
    // Each implementation checks its own delegate-specific state (connection, queue, notification availability, etc.)
}

