package org.evochora.datapipeline.resources;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IInputResource;
import org.evochora.datapipeline.api.resources.IOutputResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class InMemoryBlockingQueue<T> implements IContextualResource, IMonitorable {

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
        switch (context.usageType()) {
            case "queue-in":
                return new QueueConsumerWrapper(context);
            case "queue-out":
                return new QueueProducerWrapper(context);
            default:
                throw new IllegalArgumentException("Unsupported usage type for InMemoryBlockingQueue: " + context.usageType());
        }
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
                "capacity", capacity,
                "current_size", queue.size(),
                "throughput_per_sec", calculateThroughput(this.throughputWindowSeconds)
        );
    }

    private double calculateThroughput(int window) {
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

    private static class TimestampedObject<T> {
        final T object;
        final Instant timestamp;

        TimestampedObject(T object) {
            this.object = object;
            this.timestamp = Instant.now();
        }
    }

    public class QueueProducerWrapper implements IOutputResource<T>, IWrappedResource, IMonitorable {
        private final ResourceContext context;
        private final AtomicLong messagesSent = new AtomicLong(0);
        private final int window;

        public QueueProducerWrapper(ResourceContext context) {
            this.context = context;
            this.window = Integer.parseInt(context.parameters().getOrDefault("window", String.valueOf(throughputWindowSeconds)));
        }

        @Override
        public boolean send(T item) {
            try {
                TimestampedObject<T> tsObject = new TimestampedObject<>(item);
                boolean success = queue.offer(tsObject, 1, TimeUnit.SECONDS);
                if (success) {
                    messagesSent.incrementAndGet();
                    timestamps.put(System.nanoTime(), tsObject.timestamp);
                }
                return success;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add(new OperationalError(Instant.now(), "SEND_INTERRUPTED", "Send operation was interrupted", e.toString()));
                return false;
            }
        }

        @Override
        public Map<String, Number> getMetrics() {
            return Map.of(
                    "messages_sent", messagesSent.get(),
                    "throughput_per_sec", calculateThroughput(this.window)
            );
        }

        @Override
        public List<OperationalError> getErrors() {
            return InMemoryBlockingQueue.this.getErrors().stream()
                    .filter(e -> e.message().contains("SEND"))
                    .collect(Collectors.toList());
        }

        @Override
        public void clearErrors() {
            errors.removeIf(e -> e.message().contains("SEND"));
        }

        @Override
        public boolean isHealthy() {
            return InMemoryBlockingQueue.this.isHealthy();
        }

        @Override
        public UsageState getUsageState(String usageType) {
            return InMemoryBlockingQueue.this.getUsageState(context.usageType());
        }
    }

    public class QueueConsumerWrapper implements IInputResource<T>, IWrappedResource, IMonitorable {
        private final ResourceContext context;
        private final AtomicLong messagesConsumed = new AtomicLong(0);
        private final int window;

        public QueueConsumerWrapper(ResourceContext context) {
            this.context = context;
            this.window = Integer.parseInt(context.parameters().getOrDefault("window", String.valueOf(throughputWindowSeconds)));
        }

        @Override
        public Optional<T> receive() {
            try {
                TimestampedObject<T> tsObject = queue.poll(1, TimeUnit.SECONDS);
                if (tsObject != null) {
                    messagesConsumed.incrementAndGet();
                    return Optional.of(tsObject.object);
                }
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add(new OperationalError(Instant.now(), "RECEIVE_INTERRUPTED", "Receive operation was interrupted", e.toString()));
                return Optional.empty();
            }
        }

        @Override
        public Map<String, Number> getMetrics() {
            return Map.of(
                    "messages_consumed", messagesConsumed.get(),
                    "throughput_per_sec", calculateThroughput(this.window)
            );
        }

        @Override
        public List<OperationalError> getErrors() {
            return InMemoryBlockingQueue.this.getErrors().stream()
                .filter(e -> e.message().contains("RECEIVE"))
                .collect(Collectors.toList());
        }

        @Override
        public void clearErrors() {
            errors.removeIf(e -> e.message().contains("RECEIVE"));
        }

        @Override
        public boolean isHealthy() {
            return InMemoryBlockingQueue.this.isHealthy();
        }

        @Override
        public UsageState getUsageState(String usageType) {
            return InMemoryBlockingQueue.this.getUsageState(context.usageType());
        }
    }
}