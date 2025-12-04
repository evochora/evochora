package org.evochora.datapipeline.resources.queues.wrappers;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A wrapper for an {@link IInputQueueResource} that adds monitoring capabilities.
 * This class tracks the number of messages consumed and calculates throughput for a specific
 * service context, while delegating the actual queue operations to the underlying resource.
 *
 * @param <T> The type of elements consumed from the queue.
 */
public class MonitoredQueueConsumer<T> extends AbstractResource implements IInputQueueResource<T>, IWrappedResource {

    private final IInputQueueResource<T> delegate;
    private final ResourceContext context;
    private final AtomicLong messagesConsumed = new AtomicLong(0);
    private final SlidingWindowCounter throughputCounter;

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
        
        // Configuration hierarchy: Context parameter > Resource option > Default (5)
        int windowSeconds = Integer.parseInt(context.parameters().getOrDefault("metricsWindowSeconds", "5"));
        
        this.throughputCounter = new SlidingWindowCounter(windowSeconds);
    }

    /**
     * Records a consumption event for throughput calculation.
     * This is an O(1) operation using SlidingWindowCounter.
     */
    private void recordConsumption() {
        messagesConsumed.incrementAndGet();
        throughputCounter.recordCount();
    }

    /**
     * Records multiple consumption events for throughput calculation.
     * This is an O(1) operation using SlidingWindowCounter.
     */
    private void recordConsumptions(int count) {
        messagesConsumed.addAndGet(count);
        throughputCounter.recordSum(count);
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
            recordError("POLL_ERROR", "Error polling from queue", 
                String.format("Service: %s, Queue: %s, Error: %s", context.serviceName(), delegate.getResourceName(), e.getMessage()));
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
            recordError("TAKE_ERROR", "Error taking from queue", 
                String.format("Service: %s, Queue: %s, Error: %s", context.serviceName(), delegate.getResourceName(), e.getMessage()));
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
            recordError("POLL_TIMEOUT_ERROR", "Error polling from queue with timeout", 
                String.format("Service: %s, Queue: %s, Error: %s", context.serviceName(), delegate.getResourceName(), e.getMessage()));
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
            recordError("DRAIN_ERROR", "Error draining from queue", 
                String.format("Service: %s, Queue: %s, Error: %s", context.serviceName(), delegate.getResourceName(), e.getMessage()));
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
            recordError("DRAIN_TIMEOUT_ERROR", "Error draining from queue with timeout", 
                String.format("Service: %s, Queue: %s, Error: %s", context.serviceName(), delegate.getResourceName(), e.getMessage()));
            throw e;
        }
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics
        metrics.put("messages_consumed", messagesConsumed.get());
        metrics.put("throughput_per_sec", throughputCounter.getRate());
    }

    /**
     * Checks health of this wrapper and the underlying delegate.
     * <p>
     * Wrapper is unhealthy if it has errors OR if the delegate is unhealthy.
     */
    @Override
    public boolean isHealthy() {
        // Check own errors first (from AbstractResource)
        if (!super.isHealthy()) {
            return false;
        }
        
        // Then check delegate health if available
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