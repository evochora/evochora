package org.evochora.datapipeline.resources.queues.wrappers;

import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.wrappers.queues.IOutputQueueResource;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * A zero-overhead output queue wrapper that bypasses all monitoring and metrics.
 * This is useful for performance testing to isolate wrapper overhead.
 *
 * @param <T> The type of elements in the queue
 */
public class DirectOutputQueueWrapper<T> implements IOutputQueueResource<T>, IWrappedResource {

    private final IOutputQueueResource<T> delegate;

    public DirectOutputQueueWrapper(IOutputQueueResource<T> delegate) {
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

    @Override
    public boolean offer(T element) {
        return delegate.offer(element);
    }

    @Override
    public void put(T element) throws InterruptedException {
        delegate.put(element);
    }

    @Override
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.offer(element, timeout, unit);
    }

    @Override
    public void putAll(Collection<T> elements) throws InterruptedException {
        delegate.putAll(elements);
    }

    @Override
    public int offerAll(Collection<T> elements) {
        return delegate.offerAll(elements);
    }
}