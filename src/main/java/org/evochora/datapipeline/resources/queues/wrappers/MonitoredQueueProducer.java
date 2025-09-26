package org.evochora.datapipeline.resources.queues.wrappers;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.wrappers.queues.IOutputQueueResource;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * A wrapper for an {@link IOutputQueueResource} that adds monitoring capabilities.
 * This class tracks the number of messages sent and calculates throughput for a specific
 * service context, while delegating the actual queue operations to the underlying resource.
 *
 * @param <T> The type of elements sent to the queue.
 */
public class MonitoredQueueProducer<T> extends AbstractResource implements IOutputQueueResource<T>, IWrappedResource, IMonitorable {

    private final IOutputQueueResource<T> delegate;
    private final ResourceContext context;
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final int window;
    private final InMemoryBlockingQueue<T> queue;

    /**
     * Constructs a new MonitoredQueueProducer.
     *
     * @param delegate The underlying queue resource to wrap.
     * @param context  The resource context for this specific producer, used for configuration and monitoring.
     * @throws IllegalArgumentException if the delegate is not an instance of {@link InMemoryBlockingQueue},
     *                                  as this implementation currently relies on its specific monitoring features.
     */
    public MonitoredQueueProducer(IOutputQueueResource<T> delegate, ResourceContext context) {
        super(((AbstractResource) delegate).getResourceName(), ((AbstractResource) delegate).getOptions());
        this.delegate = delegate;
        this.context = context;
        // This is a temporary workaround until a more generic monitoring mechanism is in place.
        if (delegate instanceof InMemoryBlockingQueue) {
            this.queue = (InMemoryBlockingQueue<T>) delegate;
            this.window = Integer.parseInt(context.parameters().getOrDefault("window", String.valueOf(this.queue.getThroughputWindowSeconds())));
        } else {
            throw new IllegalArgumentException("MonitoredQueueProducer currently only supports InMemoryBlockingQueue");
        }
    }

    /**
     * {@inheritDoc}
     * This implementation increments the sent messages counter by the number of elements
     * that were successfully offered to the queue.
     */
    @Override
    public int offerAll(Collection<T> elements) {
        int count = delegate.offerAll(elements);
        if (count > 0) {
            messagesSent.addAndGet(count);
        }
        return count;
    }

    /**
     * {@inheritDoc}
     * This implementation increments the sent messages counter if the element is successfully offered.
     */
    @Override
    public boolean offer(T element) {
        boolean success = delegate.offer(element);
        if (success) {
            messagesSent.incrementAndGet();
        }
        return success;
    }

    /**
     * {@inheritDoc}
     * This implementation increments the sent messages counter after the element is successfully put.
     */
    @Override
    public void put(T element) throws InterruptedException {
        delegate.put(element);
        messagesSent.incrementAndGet();
    }

    /**
     * {@inheritDoc}
     * This implementation increments the sent messages counter if the element is successfully offered.
     */
    @Override
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        boolean success = delegate.offer(element, timeout, unit);
        if (success) {
            messagesSent.incrementAndGet();
        }
        return success;
    }

    /**
     * {@inheritDoc}
     * This implementation increments the sent messages counter by the number of elements in the collection
     * after they are all successfully put. Note: If this operation is interrupted, the count may not be
     * perfectly accurate, as the underlying operation is not atomic.
     */
    @Override
    public void putAll(Collection<T> elements) throws InterruptedException {
        delegate.putAll(elements);
        messagesSent.addAndGet(elements.size());
    }

    /**
     * {@inheritDoc}
     * This implementation provides metrics specific to this producer context.
     */
    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
                "messages_sent", messagesSent.get(),
                "throughput_per_sec", queue.calculateThroughput(this.window)
        );
    }

    /**
     * {@inheritDoc}
     * This implementation filters errors from the underlying resource to show only those relevant to production.
     */
    @Override
    public List<OperationalError> getErrors() {
        // This filtering is a temporary solution. A more robust error tagging system should be implemented.
        return queue.getErrors().stream()
                .filter(e -> {
                    String msg = e.message().toUpperCase();
                    return msg.contains("SEND") || msg.contains("OFFER") || msg.contains("PUT");
                })
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     * This implementation clears errors from the underlying resource that are relevant to production.
     */
    @Override
    public void clearErrors() {
        queue.clearErrors(error -> {
            String msg = error.message().toUpperCase();
            return msg.contains("SEND") || msg.contains("OFFER") || msg.contains("PUT");
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isHealthy() {
        return queue.isHealthy();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public UsageState getUsageState(String usageType) {
        return queue.getUsageState(context.usageType());
    }
}