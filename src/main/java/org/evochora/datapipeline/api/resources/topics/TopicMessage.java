package org.evochora.datapipeline.api.resources.topics;

import com.google.protobuf.Message;

import java.util.Objects;

/**
 * Represents a message read from a topic, including metadata and acknowledgment token.
 * <p>
 * This is a value object returned by {@link ITopicReader#receive()} and {@link ITopicReader#poll(long, java.util.concurrent.TimeUnit)}.
 * It wraps the Protobuf message payload along with:
 * <ul>
 *   <li>Unique message identifier (for idempotency)</li>
 *   <li>Timestamp (when message was written)</li>
 *   <li>Consumer group (which group read this message)</li>
 *   <li>Acknowledgment token (for {@link ITopicReader#ack(TopicMessage)})</li>
 * </ul>
 * <p>
 * <strong>Equality Semantics:</strong>
 * Two TopicMessage instances are equal if they have the same {@code messageId} and {@code consumerGroup}.
 * This enables idempotency checks and duplicate detection.
 * <p>
 * <strong>Acknowledgment Token:</strong>
 * The {@code acknowledgeToken} is implementation-specific and opaque to clients:
 * <ul>
 *   <li>H2: AckToken(rowId, claimVersion)</li>
 *   <li>Chronicle: Long (tailer index)</li>
 *   <li>Kafka: Long (offset)</li>
 * </ul>
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 * @param <ACK> The acknowledgment token type (implementation-specific).
 */
public class TopicMessage<T extends Message, ACK> {
    
    private final T payload;
    private final long timestamp;
    private final String messageId;
    private final String consumerGroup;
    private final ACK acknowledgeToken;
    
    /**
     * Creates a new TopicMessage.
     *
     * @param payload The message payload (must not be null).
     * @param timestamp Unix timestamp in milliseconds when message was written.
     * @param messageId Unique identifier for this message.
     * @param consumerGroup Consumer group this message was read from.
     * @param acknowledgeToken Implementation-specific token for acknowledgment.
     */
    public TopicMessage(T payload, long timestamp, String messageId, String consumerGroup, ACK acknowledgeToken) {
        this.payload = Objects.requireNonNull(payload, "payload cannot be null");
        this.timestamp = timestamp;
        this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup cannot be null");
        this.acknowledgeToken = acknowledgeToken;
    }
    
    /**
     * Returns the message payload.
     *
     * @return The Protobuf message payload.
     */
    public T payload() { return payload; }
    
    /**
     * Returns the message timestamp.
     *
     * @return Unix timestamp in milliseconds when the message was written.
     */
    public long timestamp() { return timestamp; }
    
    /**
     * Returns the unique message identifier.
     *
     * @return The UUID-based message ID.
     */
    public String messageId() { return messageId; }
    
    /**
     * Returns the consumer group that read this message.
     *
     * @return The consumer group name.
     */
    public String consumerGroup() { return consumerGroup; }
    
    /**
     * Returns the acknowledgment token for this message.
     * <p>
     * <strong>VISIBILITY WARNING:</strong>
     * This method is {@code public} only because {@link org.evochora.datapipeline.resources.topics.AbstractTopicDelegateReader}
     * resides in a different package (implementation package vs. API package).
     * <p>
     * <strong>DO NOT USE THIS METHOD DIRECTLY IN CLIENT CODE!</strong>
     * The acknowledgment token is an internal implementation detail. Services should only call
     * {@link ITopicReader#ack(TopicMessage)}, which uses this token internally.
     * <p>
     * This token is implementation-specific and opaque:
     * <ul>
     *   <li>H2: {@code AckToken(rowId, claimVersion)}</li>
     *   <li>Chronicle: {@code Long} (tailer index)</li>
     *   <li>Kafka: {@code Long} (offset)</li>
     * </ul>
     *
     * @return The acknowledgment token (for internal use by topic delegates only).
     */
    public ACK acknowledgeToken() { return acknowledgeToken; }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TopicMessage<?, ?> that)) return false;
        return messageId.equals(that.messageId) && Objects.equals(consumerGroup, that.consumerGroup);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(messageId, consumerGroup);
    }
    
    @Override
    public String toString() {
        return String.format("TopicMessage{messageId=%s, consumerGroup=%s, timestamp=%d, payload=%s}",
            messageId, consumerGroup, timestamp, payload.getClass().getSimpleName());
    }
}

