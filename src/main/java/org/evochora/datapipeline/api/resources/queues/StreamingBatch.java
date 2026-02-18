package org.evochora.datapipeline.api.resources.queues;

/**
 * A batch of messages received from a queue, supporting lazy iteration and explicit acknowledgment.
 * <p>
 * This interface models the universal receive-process-acknowledge pattern used by all
 * message brokers (JMS, Kafka, SQS, etc.):
 * <ol>
 *   <li><strong>Receive:</strong> Messages are fetched from the broker and held in-flight</li>
 *   <li><strong>Process:</strong> Caller iterates messages one at a time via {@link #iterator()}</li>
 *   <li><strong>Acknowledge:</strong> {@link #commit()} confirms processing; broker deletes messages</li>
 * </ol>
 * <p>
 * <strong>Streaming semantics:</strong> The {@link #iterator()} lazily deserializes messages
 * one at a time. For brokers that store messages off-heap (e.g., Artemis journal files),
 * only one <em>deserialized</em> message resides on the Java heap at any time. Note that
 * Artemis requires eager body download ({@code reset()}) during the receive phase to prevent
 * body eviction, so N <em>serialized</em> message bodies are on heap simultaneously.
 * Peak memory is {@code N × serializedSize + 1 × deserializedSize}.
 * <p>
 * <strong>Failure handling:</strong> If {@link #close()} is called without a prior
 * {@link #commit()}, messages are released back to the broker for redelivery.
 * Using try-with-resources ensures automatic rollback on exceptions:
 * <pre>
 * try (StreamingBatch&lt;T&gt; batch = queue.receiveBatch(10, 30, SECONDS)) {
 *     if (batch.size() == 0) continue;
 *     storage.writeChunkBatchStreaming(batch.iterator());
 *     batch.commit(); // Only reached on success
 * } // close() rolls back if commit() was not called
 * </pre>
 * <p>
 * <strong>Thread safety:</strong> A StreamingBatch instance is NOT thread-safe. It must be
 * consumed by the same thread that received it.
 *
 * @param <T> The type of messages in the batch
 */
public interface StreamingBatch<T> extends Iterable<T>, AutoCloseable {

    /**
     * Returns the number of messages in this batch.
     * <p>
     * This count is known immediately after {@link IInputQueueResource#receiveBatch} returns,
     * before any iteration begins. It reflects the number of messages received from the broker,
     * not the number of messages already iterated.
     *
     * @return the number of messages in this batch (0 if empty)
     */
    int size();

    /**
     * Acknowledges all messages in this batch, instructing the broker to delete them.
     * <p>
     * This must be called exactly once, after all messages have been successfully processed.
     * After commit, {@link #close()} becomes a no-op.
     * <p>
     * Broker-specific behavior:
     * <ul>
     *   <li>JMS (Artemis): {@code session.commit()}</li>
     *   <li>Kafka: {@code consumer.commitSync()}</li>
     *   <li>SQS: {@code deleteMessageBatch()}</li>
     *   <li>In-memory: no-op (no crash recovery)</li>
     * </ul>
     *
     * @throws org.evochora.datapipeline.api.resources.OperationalException if the acknowledgment fails
     *         (broker communication error). Callers should let this propagate — the batch will be
     *         rolled back on {@link #close()}, and the broker will redeliver the messages.
     */
    void commit();

    /**
     * Releases this batch, rolling back unacknowledged messages for redelivery.
     * <p>
     * If {@link #commit()} was called, this is a no-op. Otherwise, all messages in this
     * batch are returned to the broker and will be redelivered to any consumer.
     * <p>
     * Broker-specific behavior:
     * <ul>
     *   <li>JMS (Artemis): {@code session.rollback()}</li>
     *   <li>Kafka: no action (uncommitted offsets are re-fetched after rebalance)</li>
     *   <li>SQS: no action (visibility timeout expires, messages become visible)</li>
     *   <li>In-memory: no-op</li>
     * </ul>
     * <p>
     * Rollback failures are logged but not thrown — they are not recoverable by callers.
     * The broker will eventually clean up via session timeout.
     */
    @Override
    void close();
}
