package org.evochora.datapipeline.api.resources.topics;

import com.google.protobuf.Message;
import org.evochora.datapipeline.api.resources.IResource;

import java.util.concurrent.TimeUnit;

/**
 * Interface for reading messages from a topic with consumer group semantics.
 * <p>
 * This interface is technology-agnostic and can be implemented using:
 * <ul>
 *   <li>H2 Database (in-process, persistent, SQL-based consumer groups)</li>
 *   <li>Chronicle Queue (in-process, memory-mapped files, tailer-based)</li>
 *   <li>Apache Kafka (distributed, built-in consumer groups)</li>
 *   <li>AWS SQS (cloud-managed, queue-based)</li>
 * </ul>
 * <p>
 * <strong>Consumer Groups:</strong>
 * Multiple readers with the same consumer group share the workload (competing consumers).
 * Multiple readers with different consumer groups each receive all messages (pub/sub).
 * <p>
 * <strong>Acknowledgment:</strong>
 * Messages must be explicitly acknowledged via {@link #ack(TopicMessage)} after processing.
 * Unacknowledged messages may be redelivered (at-least-once semantics).
 * <p>
 * <strong>Thread Safety:</strong> Implementations MUST be thread-safe for concurrent operations
 * within the same consumer group.
 * <p>
 * <strong>Simulation Run Awareness:</strong> Extends {@link ISimulationRunAwareTopic} to support
 * run-specific isolation. Call {@link #setSimulationRun(String)} before reading messages.
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
 * @param <ACK> The acknowledgment token type (implementation-specific).
 */
public interface ITopicReader<T extends Message, ACK> extends IResource, ISimulationRunAwareTopic, AutoCloseable {
    
    /**
     * Receives the next message from the topic (blocking).
     * <p>
     * This method blocks indefinitely until a message is available or the thread is interrupted.
     * <p>
     * <strong>Consumer Group Semantics:</strong>
     * Only messages not yet acknowledged by this consumer group are returned.
     * Messages acknowledged by other consumer groups are still visible to this group.
     *
     * @return The next message (never null in this method).
     * @throws InterruptedException if interrupted while waiting.
     */
    TopicMessage<T, ACK> receive() throws InterruptedException;
    
    /**
     * Polls for the next message from the topic with timeout (non-blocking).
     * <p>
     * This method waits up to the specified timeout for a message to become available.
     * Returns {@code null} if no message is available within the timeout.
     * <p>
     * <strong>Consumer Group Semantics:</strong>
     * Only messages not yet acknowledged by this consumer group are returned.
     *
     * @param timeout The maximum time to wait.
     * @param unit The time unit of the timeout.
     * @return The next message, or {@code null} if timeout expires.
     * @throws InterruptedException if interrupted while waiting.
     */
    TopicMessage<T, ACK> poll(long timeout, TimeUnit unit) throws InterruptedException;
    
    /**
     * Acknowledges that a message has been successfully processed.
     * <p>
     * After acknowledgment, the message will not be redelivered to this consumer group
     * (but remains available for other consumer groups and historical replay).
     * <p>
     * <strong>Idempotency:</strong>
     * Acknowledging the same message multiple times is safe (idempotent operation).
     * <p>
     * <strong>Implementation Notes:</strong>
     * <ul>
     *   <li>H2: INSERT into consumer_group_acks table, release claim</li>
     *   <li>Chronicle: Advance tailer position</li>
     *   <li>Kafka: Commit offset</li>
     * </ul>
     *
     * @param message The message to acknowledge (must not be null).
     * @throws NullPointerException if message is null.
     */
    void ack(TopicMessage<T, ACK> message);
}

