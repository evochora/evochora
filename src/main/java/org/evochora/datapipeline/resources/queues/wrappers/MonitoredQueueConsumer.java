package org.evochora.datapipeline.resources.queues.wrappers;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.wrappers.queues.IInputQueueResource;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * A wrapper for an {@link IInputQueueResource} that adds monitoring capabilities.
 * This class tracks the number of messages consumed and calculates throughput for a specific
 * service context, while delegating the actual queue operations to the underlying resource.
 *
 * @param <T> The type of elements consumed from the queue.
 */
public class MonitoredQueueConsumer<T> extends AbstractResource implements IInputQueueResource<T>, IWrappedResource, IMonitorable {

    private final IInputQueueResource<T> delegate;
    private final ResourceContext context;
    private final AtomicLong messagesConsumed = new AtomicLong(0);
    private final int window;
    private final InMemoryBlockingQueue<T> queue;

    /**
     * Constructs a new MonitoredQueueConsumer.
     *
     * @param delegate The underlying queue resource to wrap.
     * @param context  The resource context for this specific consumer, used for configuration and monitoring.
     * @throws IllegalArgumentException if the delegate is not an instance of {@link InMemoryBlockingQueue},
     *                                  as this implementation currently relies on its specific monitoring features.
     */
    public MonitoredQueueConsumer(IInputQueueResource<T> delegate, ResourceContext context) {
        super(((AbstractResource) delegate).getResourceName(), ((AbstractResource) delegate).getOptions());
        this.delegate = delegate;
        this.context = context;
        // This is a temporary workaround until a more generic monitoring mechanism is in place.
        if (delegate instanceof InMemoryBlockingQueue) {
            this.queue = (InMemoryBlockingQueue<T>) delegate;
            this.window = Integer.parseInt(context.parameters().getOrDefault("window", String.valueOf(this.queue.getThroughputWindowSeconds())));
        } else {
            throw new IllegalArgumentException("MonitoredQueueConsumer currently only supports InMemoryBlockingQueue");
        }
    }

    /**
     * {@inheritDoc}
     * This implementation increments the consumed messages counter if an element is successfully retrieved.
     */
    @Override
    public Optional<T> poll() {
        Optional<T> result = delegate.poll();
        if (result.isPresent()) {
            messagesConsumed.incrementAndGet();
        }
        return result;
    }

    /**
     * {@inheritDoc}
     * This implementation increments the consumed messages counter after an element is successfully retrieved.
     */
    @Override
    public T take() throws InterruptedException {
        T result = delegate.take();
        messagesConsumed.incrementAndGet();
        return result;
    }

    /**
     * {@inheritDoc}
     * This implementation increments the consumed messages counter if an element is successfully retrieved.
     */
    @Override
    public Optional<T> poll(long timeout, TimeUnit unit) throws InterruptedException {
        Optional<T> result = delegate.poll(timeout, unit);
        if (result.isPresent()) {
            messagesConsumed.incrementAndGet();
        }
        return result;
    }

    /**
     * {@inheritDoc}
     * This implementation increments the consumed messages counter by the number of elements successfully drained.
     */
    @Override
    public int drainTo(Collection<? super T> collection, int maxElements) {
        int count = delegate.drainTo(collection, maxElements);
        if (count > 0) {
            messagesConsumed.addAndGet(count);
        }
        return count;
    }

    /**
     * {@inheritDoc}
     * This implementation increments the consumed messages counter by the number of elements successfully drained.
     */
    @Override
    public int drainTo(Collection<? super T> collection, int maxElements, long timeout, TimeUnit unit) throws InterruptedException {
        int count = delegate.drainTo(collection, maxElements, timeout, unit);
        if (count > 0) {
            messagesConsumed.addAndGet(count);
        }
        return count;
    }

    /**
     * {@inheritDoc}
     * This implementation provides metrics specific to this consumer context.
     */
    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
                "messages_consumed", messagesConsumed.get(),
                "throughput_per_sec", queue.calculateThroughput(this.window)
        );
    }

    /**
     * {@inheritDoc}
     * This implementation filters errors from the underlying resource to show only those relevant to consumption.
     */
    @Override
    public List<OperationalError> getErrors() {
        // This filtering is a temporary solution. A more robust error tagging system should be implemented.
        return queue.getErrors().stream()
                .filter(e -> {
                    String msg = e.message().toUpperCase();
                    return msg.contains("RECEIVE") || msg.contains("POLL") || msg.contains("TAKE");
                })
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     * This implementation clears errors from the underlying resource that are relevant to consumption.
     */
    @Override
    public void clearErrors() {
        queue.clearErrors(error -> {
            String msg = error.message().toUpperCase();
            return msg.contains("RECEIVE") || msg.contains("POLL") || msg.contains("TAKE");
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