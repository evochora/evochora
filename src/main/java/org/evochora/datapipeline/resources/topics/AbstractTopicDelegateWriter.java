package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Any;
import com.google.protobuf.Message;
import org.evochora.datapipeline.api.contracts.TopicEnvelope;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for topic writer delegates.
 * <p>
 * This class implements the {@link ITopicWriter#send(Message)} method to automatically
 * wrap the payload in a {@link TopicEnvelope} before delegating to the concrete implementation.
 * <p>
 * <strong>Message Wrapping:</strong>
 * The {@code send()} method is {@code final} to enforce the following flow:
 * <ol>
 *   <li>Generate unique {@code messageId} (UUID)</li>
 *   <li>Capture current {@code timestamp} (System.currentTimeMillis())</li>
 *   <li>Wrap payload in {@link TopicEnvelope} using {@code google.protobuf.Any}</li>
 *   <li>Delegate to {@link #sendEnvelope(TopicEnvelope)} for technology-specific writing</li>
 * </ol>
 * <p>
 * <strong>Subclass Responsibilities:</strong>
 * <ul>
 *   <li>Implement {@link #sendEnvelope(TopicEnvelope)} to write the envelope to the underlying topic</li>
 *   <li>Optionally override {@link #onSimulationRunSet(String)} for run-specific setup (inherited from {@link AbstractTopicDelegate})</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong>
 * Subclasses MUST implement {@code sendEnvelope} to be thread-safe for concurrent writers.
 *
 * @param <P> The parent topic resource type.
 * @param <T> The message type.
 */
public abstract class AbstractTopicDelegateWriter<P extends AbstractTopicResource<T, ?>, T extends Message> 
    extends AbstractTopicDelegate<P> implements ITopicWriter<T> {
    
    // Delegate-level metrics (O(1), tracked by abstract class)
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final SlidingWindowCounter writeThroughput;
    
    /**
     * Creates a new writer delegate.
     *
     * @param parent The parent topic resource.
     * @param context The resource context.
     */
    protected AbstractTopicDelegateWriter(P parent, ResourceContext context) {
        super(parent, context);
        
        // Initialize throughput metric with configurable window
        int metricsWindow = parent.getOptions().hasPath("metricsWindowSeconds")
            ? parent.getOptions().getInt("metricsWindowSeconds")
            : 60;  // Default: 60 seconds
        this.writeThroughput = new SlidingWindowCounter(metricsWindow);
    }
    
    @Override
    public final void send(T message) throws InterruptedException {
        if (getSimulationRunId() == null) {
            throw new IllegalStateException("setSimulationRun() must be called before sending messages");
        }
        
        // Wrap message in envelope
        TopicEnvelope envelope = TopicEnvelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString())
            .setTimestamp(System.currentTimeMillis())
            .setPayload(Any.pack(message))
            .build();
        
        // Delegate to concrete implementation
        sendEnvelope(envelope);

        // Track message sent (O(1), after successful send)
        messagesSent.incrementAndGet();
        writeThroughput.recordCount();
    }
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Parent's aggregate metrics
        
        // Delegate-level metrics (tracked by abstract class)
        metrics.put("delegate_messages_sent", messagesSent.get());
        metrics.put("delegate_write_throughput_per_sec", writeThroughput.getWindowSum());
    }
    
    /**
     * Sends a topic envelope to the underlying topic implementation.
     * <p>
     * This method must be implemented by concrete subclasses to handle the
     * technology-specific writing logic (H2, Chronicle, Kafka, etc.).
     * <p>
     * <strong>Thread Safety:</strong>
     * This method MUST be thread-safe for concurrent writers.
     *
     * @param envelope The topic envelope to send.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    protected abstract void sendEnvelope(TopicEnvelope envelope) throws InterruptedException;
    
    // Note: getUsageState(String) is inherited from IResource and MUST be implemented
    // by concrete subclasses (H2TopicWriterDelegate, ChronicleTopicWriterDelegate, etc.)
    // Each implementation checks its own delegate-specific state (connection, queue, etc.)
}
