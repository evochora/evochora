package org.evochora.datapipeline.resources.queues;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.SystemContracts;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.queues.IDeadLetterQueueResource;
import org.evochora.datapipeline.resources.AbstractResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-memory implementation of a Dead Letter Queue for the in-process deployment mode.
 * This implementation wraps an {@link InMemoryBlockingQueue} and adds DLQ-specific functionality
 * such as capacity monitoring and primary queue tracking.
 *
 * <p>This implementation is suitable for:
 * <ul>
 *   <li>Local development and testing</li>
 *   <li>In-process deployment mode</li>
 *   <li>Single-instance applications</li>
 * </ul>
 * </p>
 *
 * <p>For distributed/cloud deployments, use a cloud-native DLQ implementation
 * (e.g., SQS-backed DLQ for AWS deployments).</p>
 *
 * @param <T> The type of the original message contained in the DeadLetterMessage.
 */
public class InMemoryDeadLetterQueue<T> extends AbstractResource implements IDeadLetterQueueResource<T>, IMemoryEstimatable {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDeadLetterQueue.class);
    
    private final InMemoryBlockingQueue<SystemContracts.DeadLetterMessage> delegate;
    private final String primaryQueueName;
    private final long capacityLimit;
    private final AtomicLong droppedMessageCount = new AtomicLong(0);

    // Metrics - zero overhead counter for monitoring
    private final AtomicLong totalReceived = new AtomicLong(0);

    /**
     * Constructs an InMemoryDeadLetterQueue with the specified name and configuration.
     *
     * @param name    The name of the DLQ resource.
     * @param options The TypeSafe Config object containing DLQ options:
     *                <ul>
     *                  <li>capacity: Maximum number of messages (default: 10000)</li>
     *                  <li>primaryQueueName: Name of the primary queue this DLQ serves (optional)</li>
     *                  <li>metricsWindowSeconds: Window for metrics calculation (default: 5)</li>
     *                </ul>
     */
    public InMemoryDeadLetterQueue(String name, Config options) {
        super(name, options);

        Config defaults = ConfigFactory.parseMap(Map.of(
                "capacity", 10000,
                "metricsWindowSeconds", 5
        ));
        Config finalConfig = options.withFallback(defaults);

        this.capacityLimit = finalConfig.getLong("capacity");
        this.primaryQueueName = finalConfig.hasPath("primaryQueueName")
                ? finalConfig.getString("primaryQueueName")
                : null;

        // Create delegate queue with configured capacity
        this.delegate = new InMemoryBlockingQueue<>(name + "-delegate", finalConfig);
    }

    @Override
    public String getPrimaryQueueName() {
        return primaryQueueName;
    }

    @Override
    public long getCapacityLimit() {
        return capacityLimit;
    }

    /**
     * Gets the number of messages that were dropped due to capacity limits.
     *
     * @return The count of dropped messages.
     */
    public long getDroppedMessageCount() {
        return droppedMessageCount.get();
    }

    @Override
    public int offerAll(Collection<SystemContracts.DeadLetterMessage> elements) {
        // Check if we're approaching capacity
        int offered = delegate.offerAll(elements);
        int dropped = elements.size() - offered;
        if (offered > 0) {
            totalReceived.addAndGet(offered);
        }
        if (dropped > 0) {
            droppedMessageCount.addAndGet(dropped);
            log.warn("Dead Letter Queue '{}' dropped {} messages due to capacity, total dropped: {}",
                getResourceName(), dropped, droppedMessageCount.get());
            recordError("MESSAGES_DROPPED", "DLQ capacity exceeded, messages dropped",
                String.format("Dropped: %d, Total dropped: %d", dropped, droppedMessageCount.get()));
        }
        return offered;
    }

    @Override
    public boolean offer(SystemContracts.DeadLetterMessage element) {
        boolean success = delegate.offer(element);
        if (success) {
            totalReceived.incrementAndGet();
        } else {
            droppedMessageCount.incrementAndGet();
            log.warn("Dead Letter Queue '{}' dropped message due to capacity, total dropped: {}",
                getResourceName(), droppedMessageCount.get());
            recordError("MESSAGE_DROPPED", "DLQ capacity exceeded, message dropped",
                String.format("Total dropped: %d", droppedMessageCount.get()));
        }
        return success;
    }

    @Override
    public void put(SystemContracts.DeadLetterMessage element) throws InterruptedException {
        // For DLQs, we use offer with timeout instead of blocking indefinitely
        // to prevent cascading failures
        boolean success = delegate.offer(element, 5, TimeUnit.SECONDS);
        if (success) {
            totalReceived.incrementAndGet();
        } else {
            droppedMessageCount.incrementAndGet();
            throw new IllegalStateException("Failed to add message to Dead Letter Queue '" +
                    getResourceName() + "' within timeout. Queue may be full. " +
                    "Total dropped: " + droppedMessageCount.get());
        }
    }

    @Override
    public boolean offer(SystemContracts.DeadLetterMessage element, long timeout, TimeUnit unit) throws InterruptedException {
        boolean success = delegate.offer(element, timeout, unit);
        if (success) {
            totalReceived.incrementAndGet();
        } else {
            droppedMessageCount.incrementAndGet();
            log.warn("Dead Letter Queue '{}' failed to accept message within timeout, total dropped: {}",
                getResourceName(), droppedMessageCount.get());
            recordError("MESSAGE_DROPPED_TIMEOUT", "DLQ failed to accept message within timeout",
                String.format("Total dropped: %d", droppedMessageCount.get()));
        }
        return success;
    }

    @Override
    public void putAll(Collection<SystemContracts.DeadLetterMessage> elements) throws InterruptedException {
        // Attempt to put all elements with a reasonable timeout per element
        int dropped = 0;
        int accepted = 0;
        for (SystemContracts.DeadLetterMessage element : elements) {
            boolean success = delegate.offer(element, 1, TimeUnit.SECONDS);
            if (success) {
                accepted++;
            } else {
                dropped++;
            }
        }
        if (accepted > 0) {
            totalReceived.addAndGet(accepted);
        }
        if (dropped > 0) {
            droppedMessageCount.addAndGet(dropped);
            throw new IllegalStateException("Failed to add " + dropped + " messages to Dead Letter Queue '" +
                    getResourceName() + "'. Total dropped: " + droppedMessageCount.get());
        }
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics
        metrics.put("total_messages_received", totalReceived.get());
        metrics.put("dropped_messages", droppedMessageCount.get());
    }

    /**
     * {@inheritDoc}
     * DLQ is considered unhealthy if drop rate is high or delegate is unhealthy.
     */
    @Override
    public boolean isHealthy() {
        long received = totalReceived.get();
        long dropped = droppedMessageCount.get();

        // Consider unhealthy if more than 10% of messages are dropped
        if (received > 0 && dropped > received * 0.1) {
            return false;
        }

        // Check delegate health if available
        if (delegate instanceof IMonitorable) {
            return ((IMonitorable) delegate).isHealthy();
        }

        return true;
    }

    @Override
    public UsageState getUsageState(String usageType) {
        return delegate.getUsageState(usageType);
    }
    
    // ==================== IMemoryEstimatable ====================
    
    /**
     * {@inheritDoc}
     * <p>
     * Estimates memory for the DLQ at full capacity.
     * <p>
     * DLQ messages are DeadLetterMessage wrappers containing the original message
     * plus metadata (error reason, timestamp, retry count). Each message is estimated
     * at ~5 KB (original batch reference + metadata).
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // DeadLetterMessage contains: original batch reference (~2KB), error info (~500B), timestamps (~100B)
        long bytesPerMessage = 3 * 1024;
        long totalBytes = capacityLimit * bytesPerMessage;

        String explanation = String.format("%d capacity × ~3 KB/message (DeadLetterMessage wrapper)",
            capacityLimit);
        
        return List.of(new MemoryEstimate(
            getResourceName(),
            totalBytes,
            explanation,
            MemoryEstimate.Category.QUEUE
        ));
    }
}