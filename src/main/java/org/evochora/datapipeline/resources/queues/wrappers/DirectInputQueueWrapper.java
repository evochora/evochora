package org.evochora.datapipeline.resources.queues.wrappers;

import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A zero-overhead input queue wrapper that bypasses all monitoring and metrics.
 * This is useful for performance testing to isolate wrapper overhead.
 *
 * @param <T> The type of elements in the queue
 */
public class DirectInputQueueWrapper<T> implements IInputQueueResource<T>, IWrappedResource {

    private final IInputQueueResource<T> delegate;

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

    @Override
    public Optional<T> poll() {
        return delegate.poll();
    }

    @Override
    public T take() throws InterruptedException {
        return delegate.take();
    }

    @Override
    public Optional<T> poll(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.poll(timeout, unit);
    }

    @Override
    public int drainTo(Collection<? super T> collection, int maxElements) {
        return delegate.drainTo(collection, maxElements);
    }

    @Override
    public int drainTo(Collection<? super T> collection, int maxElements, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.drainTo(collection, maxElements, timeout, unit);
    }
}
