package org.evochora.datapipeline.resources.topics;

import org.evochora.datapipeline.api.contracts.TopicEnvelope;

/**
 * Internal envelope for passing messages from concrete implementations to abstract readers.
 * <p>
 * This record bridges the gap between technology-specific reading logic and the abstract
 * {@link org.evochora.datapipeline.api.resources.topics.TopicMessage} that is returned to clients.
 * <p>
 * <strong>Purpose:</strong>
 * Concrete reader delegates (H2TopicReaderDelegate, ChronicleTopicReaderDelegate) use this to return:
 * <ul>
 *   <li>The raw {@link TopicEnvelope} (contains Protobuf Any payload)</li>
 *   <li>An implementation-specific acknowledgment token (row ID, tailer index, offset, etc.)</li>
 * </ul>
 * <p>
 * The abstract {@link AbstractTopicDelegateReader} then unwraps the envelope and creates a
 * {@link org.evochora.datapipeline.api.resources.topics.TopicMessage} with the typed payload.
 * <p>
 * <strong>Implementation Examples:</strong>
 * <ul>
 *   <li>H2: {@code ReceivedEnvelope<AckToken>} where {@code acknowledgeToken} is AckToken(rowId, claimVersion)</li>
 *   <li>Chronicle: {@code ReceivedEnvelope<Long>} where {@code acknowledgeToken} is the tailer index</li>
 *   <li>Kafka: {@code ReceivedEnvelope<Long>} where {@code acknowledgeToken} is the offset</li>
 * </ul>
 *
 * @param envelope The wrapped Protobuf envelope (contains message_id, timestamp, Any payload).
 * @param acknowledgeToken Implementation-specific token for acknowledgment.
 * @param <ACK> The acknowledgment token type.
 */
public record ReceivedEnvelope<ACK>(TopicEnvelope envelope, ACK acknowledgeToken) {
}

