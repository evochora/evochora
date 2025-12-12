package org.evochora.datapipeline.resources.queues;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.resources.queues.wrappers.DirectInputQueueWrapper;
import org.evochora.datapipeline.resources.queues.wrappers.DirectOutputQueueWrapper;
import org.evochora.datapipeline.resources.queues.wrappers.MonitoredQueueConsumer;
import org.evochora.datapipeline.resources.queues.wrappers.MonitoredQueueProducer;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;

/**
 * A thread-safe, in-memory, bounded queue resource based on {@link ArrayBlockingQueue}.
 * This class serves as the core implementation for a queue that can be shared between
 * different services in the data pipeline. It implements the necessary interfaces to
 * provide contextual wrappers, monitoring, and both input/output queue operations.
 *
 * @param <T> The type of elements held in this queue.
 */
public class InMemoryBlockingQueue<T> extends AbstractResource implements IContextualResource, IInputQueueResource<T>, IOutputQueueResource<T>, IMemoryEstimatable {

    private final ArrayBlockingQueue<TimestampedObject<T>> queue;
    private final int capacity;
    private final int metricsWindowSeconds;
    private final boolean disableTimestamps;
    private final SlidingWindowCounter throughputCounter;

    // Lock to ensure drainTo(with timeout) is atomic across competing consumers
    // This GUARANTEES non-overlapping consecutive batch ranges
    private final Object drainLock = new Object();
    private final int coalescingDelayMs;
    
    // Optional: Override for memory estimation (bytes per item)
    // If set, uses this value instead of SimulationParameters.estimateBytesPerTick()
    // Useful for queues that don't hold TickData (e.g., metadata-queue holds SimulationMetadata)
    private final long estimatedBytesPerItem;

