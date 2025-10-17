package org.evochora.datapipeline.resources.topics;

import com.google.protobuf.Message;
import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ISimulationRunAwareTopic;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for all topic resource implementations.
 * <p>
 * This class provides the Template Method pattern for creating reader and writer delegates,
 * ensuring consistent behavior across all topic implementations (H2, Chronicle, Kafka, Cloud).
 * <p>
 * <strong>Delegate Pattern:</strong>
 * Topics use delegate classes (not inner classes) because each delegate instance needs its own state:
 * <ul>
 *   <li><strong>Readers:</strong> Each has its own consumer group and read position</li>
 *   <li><strong>Writers:</strong> Each tracks its own send metrics per service</li>
 * </ul>
 * <p>
 * Delegates extend {@link AbstractTopicDelegate} (which extends {@link AbstractResource}) to inherit:
 * <ul>
 *   <li>{@link AbstractResource#recordError(String, String, String)} - delegate-specific errors</li>
 *   <li>{@link AbstractResource#addCustomMetrics(Map)} - delegate-specific metrics</li>
 *   <li>{@link AbstractResource#isHealthy()} - delegate-specific health</li>
 *   <li>Type-safe access to parent resource via generic parameter {@code <P>}</li>
 * </ul>
 * <p>
 * <strong>Lifecycle Management:</strong>
 * The parent resource tracks all active delegates and closes them on shutdown.
 * <p>
 * <strong>Aggregate Metrics:</strong>
 * The parent resource maintains aggregate metrics (messagesPublished, messagesReceived, messagesAcknowledged)
 * across ALL delegates. Individual delegates also track their own per-service metrics.
 * <p>
 * <strong>Simulation Run Awareness:</strong>
 * This class implements {@link ISimulationRunAwareTopic} and automatically propagates the simulation run ID
 * to all delegates when {@link #setSimulationRun(String)} is called. Subclasses can override
 * {@link #onSimulationRunSet(String)} to perform technology-specific setup (e.g., schema creation for H2,
 * queue directory creation for Chronicle).
 * <p>
 * <strong>Subclass Responsibilities:</strong>
 * Implement {@link #createReaderDelegate(ResourceContext)} and {@link #createWriterDelegate(ResourceContext)}
 * to provide technology-specific readers and writers (H2, Chronicle, Kafka, etc.).
 *
 * @param <T> The message type (must be a Protobuf {@link Message}).
 * @param <ACK> The acknowledgment token type (implementation-specific, e.g., {@code Long} for H2/Chronicle).
 */
public abstract class AbstractTopicResource<T extends Message, ACK> extends AbstractResource implements IContextualResource, ISimulationRunAwareTopic, AutoCloseable {
    
    private static final Logger log = LoggerFactory.getLogger(AbstractTopicResource.class);
    
    // Delegate lifecycle management
    protected final Set<AutoCloseable> activeDelegates = ConcurrentHashMap.newKeySet();
    
    // Simulation run awareness
    private volatile String simulationRunId = null;
    
    // Aggregate metrics (across ALL delegates/services)
    protected final AtomicLong messagesPublished = new AtomicLong(0);
    protected final AtomicLong messagesReceived = new AtomicLong(0);
    protected final AtomicLong messagesAcknowledged = new AtomicLong(0);
    protected final SlidingWindowCounter writeThroughput;
    protected final SlidingWindowCounter readThroughput;
    
    /**
     * Creates a new AbstractTopicResource.
     *
     * @param name The resource name.
     * @param options The configuration options.
     */
    protected AbstractTopicResource(String name, Config options) {
        super(name, options);
        
        // Initialize throughput metrics with configurable window
        int metricsWindow = options.hasPath("metricsWindowSeconds")
            ? options.getInt("metricsWindowSeconds")
            : 60;  // Default: 60 seconds
        this.writeThroughput = new SlidingWindowCounter(metricsWindow);
        this.readThroughput = new SlidingWindowCounter(metricsWindow);
    }
    
    /**
     * Creates a reader delegate for the specified context.
     * <p>
     * Template method - subclasses must implement this to return technology-specific readers.
     * <p>
     * The returned delegate MUST:
     * <ul>
     *   <li>Extend {@link AbstractTopicDelegateReader}</li>
     *   <li>Implement {@link ITopicReader}</li>
     *   <li>Implement {@link AutoCloseable}</li>
     *   <li>Extract consumer group from {@code context.parameters().get("consumerGroup")}</li>
     * </ul>
     *
     * @param context The resource context (includes consumerGroup parameter).
     * @return The reader delegate.
     * @throws IllegalArgumentException if consumerGroup parameter is missing or invalid.
     */
    protected abstract ITopicReader<T, ACK> createReaderDelegate(ResourceContext context);
    
    /**
     * Creates a writer delegate for the specified context.
     * <p>
     * Template method - subclasses must implement this to return technology-specific writers.
     * <p>
     * The returned delegate MUST:
     * <ul>
     *   <li>Extend {@link AbstractTopicDelegateWriter}</li>
     *   <li>Implement {@link ITopicWriter}</li>
     *   <li>Implement {@link AutoCloseable}</li>
     * </ul>
     *
     * @param context The resource context.
     * @return The writer delegate.
     */
    protected abstract ITopicWriter<T> createWriterDelegate(ResourceContext context);
    
    @Override
    public final IWrappedResource getWrappedResource(ResourceContext context) {
        if (context.usageType() == null) {
            throw new IllegalArgumentException(String.format(
                "Topic resource '%s' requires a usageType in the binding URI. " +
                "Expected format: 'usageType:%s' where usageType is one of: topic-write, topic-read",
                getResourceName(), getResourceName()));
        }
        
        IWrappedResource delegate = switch (context.usageType()) {
            case "topic-write" -> (IWrappedResource) createWriterDelegate(context);
            case "topic-read" -> (IWrappedResource) createReaderDelegate(context);
            default -> throw new IllegalArgumentException(String.format(
                "Unsupported usage type '%s' for topic resource '%s'. Supported: topic-write, topic-read",
                context.usageType(), getResourceName()));
        };
        
        // Track delegate for lifecycle management
        if (delegate instanceof AutoCloseable) {
            activeDelegates.add((AutoCloseable) delegate);
        }
        
        // Auto-inherit simulation run ID if parent already has it set
        // This supports both orderings:
        // 1) setSimulationRun() → createDelegate() → auto-inherit
        // 2) createDelegate() → setSimulationRun() → propagate (handled in setSimulationRun)
        if (simulationRunId != null && delegate instanceof ISimulationRunAwareTopic aware) {
            aware.setSimulationRun(simulationRunId);
        }
        
        log.debug("Created delegate for topic '{}': type={}, service={}, runId={}",
            getResourceName(), context.usageType(), context.serviceName(), 
            simulationRunId != null ? simulationRunId : "not-set");
        
        return delegate;
    }
    
    @Override
    public final UsageState getUsageState(String usageType) {
        if (usageType == null) {
            throw new IllegalArgumentException("UsageType cannot be null for topic '" + getResourceName() + "'");
        }
        
        return switch (usageType) {
            case "topic-write" -> getWriteUsageState();
            case "topic-read" -> getReadUsageState();
            default -> throw new IllegalArgumentException(String.format(
                "Unsupported usage type '%s' for topic resource '%s'. Supported: topic-write, topic-read",
                usageType, getResourceName()));
        };
    }
    
    /**
     * Template method for subclasses to provide write usage state logic.
     *
     * @return The current write usage state.
     */
    protected abstract UsageState getWriteUsageState();
    
    /**
     * Template method for subclasses to provide read usage state logic.
     *
     * @return The current read usage state.
     */
    protected abstract UsageState getReadUsageState();
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        metrics.put("messages_published", messagesPublished.get());
        metrics.put("messages_received", messagesReceived.get());
        metrics.put("messages_acknowledged", messagesAcknowledged.get());
        metrics.put("write_throughput_per_sec", writeThroughput.getWindowSum());
        metrics.put("read_throughput_per_sec", readThroughput.getWindowSum());
    }
    
    /**
     * Records a message write operation.
     * <p>
     * Called by writer delegates after successful message publication.
     * Updates both counter and throughput metrics.
     * <p>
     * Subclasses may override to add implementation-specific metrics, but MUST call super.recordWrite().
     */
    protected void recordWrite() {
        messagesPublished.incrementAndGet();
        writeThroughput.recordCount();
    }
    
    /**
     * Records a message read operation.
     * <p>
     * Called by reader delegates after successful message claim.
     * Updates both counter and throughput metrics.
     * <p>
     * Subclasses may override to add implementation-specific metrics, but MUST call super.recordRead().
     */
    protected void recordRead() {
        messagesReceived.incrementAndGet();
        readThroughput.recordCount();
    }
    
    /**
     * Records a message acknowledgment.
     * <p>
     * Called by reader delegates after successful message acknowledgment.
     * Subclasses may override to add implementation-specific metrics, but MUST call super.recordAcknowledge().
     */
    protected void recordAcknowledge() {
        messagesAcknowledged.incrementAndGet();
    }
    
    /**
     * Returns the current simulation run ID, or null if not yet set.
     *
     * @return The simulation run ID, or null.
     */
    public final String getSimulationRunId() {
        return simulationRunId;
    }
    
    @Override
    public final void setSimulationRun(String simulationRunId) {
        if (simulationRunId == null || simulationRunId.isBlank()) {
            throw new IllegalArgumentException("Simulation run ID must not be null or blank");
        }
        
        // Synchronized to prevent race condition when multiple services call setSimulationRun() concurrently
        synchronized (this) {
            // Idempotent: skip if already set to the same run ID
            if (this.simulationRunId != null) {
                if (this.simulationRunId.equals(simulationRunId)) {
                    log.debug("Simulation run '{}' already set for topic '{}'", simulationRunId, getResourceName());
                    return;
                } else {
                    throw new IllegalStateException(String.format(
                        "Cannot change simulation run ID from '%s' to '%s' for topic '%s'. " +
                        "Run ID is immutable once set.",
                        this.simulationRunId, simulationRunId, getResourceName()));
                }
            }
            
            this.simulationRunId = simulationRunId;
            
            // Template method: Let subclass perform technology-specific setup
            onSimulationRunSet(simulationRunId);
            
            // Propagate run-ID to all existing delegates (created before setSimulationRun was called)
            propagateSimulationRunToDelegates(simulationRunId);
            
            log.debug("Simulation run '{}' set for topic '{}'", simulationRunId, getResourceName());
        }
    }
    
    /**
     * Template method called after simulation run ID is set.
     * <p>
     * Subclasses should override this to perform technology-specific setup:
     * <ul>
     *   <li><strong>H2:</strong> Create schema, tables, and register triggers</li>
     *   <li><strong>Chronicle:</strong> Create run-specific queue directory</li>
     *   <li><strong>Kafka:</strong> Create run-specific topic partitions</li>
     * </ul>
     * <p>
     * <strong>Note:</strong> This method is called BEFORE delegates are notified, so subclasses
     * can prepare the infrastructure (schema, directories, etc.) that delegates will need.
     *
     * @param simulationRunId The simulation run ID.
     */
    protected void onSimulationRunSet(String simulationRunId) {
        // Default: no-op. Subclasses override for technology-specific setup.
    }
    
    /**
     * Propagates the simulation run ID to all existing delegates.
     * <p>
     * This is necessary because delegates are created by the ServiceManager BEFORE
     * the service calls setSimulationRun() on the parent resource. Each delegate
     * needs to be notified so it can switch its connection/state to the correct run.
     *
     * @param simulationRunId The simulation run ID to propagate.
     */
    private void propagateSimulationRunToDelegates(String simulationRunId) {
        int delegateCount = 0;
        for (AutoCloseable delegate : activeDelegates) {
            if (delegate instanceof ISimulationRunAwareTopic aware) {
                aware.setSimulationRun(simulationRunId);
                delegateCount++;
            }
        }
        
        if (delegateCount > 0) {
            log.debug("Propagated simulation run '{}' to {} delegate(s) for topic '{}'",
                simulationRunId, delegateCount, getResourceName());
        }
    }
    
    @Override
    public void close() throws Exception {
        if (!activeDelegates.isEmpty()) {
            log.info("Closing {} delegates for topic '{}'", activeDelegates.size(), getResourceName());
        }
        
        // Close all active delegates
        for (AutoCloseable delegate : activeDelegates) {
            try {
                delegate.close();
            } catch (Exception e) {
                log.warn("Failed to close delegate for topic '{}'", getResourceName());
                recordError("DELEGATE_CLOSE_FAILED", "Failed to close delegate", "Topic: " + getResourceName());
            }
        }
        activeDelegates.clear();
    }
}

