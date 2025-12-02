package org.evochora.datapipeline.resources.queues;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.resources.queues.wrappers.DirectOutputQueueWrapper;
import org.evochora.datapipeline.resources.queues.wrappers.MonitoredQueueConsumer;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A /dev/null style queue resource that discards all data immediately without any storage or synchronization.
 * This is useful for performance testing to isolate simulation overhead from queue/consumer overhead.
 *
 * <p>All put operations succeed instantly. All take operations return empty/wait indefinitely.</p>
 *
 * @param <T> The type of elements (ignored, never stored)
 */
public class NullQueue<T> extends AbstractResource implements IContextualResource, IInputQueueResource<T>, IOutputQueueResource<T> {

    private final AtomicLong messageCount = new AtomicLong(0);

    public NullQueue(String name, Config options) {
        super(name, options);
    }

    // IContextualResource implementation
    @Override
    public UsageState getUsageState(String usageType) {
        return UsageState.ACTIVE;  // Always ready
    }

    @Override
    public IWrappedResource getWrappedResource(ResourceContext context) {
        // Use direct wrapper with zero monitoring overhead for output
        return switch (context.usageType()) {
            case "queue-in" -> new MonitoredQueueConsumer<>(this, context);
            case "queue-out" -> new DirectOutputQueueWrapper<>(this);
            default -> throw new IllegalArgumentException("Unsupported usage type: " + context.usageType());
        };
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics
        metrics.put("messages_discarded", messageCount.get());
    }

    @Override
    public boolean isHealthy() {
        return true;  // NullQueue is always healthy (never fails)
    }

    // IOutputQueueResource implementation - discard all data instantly
    @Override
    public boolean offer(T element) {
        messageCount.incrementAndGet();
        return true;  // Always succeeds
    }

    @Override
    public void put(T element) {
        messageCount.incrementAndGet();
        // Returns immediately - no blocking
    }

    @Override
    public boolean offer(T element, long timeout, TimeUnit unit) {
        messageCount.incrementAndGet();
        return true;  // Always succeeds
    }

    @Override
    public void putAll(Collection<T> elements) {
        messageCount.addAndGet(elements.size());
        // Discard all instantly
    }

    @Override
    public int offerAll(Collection<T> elements) {
        messageCount.addAndGet(elements.size());
        return elements.size();  // All "accepted"
    }

    // IInputQueueResource implementation - never return data
    @Override
    public Optional<T> poll() {
        return Optional.empty();  // No data available
    }

    @Override
    public T take() throws InterruptedException {
        // Block forever since there's no data
        Thread.sleep(Long.MAX_VALUE);
        return null;  // Never reached
    }

    @Override
    public Optional<T> poll(long timeout, TimeUnit unit) {
        return Optional.empty();  // No data available
    }

    @Override
    public int drainTo(Collection<? super T> collection, int maxElements) {
        return 0;  // No data to drain
    }

    @Override
    public int drainTo(Collection<? super T> collection, int maxElements, long timeout, TimeUnit unit) {
        return 0;  // No data to drain
    }
}