    /**
     * Constructs an InMemoryBlockingQueue with the specified name and configuration.
     *
     * @param name    The name of the resource.
     * @param options The TypeSafe Config object containing queue options:
     *                <ul>
     *                  <li>{@code capacity} - Maximum queue size (default: 10)</li>
     *                  <li>{@code metricsWindowSeconds} - Throughput calculation window (default: 5)</li>
     *                  <li>{@code coalescingDelayMs} - Delay for batch coalescing (default: 0)</li>
     *                  <li>{@code disableTimestamps} - Disable timestamp tracking (default: false)</li>
     *                  <li>{@code estimatedBytesPerItem} - Override memory estimation per item in bytes.
     *                      If not set, uses {@link SimulationParameters#estimateBytesPerTick()} which
     *                      assumes TickData. Set this for queues holding other types (e.g., metadata-queue
     *                      holds SimulationMetadata with ProgramArtifacts, ~5 MB per item).</li>
     *                </ul>
     * @throws IllegalArgumentException if the configuration is invalid (e.g., non-positive capacity).
     */
    public InMemoryBlockingQueue(String name, Config options) {
        super(name, options);
        Config defaults = ConfigFactory.parseMap(Map.of(
                "capacity", 10,  // Default: 10 (memory-optimized for large environments)
                "metricsWindowSeconds", 5,
                "coalescingDelayMs", 0  // Default: no coalescing
        ));
        Config finalConfig = options.withFallback(defaults);

        try {
            this.capacity = finalConfig.getInt("capacity");
            this.metricsWindowSeconds = finalConfig.getInt("metricsWindowSeconds");
            this.coalescingDelayMs = finalConfig.getInt("coalescingDelayMs");
            this.disableTimestamps = finalConfig.hasPath("disableTimestamps")
                    && finalConfig.getBoolean("disableTimestamps");
            // Optional: Override for memory estimation (0 = use SimulationParameters.estimateBytesPerTick())
            this.estimatedBytesPerItem = finalConfig.hasPath("estimatedBytesPerItem")
                    ? finalConfig.getLong("estimatedBytesPerItem") : 0;
            if (capacity <= 0) {
                throw new IllegalArgumentException("Capacity must be positive for resource '" + name + "'.");
            }
            if (coalescingDelayMs < 0) {
                throw new IllegalArgumentException("coalescingDelayMs cannot be negative for resource '" + name + "'.");
            }
            if (estimatedBytesPerItem < 0) {
                throw new IllegalArgumentException("estimatedBytesPerItem cannot be negative for resource '" + name + "'.");
            }
            this.queue = new ArrayBlockingQueue<>(capacity);
            this.throughputCounter = new SlidingWindowCounter(metricsWindowSeconds);
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
                if (!disableTimestamps) {
                    throughputCounter.recordCount();
                }
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
        if (usageType == null) {
            throw new IllegalArgumentException(String.format(
                "Queue resource '%s' requires a non-null usageType", getResourceName()
            ));
        }

        return switch (usageType) {
            case "queue-in", "queue-in-direct" ->
                queue.isEmpty() ? UsageState.WAITING : UsageState.ACTIVE;
            case "queue-out", "queue-out-direct" ->
                queue.remainingCapacity() == 0 ? UsageState.WAITING : UsageState.ACTIVE;
            default -> throw new IllegalArgumentException(String.format(
                "Unknown usageType '%s' for queue resource '%s'", usageType, getResourceName()
            ));
        };
    }

    /**
     * {@inheritDoc}
     * Supports usage types: queue-in, queue-in-direct, queue-out, queue-out-direct.
     * Direct variants bypass monitoring for zero overhead.
     */
    @Override
    public IWrappedResource getWrappedResource(ResourceContext context) {
        if (context.usageType() == null) {
            throw new IllegalArgumentException(String.format(
                "Queue resource '%s' requires a usageType in the binding URI. " +
                "Expected format: 'usageType:%s' where usageType is one of: " +
                "queue-in, queue-in-direct, queue-out, queue-out-direct",
                getResourceName(), getResourceName()
            ));
        }

        return switch (context.usageType()) {
            case "queue-in" -> new MonitoredQueueConsumer<>(this, context);
            case "queue-in-direct" -> new DirectInputQueueWrapper<>(this);
            case "queue-out" -> new MonitoredQueueProducer<>(this, context);
            case "queue-out-direct" -> new DirectOutputQueueWrapper<>(this);
            default -> throw new IllegalArgumentException(String.format(
                "Unsupported usage type '%s' for queue resource '%s'. " +
                "Supported types: queue-in, queue-in-direct, queue-out, queue-out-direct",
                context.usageType(), getResourceName()
            ));
        };
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics
        metrics.put("capacity", capacity);
        metrics.put("current_size", queue.size());
        metrics.put("throughput_per_sec", calculateThroughput(this.metricsWindowSeconds));
    }

    /**
     * Calculates the throughput of messages in the queue over a given time window.
     * This method is public to allow wrappers to access it for monitoring.
     * <p>
     * This is an O(1) operation using {@link SlidingWindowCounter}.
     *
     * @param window The time window in seconds (ignored, uses configured metricsWindowSeconds).
     * @return The calculated throughput in messages per second.
     */
    public double calculateThroughput(int window) {
        return throughputCounter.getRate();
    }

    /**
     * Clears errors from the resource's error list based on a predicate.
     * This method is intended for use by wrappers to clear errors specific to their context.
     *
     * @param filter A predicate to select which errors to remove.
     */
    public void clearErrors(Predicate<OperationalError> filter) {
        clearErrorsIf(filter);
    }

    /**
     * Gets the metrics calculation window in seconds.
     *
     * @return The metrics window in seconds.
     */
    public int getMetricsWindowSeconds() {
        return metricsWindowSeconds;
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
                throughputCounter.recordCount();
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
            throughputCounter.recordCount();
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
                throughputCounter.recordCount();
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
            for (TimestampedObject<T> tsObject : drainedObjects) {
                collection.add(tsObject.object);
                // Record a count for each drained object to contribute to throughput metrics.
                if (tsObject.timestamp != null) {
                    throughputCounter.recordCount();
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
     * {@link ArrayBlockingQueue#drainTo} does not support it.
     *
     * CRITICAL: This method is synchronized via drainLock to ensure atomicity across competing
     * consumers. This GUARANTEES non-overlapping consecutive tick ranges in batch files when
     * multiple PersistenceService instances compete for items.
     *
     * ADAPTIVE COALESCING: If configured with coalescingDelayMs > 0, after receiving the first
     * element from an empty queue, the method checks if the queue is still empty. If so, it waits
     * briefly to allow the producer to add more items, improving batch sizes. If queue has data,
     * it drains immediately to avoid unnecessary delays.
     *
     * Lock scope: The entire drainTo operation (wait + drain) is atomic per consumer.
     * Other consumers block waiting for the lock, preventing interleaved operations.
     */
    @Override
    public int drainTo(Collection<? super T> collection, int maxElements, long timeout, TimeUnit unit) throws InterruptedException {
        // ATOMIC OPERATION: Entire drain is synchronized to guarantee consecutive ranges
        synchronized (drainLock) {
            // First, attempt a non-blocking drain to get any immediately available elements.
            int drained = drainTo(collection, maxElements);

            // If we drained something OR if the timeout is zero, we're done
            if (drained > 0 || timeout == 0) {
                return drained;
            }

            // Queue was empty - wait for at least ONE element to arrive
            Optional<T> item = poll(timeout, unit);
            if (item.isPresent()) {
                collection.add(item.get());

                // ADAPTIVE COALESCING: Only wait if queue is STILL empty (producer is slow)
                // If queue has data, drain immediately (producer is fast, no need to wait)
                boolean queueStillEmpty = queue.isEmpty();
                if (coalescingDelayMs > 0 && queueStillEmpty) {
                    Thread.sleep(coalescingDelayMs);
                }

                // Now drain any accumulated elements
                int additional = drainTo(collection, maxElements - 1);
                return 1 + additional;
            }

            // Timeout elapsed with no elements
            return 0;
        }
    }

    /**
     * {@inheritDoc}
     * This implementation adds an element and records its timestamp for throughput calculation.
     */
    @Override
    public boolean offer(T element) {
        boolean success = queue.offer(new TimestampedObject<>(element));
        if (success && !disableTimestamps) {
            throughputCounter.recordCount();
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
            throughputCounter.recordCount();
        }
    }

    /**
     * {@inheritDoc}
     * This implementation adds an element and records its timestamp for throughput calculation.
     */
    @Override
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        boolean success = queue.offer(new TimestampedObject<>(element), timeout, unit);
        if (success && !disableTimestamps) {
            throughputCounter.recordCount();
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
    
    // ==================== IMemoryEstimatable ====================
    
    /**
     * {@inheritDoc}
     * <p>
     * Estimates memory for a queue at full capacity with worst-case item sizes.
     * <p>
     * <strong>Calculation:</strong> capacity × bytesPerItem (100% occupancy)
     * <p>
     * If {@code estimatedBytesPerItem} is configured, uses that value for item size estimation.
     * Otherwise, assumes each item is a TickData and uses {@link SimulationParameters#estimateBytesPerTick()}.
     * <p>
     * This is a worst-case estimate assuming:
     * <ul>
     *   <li>Queue is completely full</li>
     *   <li>Each item has the estimated size (TickData default, or custom override)</li>
     * </ul>
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // Use custom estimation if configured, otherwise assume TickData
        long bytesPerItem = estimatedBytesPerItem > 0 
                ? estimatedBytesPerItem 
                : params.estimateBytesPerTick();
        long totalBytes = (long) capacity * bytesPerItem;
        
        // Add overhead for TimestampedObject wrapper (~32 bytes per entry)
        long wrapperOverhead = (long) capacity * 32;
        totalBytes += wrapperOverhead;
        
        String itemType = estimatedBytesPerItem > 0 ? "item (custom)" : "tick";
        String explanation = String.format("%d capacity × %s/%s + %s wrapper overhead",
            capacity,
            SimulationParameters.formatBytes(bytesPerItem),
            itemType,
            SimulationParameters.formatBytes(wrapperOverhead));
        
        return List.of(new MemoryEstimate(
            getResourceName(),
            totalBytes,
            explanation,
            MemoryEstimate.Category.QUEUE
        ));
    }
    
    /**
     * Returns the configured capacity of this queue.
     * <p>
     * Exposed for memory estimation and monitoring purposes.
     *
     * @return The maximum number of elements this queue can hold.
     */
    public int getCapacity() {
        return capacity;
    }
}