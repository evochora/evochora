package org.evochora.datapipeline.resources.queues.wrappers;

import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.wrappers.queues.IInputQueueResource;
import org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class MonitoredQueueConsumer<T> implements IInputQueueResource<T>, IWrappedResource, IMonitorable {

    private final IInputQueueResource<T> delegate;
    private final ResourceContext context;
    private final AtomicLong messagesConsumed = new AtomicLong(0);
    private final int window;
    private final InMemoryBlockingQueue<T> queue;

    public MonitoredQueueConsumer(IInputQueueResource<T> delegate, ResourceContext context) {
        this.delegate = delegate;
        this.context = context;
        // This is a temporary workaround until a more generic monitoring mechanism is in place.
        if (delegate instanceof InMemoryBlockingQueue) {
            this.queue = (InMemoryBlockingQueue<T>) delegate;
            this.window = Integer.parseInt(context.parameters().getOrDefault("window", String.valueOf(this.queue.getThroughputWindowSeconds())));
        } else {
            throw new IllegalArgumentException("MonitoredQueueConsumer currently only supports InMemoryBlockingQueue");
        }
    }

    @Override
    public Optional<T> receive() {
        try {
            return receive(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            queue.addError(new OperationalError(Instant.now(), "RECEIVE_INTERRUPTED", "Receive operation was interrupted", e.toString()));
            return Optional.empty();
        }
    }

    @Override
    public Optional<T> receive(long timeout, TimeUnit unit) throws InterruptedException {
        Optional<T> result = delegate.receive(timeout, unit);
        if (result.isPresent()) {
            messagesConsumed.incrementAndGet();
        }
        return result;
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
                "messages_consumed", messagesConsumed.get(),
                "throughput_per_sec", queue.calculateThroughput(this.window)
        );
    }

    @Override
    public List<OperationalError> getErrors() {
        return queue.getErrors().stream()
                .filter(e -> e.message().contains("RECEIVE"))
                .collect(Collectors.toList());
    }

    @Override
    public void clearErrors() {
        queue.clearErrors(error -> error.message().contains("RECEIVE"));
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