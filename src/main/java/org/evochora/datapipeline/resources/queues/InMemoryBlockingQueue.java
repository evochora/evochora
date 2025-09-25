package org.evochora.datapipeline.resources.queues;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.wrappers.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.wrappers.queues.IOutputQueueResource;
import org.evochora.datapipeline.resources.queues.wrappers.MonitoredQueueConsumer;
import org.evochora.datapipeline.resources.queues.wrappers.MonitoredQueueProducer;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class InMemoryBlockingQueue<T> implements IContextualResource, IMonitorable, IInputQueueResource<T>, IOutputQueueResource<T> {

    private final ArrayBlockingQueue<TimestampedObject<T>> queue;
    private final int capacity;
    private final int throughputWindowSeconds;
    private final List<OperationalError> errors = Collections.synchronizedList(new java.util.ArrayList<>());
    private final ConcurrentHashMap<Long, Instant> timestamps = new ConcurrentHashMap<>();

    public InMemoryBlockingQueue(Config options) {
        Config defaults = ConfigFactory.parseMap(Map.of(
                "capacity", 1000,
                "throughputWindowSeconds", 5
        ));
        Config finalConfig = options.withFallback(defaults);

        try {
            this.capacity = finalConfig.getInt("capacity");
            this.throughputWindowSeconds = finalConfig.getInt("throughputWindowSeconds");
            if (capacity <= 0) {
                throw new IllegalArgumentException("Capacity must be positive.");
            }
            this.queue = new ArrayBlockingQueue<>(capacity);
        } catch (ConfigException e) {
            throw new IllegalArgumentException("Invalid configuration for InMemoryBlockingQueue", e);
        }
    }

    @Override
    public UsageState getUsageState(String usageType) {
        switch (usageType) {
            case "queue-in":
                return queue.isEmpty() ? UsageState.WAITING : UsageState.ACTIVE;
            case "queue-out":
                return queue.remainingCapacity() == 0 ? UsageState.WAITING : UsageState.ACTIVE;
            default:
                throw new IllegalArgumentException("Unknown usageType: " + usageType);
        }
    }

    @Override
    public IWrappedResource getWrappedResource(ResourceContext context) {
        return switch (context.usageType()) {
            case "queue-in" -> new MonitoredQueueConsumer<>(this, context);
            case "queue-out" -> new MonitoredQueueProducer<>(this, context);
            default -> throw new IllegalArgumentException("Unsupported usage type: " + context.usageType());
        };
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
                "capacity", capacity,
                "current_size", queue.size(),
                "throughput_per_sec", calculateThroughput(this.throughputWindowSeconds)
        );
    }

    public double calculateThroughput(int window) {
        Instant windowStart = Instant.now().minusSeconds(window);
        long count = timestamps.values().stream().filter(t -> t.isAfter(windowStart)).count();
        return (double) count / window;
    }

    @Override
    public List<OperationalError> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    @Override
    public void clearErrors() {
        errors.clear();
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    public void addError(OperationalError error) {
        errors.add(error);
    }

    public void clearErrors(Predicate<OperationalError> filter) {
        errors.removeIf(filter);
    }

    public int getThroughputWindowSeconds() {
        return throughputWindowSeconds;
    }

    @Override
    public boolean send(T item) {
        try {
            return send(item, 1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            addError(new OperationalError(Instant.now(), "SEND_INTERRUPTED", "Send operation was interrupted", e.toString()));
            return false;
        }
    }

    @Override
    public boolean send(T item, long timeout, TimeUnit unit) throws InterruptedException {
        TimestampedObject<T> tsObject = new TimestampedObject<>(item);
        boolean success = queue.offer(tsObject, timeout, unit);
        if (success) {
            timestamps.put(System.nanoTime(), tsObject.timestamp);
        }
        return success;
    }

    @Override
    public Optional<T> receive() {
        try {
            return receive(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            addError(new OperationalError(Instant.now(), "RECEIVE_INTERRUPTED", "Receive operation was interrupted", e.toString()));
            return Optional.empty();
        }
    }

    @Override
    public Optional<T> receive(long timeout, TimeUnit unit) throws InterruptedException {
        TimestampedObject<T> tsObject = queue.poll(timeout, unit);
        if (tsObject != null) {
            timestamps.put(System.nanoTime(), tsObject.timestamp);
            return Optional.of(tsObject.object);
        }
        return Optional.empty();
    }

    private static class TimestampedObject<T> {
        final T object;
        final Instant timestamp;

        TimestampedObject(T object) {
            this.object = object;
            this.timestamp = Instant.now();
        }
    }
}