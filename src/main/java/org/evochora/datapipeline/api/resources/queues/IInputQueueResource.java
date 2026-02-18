package org.evochora.datapipeline.api.resources.queues;

import org.evochora.datapipeline.api.resources.IResource;

import java.util.concurrent.TimeUnit;

/**
 * Interface for queue resources that provide batch consumption with explicit acknowledgment.
 * <p>
 * This interface models the universal receive-process-acknowledge pattern. Consumers receive
 * a batch of messages as a {@link StreamingBatch}, process them (e.g., stream to storage),
 * and then either {@link StreamingBatch#commit() commit} (acknowledge) or
 * {@link StreamingBatch#close() close} (rollback) the batch.
 * <p>
 * <strong>Consecutive batch guarantee:</strong> Each batch contains consecutive messages
 * from the queue. Competing consumers receive non-overlapping batches. This is enforced
 * by implementation-specific mechanisms (e.g., drain locks, token queues).
 * <p>
 * <strong>Broker-agnostic:</strong> This interface works with any message broker:
 * Artemis (JMS), Kafka, SQS, Apache Bookkeeper, or in-memory queues.
 *
 * @param <T> The type of data this resource can provide.
 */
public interface IInputQueueResource<T> extends IResource {

    /**
     * Receives a batch of up to {@code maxSize} messages from the queue.
     * <p>
     * Blocks until at least one message is available or the timeout expires.
     * Returns a {@link StreamingBatch} that lazily deserializes messages during iteration,
     * keeping only one message on the heap at a time.
     * <p>
     * <strong>Usage pattern:</strong>
     * <pre>
     * try (StreamingBatch&lt;T&gt; batch = queue.receiveBatch(10, 30, SECONDS)) {
     *     if (batch.size() == 0) continue; // Timeout, no data
     *     process(batch.iterator());       // Lazy, one-at-a-time
     *     batch.commit();                  // Acknowledge all
     * } // Automatic rollback if commit() was not called
     * </pre>
     * <p>
     * <strong>Consecutive guarantee:</strong> All messages in the batch are consecutive
     * queue entries. No other consumer can interleave messages within this batch.
     * <p>
     * <strong>Coalescing:</strong> Implementations may wait briefly after receiving the
     * first message to accumulate more messages into the batch (adaptive coalescing).
     *
     * @param maxSize the maximum number of messages to receive
     * @param timeout how long to wait for at least one message
     * @param unit    the time unit of the timeout parameter
     * @return a {@link StreamingBatch} containing 0 to {@code maxSize} messages (never null)
     * @throws InterruptedException if interrupted while waiting
     */
    StreamingBatch<T> receiveBatch(int maxSize, long timeout, TimeUnit unit) throws InterruptedException;
}
