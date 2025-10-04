package org.evochora.datapipeline.resources.queues.wrappers;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
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
    private final ConcurrentHashMap<Long, AtomicLong> perSecondCounters = new ConcurrentHashMap<>();
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
        this.windowSeconds = Integer.parseInt(context.parameters().getOrDefault("throughputWindowSeconds", "5"));
    }

    /**
     * Records a consumption event for throughput calculation using sliding window counters.
     * This is an O(1) operation that just increments a counter for the current second.
     */
    private void recordConsumption() {
        messagesConsumed.incrementAndGet();
        long currentSecond = Instant.now().getEpochSecond();
        perSecondCounters.computeIfAbsent(currentSecond, k -> new AtomicLong(0)).incrementAndGet();
        cleanupOldCounters(currentSecond);
    }

    /**
     * Records multiple consumption events for throughput calculation.
     * This is an O(1) operation that adds to the counter for the current second.
     */
    private void recordConsumptions(int count) {
        messagesConsumed.addAndGet(count);
        long currentSecond = Instant.now().getEpochSecond();
        perSecondCounters.computeIfAbsent(currentSecond, k -> new AtomicLong(0)).addAndGet(count);
        cleanupOldCounters(currentSecond);
    }

    /**
     * Removes counter buckets older than the monitoring window to prevent unbounded memory growth.
     * Only removes counters if we have more buckets than needed (window + buffer).
     */
    private void cleanupOldCounters(long currentSecond) {
        // Keep windowSeconds + 5 extra seconds as buffer
        int maxBuckets = windowSeconds + 5;
        if (perSecondCounters.size() > maxBuckets) {
            long cutoffSecond = currentSecond - windowSeconds - 1;
            perSecondCounters.keySet().removeIf(second -> second < cutoffSecond);
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
     * Calculates the throughput (messages per second) based on per-second counters within the configured window.
     * This is an O(windowSeconds) operation, typically O(5-10).
     *
     * @return The throughput in messages per second.
     */
    private double calculateThroughput() {
        long currentSecond = Instant.now().getEpochSecond();
        long totalMessages = 0;

        // Sum counters from the last windowSeconds
        for (int i = 0; i < windowSeconds; i++) {
            long second = currentSecond - i;
            AtomicLong counter = perSecondCounters.get(second);
            if (counter != null) {
                totalMessages += counter.get();
            }
        }

        return (double) totalMessages / windowSeconds;
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