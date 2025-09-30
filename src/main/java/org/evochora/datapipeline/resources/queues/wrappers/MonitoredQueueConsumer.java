package org.evochora.datapipeline.resources.queues.wrappers;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.wrappers.queues.IInputQueueResource;
import org.evochora.datapipeline.resources.AbstractResource;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private final int windowSeconds;
    private final ConcurrentHashMap<Long, Instant> consumptionTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();

    /**
     * Constructs a new MonitoredQueueConsumer.
     * This wrapper is now fully abstracted and works with any {@link IInputQueueResource} implementation,
     * supporting both in-process and cloud deployment modes.
     *
     * @param delegate The underlying queue resource to wrap.
     * @param context  The resource context for this specific consumer, used for configuration and monitoring.
     */
    public MonitoredQueueConsumer(IInputQueueResource<T> delegate, ResourceContext context) {
        super(((AbstractResource) delegate).getResourceName(), ((AbstractResource) delegate).getOptions());
        this.delegate = delegate;
        this.context = context;
        this.windowSeconds = Integer.parseInt(context.parameters().getOrDefault("window", "5"));
    }

    /**
     * Records a consumption event for throughput calculation.
     */
    private void recordConsumption() {
        messagesConsumed.incrementAndGet();
        consumptionTimestamps.put(System.nanoTime(), Instant.now());
        // Clean up old timestamps to prevent memory leaks
        cleanupOldTimestamps();
    }

    /**
     * Records multiple consumption events for throughput calculation.
     */
    private void recordConsumptions(int count) {
        messagesConsumed.addAndGet(count);
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
            consumptionTimestamps.put(System.nanoTime() + i, now);
        }
        cleanupOldTimestamps();
    }

    /**
     * Removes timestamps older than the monitoring window to prevent unbounded memory growth.
     */
    private void cleanupOldTimestamps() {
        if (consumptionTimestamps.size() > 10000) {
            Instant cutoff = Instant.now().minusSeconds(windowSeconds * 2);
            consumptionTimestamps.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        }
    }

    /**
     * {@inheritDoc}
     * This implementation increments the consumed messages counter if an element is successfully retrieved.
     */
    @Override
    public Optional<T> poll() {
        try {
            Optional<T> result = delegate.poll();
            if (result.isPresent()) {
                recordConsumption();
            }
            return result;
        } catch (Exception e) {
            errors.add(new OperationalError(Instant.now(), "POLL_ERROR", "Error polling from queue", e.getMessage()));
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     * This implementation increments the consumed messages counter after an element is successfully retrieved.
     */
    @Override
    public T take() throws InterruptedException {
        try {
            T result = delegate.take();
            recordConsumption();
            return result;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            errors.add(new OperationalError(Instant.now(), "TAKE_ERROR", "Error taking from queue", e.getMessage()));
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     * This implementation increments the consumed messages counter if an element is successfully retrieved.
     */
    @Override
    public Optional<T> poll(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            Optional<T> result = delegate.poll(timeout, unit);
            if (result.isPresent()) {
                recordConsumption();
            }
            return result;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            errors.add(new OperationalError(Instant.now(), "POLL_TIMEOUT_ERROR", "Error polling from queue with timeout", e.getMessage()));
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     * This implementation increments the consumed messages counter by the number of elements successfully drained.
     */
    @Override
    public int drainTo(Collection<? super T> collection, int maxElements) {
        try {
            int count = delegate.drainTo(collection, maxElements);
            if (count > 0) {
                recordConsumptions(count);
            }
            return count;
        } catch (Exception e) {
            errors.add(new OperationalError(Instant.now(), "DRAIN_ERROR", "Error draining from queue", e.getMessage()));
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     * This implementation increments the consumed messages counter by the number of elements successfully drained.
     */
    @Override
    public int drainTo(Collection<? super T> collection, int maxElements, long timeout, TimeUnit unit) throws InterruptedException {
        try {
            int count = delegate.drainTo(collection, maxElements, timeout, unit);
            if (count > 0) {
                recordConsumptions(count);
            }
            return count;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            errors.add(new OperationalError(Instant.now(), "DRAIN_TIMEOUT_ERROR", "Error draining from queue with timeout", e.getMessage()));
            throw e;
        }
    }

    /**
     * Calculates the throughput (messages per second) based on messages consumed within the configured window.
     *
     * @return The throughput in messages per second.
     */
    private double calculateThroughput() {
        Instant windowStart = Instant.now().minusSeconds(windowSeconds);
        long count = consumptionTimestamps.values().stream()
                .filter(t -> t.isAfter(windowStart))
                .count();
        return (double) count / windowSeconds;
    }

    /**
     * {@inheritDoc}
     * This implementation provides metrics specific to this consumer context, calculated independently
     * of the underlying resource.
     */
    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
                "messages_consumed", messagesConsumed.get(),
                "throughput_per_sec", calculateThroughput()
        );
    }

    /**
     * {@inheritDoc}
     * This implementation returns errors tracked by this wrapper, providing proper isolation
     * from the underlying resource's error tracking.
     */
    @Override
    public List<OperationalError> getErrors() {
        return Collections.unmodifiableList(List.copyOf(errors));
    }

    /**
     * {@inheritDoc}
     * This implementation clears only the errors tracked by this wrapper.
     */
    @Override
    public void clearErrors() {
        errors.clear();
    }

    /**
     * {@inheritDoc}
     * This implementation checks health based on the error rate in this wrapper.
     * If the delegate implements {@link IMonitorable}, we also consider its health status.
     */
    @Override
    public boolean isHealthy() {
        // Consider unhealthy if we have many recent errors
        if (errors.size() > 100) {
            return false;
        }

        // If delegate is monitorable, also check its health
        if (delegate instanceof IMonitorable) {
            return ((IMonitorable) delegate).isHealthy();
        }

        return true;
    }

    /**
     * {@inheritDoc}
     * This implementation delegates to the underlying resource if it implements IResource.
     */
    @Override
    public IResource.UsageState getUsageState(String usageType) {
        if (delegate instanceof IResource) {
            return ((IResource) delegate).getUsageState(usageType);
        }
        // Default to ACTIVE if delegate doesn't support usage state
        return IResource.UsageState.ACTIVE;
    }
}