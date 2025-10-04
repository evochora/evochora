package org.evochora.datapipeline.api.resources.queues;

import org.evochora.datapipeline.api.contracts.SystemContracts;

/**
 * Marker interface for queue resources that serve as Dead Letter Queues (DLQs).
 * A Dead Letter Queue is a specialized output queue that receives messages that have
 * failed processing after multiple retry attempts. DLQs enable:
 * <ul>
 *   <li>Quarantine of poison messages that would otherwise block processing</li>
 *   <li>Preservation of failed messages for debugging and analysis</li>
 *   <li>Manual or automated reprocessing after fixes are applied</li>
 *   <li>Compliance and audit trail for data loss prevention</li>
 * </ul>
 *
 * <p>This interface extends {@link IOutputQueueResource} because DLQs are typically
 * write-only from the perspective of the main data pipeline. Messages are written to
 * the DLQ when they cannot be processed, and separate monitoring/remediation processes
 * may read from the DLQ.</p>
 *
 * <p><strong>Architectural Note:</strong> This interface supports dual-mode deployment:
 * <ul>
 *   <li>In-process mode: Can be backed by in-memory queues or local files</li>
 *   <li>Cloud mode: Can be backed by managed services like AWS SQS DLQ, Azure Service Bus DLQ, etc.</li>
 * </ul>
 * </p>
 *
 * @param <T> The type of original message elements this DLQ handles (used for type safety in service code).
 */
public interface IDeadLetterQueueResource<T> extends IOutputQueueResource<SystemContracts.DeadLetterMessage> {

    /**
     * Gets the name of the primary queue that this DLQ serves.
     * This helps in routing and monitoring.
     *
     * @return The name of the primary queue, or null if this DLQ serves multiple queues.
     */
    default String getPrimaryQueueName() {
        return null;
    }

    /**
     * Gets the maximum number of messages this DLQ can hold before alerting or dropping messages.
     * This is important for preventing DLQ overflow in high-failure scenarios.
     *
     * @return The capacity limit, or -1 if unlimited.
     */
    default long getCapacityLimit() {
        return -1L;
    }
}