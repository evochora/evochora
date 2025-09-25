package org.evochora.datapipeline.resources.queues.wrappers;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.wrappers.queues.IOutputQueueResource;
import org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class MonitoredQueueProducer<T> implements IOutputQueueResource<T>, IWrappedResource, IMonitorable {

    private final IOutputQueueResource<T> delegate;
    private final ResourceContext context;
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final int window;
    private final InMemoryBlockingQueue<T> queue;

    public MonitoredQueueProducer(IOutputQueueResource<T> delegate, ResourceContext context) {
        this.delegate = delegate;
        this.context = context;
        // This is a temporary workaround until a more generic monitoring mechanism is in place.
        if (delegate instanceof InMemoryBlockingQueue) {
            this.queue = (InMemoryBlockingQueue<T>) delegate;
            this.window = Integer.parseInt(context.parameters().getOrDefault("window", String.valueOf(this.queue.getThroughputWindowSeconds())));
        } else {
            throw new IllegalArgumentException("MonitoredQueueProducer currently only supports InMemoryBlockingQueue");
        }
    }

    @Override
    public boolean send(T item) {
        try {
            return send(item, 1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            queue.addError(new OperationalError(Instant.now(), "SEND_INTERRUPTED", "Send operation was interrupted", e.toString()));
            return false;
        }
    }

    @Override
    public boolean send(T item, long timeout, TimeUnit unit) throws InterruptedException {
        boolean success = delegate.send(item, timeout, unit);
        if (success) {
            messagesSent.incrementAndGet();
        }
        return success;
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
                "messages_sent", messagesSent.get(),
                "throughput_per_sec", queue.calculateThroughput(this.window)
        );
    }

    @Override
    public List<OperationalError> getErrors() {
        return queue.getErrors().stream()
                .filter(e -> e.message().contains("SEND"))
                .collect(Collectors.toList());
    }

    @Override
    public void clearErrors() {
        queue.clearErrors(error -> error.message().contains("SEND"));
    }

    @Override
    public boolean isHealthy() {
        return queue.isHealthy();
    }

    @Override
    public UsageState getUsageState(String usageType) {
        return queue.getUsageState(context.usageType());
    }
}