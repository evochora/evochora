package org.evochora.datapipeline.resources.queues;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
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
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.resources.queues.wrappers.DirectOutputQueueWrapper;
import org.evochora.datapipeline.resources.queues.wrappers.MonitoredQueueConsumer;
import org.evochora.datapipeline.resources.queues.wrappers.MonitoredQueueProducer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A thread-safe, in-memory, bounded queue resource based on {@link ArrayBlockingQueue}.
 * This class serves as the core implementation for a queue that can be shared between
 * different services in the data pipeline. It implements the necessary interfaces to
 * provide contextual wrappers, monitoring, and both input/output queue operations.
 *
 * @param <T> The type of elements held in this queue.
 */
public class InMemoryBlockingQueue<T> extends AbstractResource implements IContextualResource, IMonitorable, IInputQueueResource<T>, IOutputQueueResource<T> {

    private final ArrayBlockingQueue<TimestampedObject<T>> queue;
    private final int capacity;
    private final int throughputWindowSeconds;
    private final boolean disableTimestamps;
    private final List<OperationalError> errors = Collections.synchronizedList(new java.util.ArrayList<>());
    private final ConcurrentHashMap<Long, Instant> timestamps = new ConcurrentHashMap<>();

    /**
     * Constructs an InMemoryBlockingQueue with the specified name and configuration.
     *
     * @param name    The name of the resource.
     * @param options The TypeSafe Config object containing queue options like "capacity" and "throughputWindowSeconds".
     * @throws IllegalArgumentException if the configuration is invalid (e.g., non-positive capacity).
     */
    public InMemoryBlockingQueue(String name, Config options) {
        super(name, options);
        Config defaults = ConfigFactory.parseMap(Map.of(
                "capacity", 1000,
                "throughputWindowSeconds", 5
        ));
        Config finalConfig = options.withFallback(defaults);

        try {
            this.capacity = finalConfig.getInt("capacity");
            this.throughputWindowSeconds = finalConfig.getInt("throughputWindowSeconds");
            this.disableTimestamps = finalConfig.hasPath("disableTimestamps")
                    && finalConfig.getBoolean("disableTimestamps");
            if (capacity <= 0) {
                throw new IllegalArgumentException("Capacity must be positive for resource '" + name + "'.");
            }
            this.queue = new ArrayBlockingQueue<>(capacity);
        } catch (ConfigException e) {
            throw new IllegalArgumentException("Invalid configuration for InMemoryBlockingQueue '" + name + "'", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int offerAll(Collection<T> elements) {
        if (elements == null) {
            throw new NullPointerException("elements collection cannot be null");
        }
        int count = 0;
        for (T element : elements) {
            if (element == null) {
                throw new NullPointerException("collection cannot contain null elements");
            }
            // Directly use the underlying non-blocking queue's offer method.
            if (this.queue.offer(new TimestampedObject<>(element))) {
                timestamps.put(System.nanoTime(), Instant.now());
                count++;
            } else {
                // Queue is full, stop trying to add more.
                break;
            }
        }
        return count;
    }

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     * If useDirectWrapper=true in options, returns DirectOutputQueueWrapper for queue-out to bypass monitoring overhead.
     */
    @Override
    public IWrappedResource getWrappedResource(ResourceContext context) {
        return switch (context.usageType()) {
            case "queue-in" -> new MonitoredQueueConsumer<>(this, context);
            case "queue-out" -> {
                // Check if we should use direct wrapper to bypass monitoring
                boolean useDirectWrapper = getOptions().hasPath("useDirectWrapper")
                    && getOptions().getBoolean("useDirectWrapper");
                yield useDirectWrapper
                    ? new DirectOutputQueueWrapper<>(this)
                    : new MonitoredQueueProducer<>(this, context);
            }
            default -> throw new IllegalArgumentException("Unsupported usage type: " + context.usageType());
        };
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
                "capacity", capacity,
                "current_size", queue.size(),
                "throughput_per_sec", calculateThroughput(this.throughputWindowSeconds)
        );
    }

    /**
     * Calculates the throughput of messages in the queue over a given time window.
     * This method is public to allow wrappers to access it for monitoring.
     *
     * @param window The time window in seconds to calculate throughput for.
     * @return The calculated throughput in messages per second.
     */
    public double calculateThroughput(int window) {
        Instant windowStart = Instant.now().minusSeconds(window);
        long count = timestamps.values().stream().filter(t -> t.isAfter(windowStart)).count();
        return (double) count / window;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<OperationalError> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearErrors() {
        errors.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isHealthy() {
        return true;
    }

    /**
     * Adds an operational error to the resource's error list.
     * This method is intended for use by wrappers to report errors.
     *
     * @param error The operational error to add.
     */
    public void addError(OperationalError error) {
        errors.add(error);
    }

    /**
     * Clears errors from the resource's error list based on a predicate.
     * This method is intended for use by wrappers to clear errors specific to their context.
     *
     * @param filter A predicate to select which errors to remove.
     */
    public void clearErrors(Predicate<OperationalError> filter) {
        errors.removeIf(filter);
    }

    /**
     * Gets the default throughput calculation window in seconds.
     *
     * @return The default throughput window in seconds.
     */
    public int getThroughputWindowSeconds() {
        return throughputWindowSeconds;
    }

    /**
     * {@inheritDoc}
     * This implementation retrieves an element and records its timestamp for throughput calculation.
     */
    @Override
    public Optional<T> poll() {
        TimestampedObject<T> tsObject = queue.poll();
        if (tsObject != null) {
            if (!disableTimestamps && tsObject.timestamp != null) {
                timestamps.put(System.nanoTime(), tsObject.timestamp);
            }
            return Optional.of(tsObject.object);
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     * This implementation retrieves an element and records its timestamp for throughput calculation.
     */
    @Override
    public T take() throws InterruptedException {
        TimestampedObject<T> tsObject = queue.take();
        if (!disableTimestamps && tsObject.timestamp != null) {
            timestamps.put(System.nanoTime(), tsObject.timestamp);
        }
        return tsObject.object;
    }

    /**
     * {@inheritDoc}
     * This implementation retrieves an element and records its timestamp for throughput calculation.
     */
    @Override
    public Optional<T> poll(long timeout, TimeUnit unit) throws InterruptedException {
        TimestampedObject<T> tsObject = queue.poll(timeout, unit);
        if (tsObject != null) {
            if (!disableTimestamps && tsObject.timestamp != null) {
                timestamps.put(System.nanoTime(), tsObject.timestamp);
            }
            return Optional.of(tsObject.object);
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     * This implementation drains elements and records a single timestamp for the entire batch operation
     * for throughput calculation.
     */
    @Override
    public int drainTo(Collection<? super T> collection, int maxElements) {
        ArrayList<TimestampedObject<T>> drainedObjects = new ArrayList<>();
        int count = queue.drainTo(drainedObjects, maxElements);
        if (count > 0 && !disableTimestamps) {
            Instant now = Instant.now();
            for (TimestampedObject<T> tsObject : drainedObjects) {
                collection.add(tsObject.object);
                // Record a timestamp for each drained object to contribute to throughput metrics.
                if (tsObject.timestamp != null) {
                    timestamps.put(System.nanoTime(), now);
                }
            }
        } else if (count > 0) {
            // Just extract objects without timestamp tracking
            for (TimestampedObject<T> tsObject : drainedObjects) {
                collection.add(tsObject.object);
            }
        }
        return count;
    }

    /**
     * {@inheritDoc}
     * This implementation simulates a timeout for the drain operation, as the underlying
     * {@link ArrayBlockingQueue#drainTo} does not support it. It may not be perfectly accurate
     * under high contention.
     */
    @Override
    public int drainTo(Collection<? super T> collection, int maxElements, long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;
        int count = 0;
        while (count < maxElements) {
            // First, attempt a non-blocking drain to get any immediately available elements.
            int drained = drainTo(collection, maxElements - count);
            count += drained;

            // If we have drained all we need or the deadline has passed, exit.
            if (count == maxElements || System.nanoTime() >= deadline) {
                break;
            }

            // If nothing was drained in the first attempt, it means the queue was empty.
            // Wait for a new element to arrive using a blocking poll with the remaining timeout.
            if (drained == 0) {
                Optional<T> item = poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (item.isPresent()) {
                    collection.add(item.get());
                    count++;
                } else {
                    // Timeout elapsed while waiting for an element, so we're done.
                    break;
                }
            }
        }
        return count;
    }

    /**
     * {@inheritDoc}
     * This implementation adds an element and records its timestamp for throughput calculation.
     */
    @Override
    public boolean offer(T element) {
        boolean success = queue.offer(new TimestampedObject<>(element));
        if (success) {
            timestamps.put(System.nanoTime(), Instant.now());
        }
        return success;
    }

    /**
     * {@inheritDoc}
     * This implementation adds an element and records its timestamp for throughput calculation.
     */
    @Override
    public void put(T element) throws InterruptedException {
        queue.put(new TimestampedObject<>(element, disableTimestamps));
        if (!disableTimestamps) {
            timestamps.put(System.nanoTime(), Instant.now());
        }
    }

    /**
     * {@inheritDoc}
     * This implementation adds an element and records its timestamp for throughput calculation.
     */
    @Override
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        boolean success = queue.offer(new TimestampedObject<>(element), timeout, unit);
        if (success) {
            timestamps.put(System.nanoTime(), Instant.now());
        }
        return success;
    }

    /**
     * {@inheritDoc}
     * This implementation iterates through the collection and calls {@link #put(Object)} for each element,
     * ensuring each addition is timestamped for accurate throughput metrics.
     */
    @Override
    public void putAll(Collection<T> elements) throws InterruptedException {
        for (T element : elements) {
            put(element);
        }
    }

    /**
     * An internal wrapper class to associate a timestamp with each object in the queue.
     * This is used for calculating throughput.
     * @param <T> The type of the object being timestamped.
     */
    private static class TimestampedObject<T> {
        final T object;
        final Instant timestamp;

        TimestampedObject(T object) {
            this.object = object;
            this.timestamp = Instant.now();
        }

        TimestampedObject(T object, boolean skipTimestamp) {
            this.object = object;
            this.timestamp = skipTimestamp ? null : Instant.now();
        }
    }
}