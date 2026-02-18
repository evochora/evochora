package org.evochora.datapipeline.resources.queues.wrappers;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.StreamingBatch;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
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

    private static final Logger log = LoggerFactory.getLogger(MonitoredQueueConsumer.class);

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
     * Records multiple consumption events for throughput calculation.
     * This is an O(1) operation using SlidingWindowCounter.
     */
    private void recordConsumptions(int count) {
        messagesConsumed.addAndGet(count);
        throughputCounter.recordSum(count);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Delegates to the underlying queue and records the batch size for metrics.
     * The message count is known immediately from {@link StreamingBatch#size()}.
     *
     * @param maxSize maximum number of elements to receive
     * @param timeout maximum time to wait for at least one element
     * @param unit    time unit for the timeout parameter
     * @return a {@link StreamingBatch} containing 0 to {@code maxSize} elements (never null)
     * @throws InterruptedException if interrupted while waiting
     */
    @Override
    public StreamingBatch<T> receiveBatch(int maxSize, long timeout, TimeUnit unit) throws InterruptedException {
        try {
            StreamingBatch<T> batch = delegate.receiveBatch(maxSize, timeout, unit);
            if (batch.size() > 0) {
                recordConsumptions(batch.size());
            }
            return batch;
        } catch (InterruptedException e) {
            log.debug("receiveBatch interrupted: service={}, queue={}",
                context.serviceName(), delegate.getResourceName());
            throw e;
        } catch (Exception e) {
            log.error("receiveBatch failed: service={}, queue={}, error={}",
                context.serviceName(), delegate.getResourceName(), e.getMessage());
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
