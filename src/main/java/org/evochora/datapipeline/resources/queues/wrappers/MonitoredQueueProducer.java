package org.evochora.datapipeline.resources.queues.wrappers;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private final SlidingWindowCounter throughputCounter;
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();

    /**
     * Constructs a new MonitoredQueueProducer.
     * This wrapper is now fully abstracted and works with any {@link IOutputQueueResource} implementation,
     * supporting both in-process and cloud deployment modes.
     *
     * @param delegate The underlying queue resource to wrap.
     * @param context  The resource context for this specific producer, used for configuration and monitoring.
     */
    public MonitoredQueueProducer(IOutputQueueResource<T> delegate, ResourceContext context) {
        super(((AbstractResource) delegate).getResourceName(), ((AbstractResource) delegate).getOptions());
        this.delegate = delegate;
        this.context = context;
        
        // Configuration hierarchy: Context parameter > Resource option > Default (5)
        int windowSeconds = Integer.parseInt(context.parameters().getOrDefault("metricsWindowSeconds", "5"));
        
        this.throughputCounter = new SlidingWindowCounter(windowSeconds);
    }

    /**
     * Records a production event for throughput calculation.
     * This is an O(1) operation using SlidingWindowCounter.
     */
    private void recordProduction() {
        messagesSent.incrementAndGet();
        throughputCounter.recordCount();
    }

    /**
     * Records multiple production events for throughput calculation.
     * This is an O(1) operation using SlidingWindowCounter.
     */
    private void recordProductions(int count) {
        messagesSent.addAndGet(count);
        throughputCounter.recordSum(count);
    }

    /**
     * {@inheritDoc}
     * This implementation increments the sent messages counter by the number of elements
     * that were successfully offered to the queue.
     */
    @Override
    public int offerAll(Collection<T> elements) {
        try {
            int count = delegate.offerAll(elements);
            if (count > 0) {
                recordProductions(count);
            }
            return count;
        } catch (Exception e) {
            errors.add(new OperationalError(Instant.now(), "OFFER_ALL_ERROR", "Error offering elements to queue", e.getMessage()));
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     * This implementation increments the sent messages counter if the element is successfully offered.
     */
    @Override
    public boolean offer(T element) {
        try {
            boolean success = delegate.offer(element);
            if (success) {
                recordProduction();
            }
            return success;
        } catch (Exception e) {
            errors.add(new OperationalError(Instant.now(), "OFFER_ERROR", "Error offering element to queue", e.getMessage()));
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     * This implementation increments the sent messages counter after the element is successfully put.
     */
    @Override
    public void put(T element) throws InterruptedException {
        try {
            delegate.put(element);
            recordProduction();
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            errors.add(new OperationalError(Instant.now(), "PUT_ERROR", "Error putting element to queue", e.getMessage()));
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     * This implementation increments the sent messages counter if the element is successfully offered.
     */
    @Override
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        try {
            boolean success = delegate.offer(element, timeout, unit);
            if (success) {
                recordProduction();
            }
            return success;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            errors.add(new OperationalError(Instant.now(), "OFFER_TIMEOUT_ERROR", "Error offering element to queue with timeout", e.getMessage()));
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     * This implementation increments the sent messages counter by the number of elements in the collection
     * after they are all successfully put.
     */
    @Override
    public void putAll(Collection<T> elements) throws InterruptedException {
        try {
            delegate.putAll(elements);
            recordProductions(elements.size());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            errors.add(new OperationalError(Instant.now(), "PUT_ALL_ERROR", "Error putting elements to queue", e.getMessage()));
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     * This implementation provides metrics specific to this producer context, calculated independently
     * of the underlying resource.
     */
    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
                "messages_sent", messagesSent.get(),
                "throughput_per_sec", throughputCounter.getRate()
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
        return IResource.UsageState.ACTIVE;
    }
}