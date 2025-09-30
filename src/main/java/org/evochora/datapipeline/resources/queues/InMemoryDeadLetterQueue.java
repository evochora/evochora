package org.evochora.datapipeline.resources.queues;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.SystemContracts;
import org.evochora.datapipeline.api.resources.wrappers.queues.IDeadLetterQueueResource;
import org.evochora.datapipeline.resources.AbstractResource;

import java.util.Collection;
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
public class InMemoryDeadLetterQueue<T> extends AbstractResource implements IDeadLetterQueueResource<T> {

    private final InMemoryBlockingQueue<SystemContracts.DeadLetterMessage> delegate;
    private final String primaryQueueName;
    private final long capacityLimit;
    private final AtomicLong droppedMessageCount = new AtomicLong(0);

    /**
     * Constructs an InMemoryDeadLetterQueue with the specified name and configuration.
     *
     * @param name    The name of the DLQ resource.
     * @param options The TypeSafe Config object containing DLQ options:
     *                <ul>
     *                  <li>capacity: Maximum number of messages (default: 10000)</li>
     *                  <li>primaryQueueName: Name of the primary queue this DLQ serves (optional)</li>
     *                  <li>throughputWindowSeconds: Window for throughput calculation (default: 5)</li>
     *                </ul>
     */
    public InMemoryDeadLetterQueue(String name, Config options) {
        super(name, options);

        Config defaults = ConfigFactory.parseMap(Map.of(
                "capacity", 10000,
                "throughputWindowSeconds", 5
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
        if (dropped > 0) {
            droppedMessageCount.addAndGet(dropped);
            // Log a warning - in a real system, this should trigger alerts
            System.err.println("WARNING: Dead Letter Queue '" + getResourceName() +
                    "' dropped " + dropped + " messages due to capacity limits. " +
                    "Total dropped: " + droppedMessageCount.get());
        }
        return offered;
    }

    @Override
    public boolean offer(SystemContracts.DeadLetterMessage element) {
        boolean success = delegate.offer(element);
        if (!success) {
            droppedMessageCount.incrementAndGet();
            System.err.println("WARNING: Dead Letter Queue '" + getResourceName() +
                    "' dropped a message due to capacity limits. " +
                    "Total dropped: " + droppedMessageCount.get() +
                    " - Message: " + element);
        }
        return success;
    }

    @Override
    public void put(SystemContracts.DeadLetterMessage element) throws InterruptedException {
        // For DLQs, we use offer with timeout instead of blocking indefinitely
        // to prevent cascading failures
        boolean success = delegate.offer(element, 5, TimeUnit.SECONDS);
        if (!success) {
            droppedMessageCount.incrementAndGet();
            throw new IllegalStateException("Failed to add message to Dead Letter Queue '" +
                    getResourceName() + "' within timeout. Queue may be full. " +
                    "Total dropped: " + droppedMessageCount.get());
        }
    }

    @Override
    public boolean offer(SystemContracts.DeadLetterMessage element, long timeout, TimeUnit unit) throws InterruptedException {
        boolean success = delegate.offer(element, timeout, unit);
        if (!success) {
            droppedMessageCount.incrementAndGet();
            System.err.println("WARNING: Dead Letter Queue '" + getResourceName() +
                    "' failed to accept message within timeout. " +
                    "Total dropped: " + droppedMessageCount.get());
        }
        return success;
    }

    @Override
    public void putAll(Collection<SystemContracts.DeadLetterMessage> elements) throws InterruptedException {
        // Attempt to put all elements with a reasonable timeout per element
        int dropped = 0;
        for (SystemContracts.DeadLetterMessage element : elements) {
            boolean success = delegate.offer(element, 1, TimeUnit.SECONDS);
            if (!success) {
                dropped++;
            }
        }
        if (dropped > 0) {
            droppedMessageCount.addAndGet(dropped);
            throw new IllegalStateException("Failed to add " + dropped + " messages to Dead Letter Queue '" +
                    getResourceName() + "'. Total dropped: " + droppedMessageCount.get());
        }
    }

    /**
     * Gets additional metrics specific to the DLQ, including dropped message count.
     *
     * @return A map of metric names to values.
     */
    public Map<String, Number> getDLQMetrics() {
        return Map.of(
                "dropped_messages", droppedMessageCount.get(),
                "capacity_limit", capacityLimit,
                "current_size", delegate.getMetrics().get("current_size")
        );
    }

    @Override
    public UsageState getUsageState(String usageType) {
        return delegate.getUsageState(usageType);
    }
}