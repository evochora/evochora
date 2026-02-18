package org.evochora.datapipeline.resources.queues;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.queues.StreamingBatch;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.resources.queues.wrappers.DirectOutputQueueWrapper;
import org.evochora.datapipeline.resources.queues.wrappers.MonitoredQueueConsumer;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A /dev/null style queue resource that discards all data immediately without any storage or synchronization.
 * This is useful for performance testing to isolate simulation overhead from queue/consumer overhead.
 *
 * <p>All put operations succeed instantly. All receive operations return empty batches after timeout.</p>
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

    /**
     * {@inheritDoc}
     * <p>
     * Always returns an empty batch after sleeping for the requested timeout.
     * No data is ever stored or returned.
     *
     * @param maxSize ignored (no data to return)
     * @param timeout time to sleep before returning
     * @param unit    time unit for the timeout parameter
     * @return an always-empty {@link StreamingBatch}
     * @throws InterruptedException if interrupted while sleeping
     */
    @Override
    public StreamingBatch<T> receiveBatch(int maxSize, long timeout, TimeUnit unit) throws InterruptedException {
        // Block for the requested timeout (simulates waiting for data that never comes)
        Thread.sleep(unit.toMillis(timeout));
        return new EmptyStreamingBatch<>();
    }

    /**
     * A StreamingBatch that is always empty. Used by NullQueue which never has data.
     */
    private static class EmptyStreamingBatch<T> implements StreamingBatch<T> {
        @Override
        public int size() {
            return 0;
        }

        @Override
        public Iterator<T> iterator() {
            return Collections.emptyIterator();
        }

        @Override
        public void commit() {
            // No-op
        }

        @Override
        public void close() {
            // No-op
        }
    }
}
