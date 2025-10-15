package org.evochora.datapipeline.api.resources.topics;

import com.google.protobuf.Message;
import org.evochora.datapipeline.api.resources.IResource;

/**
 * Interface for writing messages to a topic.
 * <p>
 * This interface is technology-agnostic and can be implemented using:
 * <ul>
 *   <li>H2 Database (in-process, persistent)</li>
 *   <li>Chronicle Queue (in-process, memory-mapped files)</li>
 *   <li>Apache Kafka (distributed)</li>
 *   <li>AWS SQS/SNS (cloud-managed)</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong> Implementations MUST be thread-safe for concurrent writers.
 * <p>
 * <strong>Blocking Behavior:</strong> The {@link #send(Message)} method MAY block briefly
 * during internal buffering or backpressure, depending on the implementation.
 * <p>
 * <strong>Simulation Run Awareness:</strong> Extends {@link ISimulationRunAwareTopic} to support
 * run-specific isolation. Call {@link #setSimulationRun(String)} before sending messages.
 * <p>
 * <strong>Resource Management:</strong> Implements {@link AutoCloseable} to support both:
 * <ul>
 *   <li>Long-lived pattern: Create once, use many times, close manually (best performance)</li>
 *   <li>Try-with-resources pattern: Auto-cleanup per operation (best safety)</li>
 * </ul>
 * <p>
 * <strong>Implements:</strong> {@link IResource}, {@link ISimulationRunAwareTopic}, {@link AutoCloseable}
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 */
public interface ITopicWriter<T extends Message> extends IResource, ISimulationRunAwareTopic, AutoCloseable {
    
    /**
     * Sends a message to the topic.
     * <p>
     * The message is automatically wrapped in a {@code TopicEnvelope} with a unique
     * {@code messageId} (UUID) and {@code timestamp} before being written to the underlying
     * topic implementation.
     * <p>
     * <strong>Thread Safety:</strong> This method is thread-safe and can be called
     * concurrently by multiple threads.
     * <p>
     * <strong>Blocking:</strong> May block briefly if the underlying implementation
     * applies backpressure (e.g., H2 transaction lock, Kafka buffer full).
     *
     * @param message The message to send (must not be null).
     * @throws InterruptedException if interrupted while waiting.
     * @throws NullPointerException if message is null.
     */
    void send(T message) throws InterruptedException;
}

