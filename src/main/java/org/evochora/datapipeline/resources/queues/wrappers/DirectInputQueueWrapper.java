package org.evochora.datapipeline.resources.queues.wrappers;

import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.StreamingBatch;

import java.util.concurrent.TimeUnit;

/**
 * A zero-overhead input queue wrapper that bypasses all monitoring and metrics.
 * This is useful for performance testing to isolate wrapper overhead.
 *
 * @param <T> The type of elements in the queue
 */
public class DirectInputQueueWrapper<T> implements IInputQueueResource<T>, IWrappedResource {

    private final IInputQueueResource<T> delegate;

    /**
     * Wraps the given queue, forwarding every call unchanged.
     * <p>
     * The wrapper does not own the delegate and holds no state of its own, so several wrappers may
     * share one queue and discarding a wrapper leaves the queue usable.
     *
     * @param delegate the queue every call is forwarded to
     */
    public DirectInputQueueWrapper(IInputQueueResource<T> delegate) {
        this.delegate = delegate;
    }

    // IResource implementation - delegate to underlying resource
    @Override
    public String getResourceName() {
        if (delegate instanceof IResource) {
            return ((IResource) delegate).getResourceName();
        }
        return "direct-wrapper";
    }

    @Override
    public UsageState getUsageState(String usageType) {
        if (delegate instanceof IResource) {
            return ((IResource) delegate).getUsageState(usageType);
        }
        return UsageState.ACTIVE;
    }

    /** {@inheritDoc} */
    @Override
    public StreamingBatch<T> receiveBatch(int maxSize, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.receiveBatch(maxSize, timeout, unit);
    }
}
