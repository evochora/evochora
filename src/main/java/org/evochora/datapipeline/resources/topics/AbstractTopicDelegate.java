package org.evochora.datapipeline.resources.topics;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.topics.ISimulationRunAwareTopic;
import org.evochora.datapipeline.resources.AbstractResource;

import java.util.Map;

/**
 * Abstract base class for all topic delegates (readers and writers).
 * <p>
 * This class provides type-safe access to the parent topic resource and integrates
 * with {@link AbstractResource} for error tracking, metrics, and health monitoring.
 * <p>
 * <strong>Type Safety:</strong>
 * The generic parameter {@code <P>} ensures compile-time type safety when accessing
 * parent resource methods.
 * <p>
 * <strong>Error Tracking:</strong>
 * Delegates track their own errors independently from the parent resource. Use
 * {@link #recordError(String, String, String)} for delegate-specific errors.
 * <p>
 * <strong>Metrics:</strong>
 * Delegates override {@link #addCustomMetrics(Map)} to expose parent's aggregate
 * metrics plus their own delegate-specific metrics.
 * <p>
 * <strong>Consumer Group (Readers Only):</strong>
 * Reader delegates extract the consumer group from {@code context.parameters().get("consumerGroup")}.
 * Writer delegates do not use consumer groups.
 * <p>
 * <strong>Simulation Run Awareness:</strong>
 * Implements {@link ISimulationRunAwareTopic} to store the simulation run ID and provide
 * access to subclasses via {@link #getSimulationRunId()}. Subclasses can override
 * {@link #onSimulationRunSet(String)} to perform run-specific initialization.
 *
 * @param <P> The parent topic resource type.
 */
public abstract class AbstractTopicDelegate<P extends AbstractTopicResource<?, ?>> extends AbstractResource implements IWrappedResource, ISimulationRunAwareTopic, AutoCloseable {
    
    protected final P parent;
    protected final String consumerGroup;  // Only used by readers
    
    /**
     * The simulation run ID for this delegate.
     * Set via {@link #setSimulationRun(String)} before sending/reading messages.
     */
    private String simulationRunId;
    
    /**
     * Creates a new topic delegate.
     *
     * @param parent The parent topic resource.
     * @param context The resource context.
     */
    protected AbstractTopicDelegate(P parent, ResourceContext context) {
        super(context.serviceName() + "-" + context.usageType(), parent.getOptions());
        this.parent = parent;
        this.consumerGroup = context.parameters().get("consumerGroup");  // May be null for writers
    }
    
    @Override
    public final void setSimulationRun(String simulationRunId) {
        if (simulationRunId == null || simulationRunId.isBlank()) {
            throw new IllegalArgumentException("Simulation run ID must not be null or blank");
        }
        this.simulationRunId = simulationRunId;
        onSimulationRunSet(simulationRunId);
    }
    
    /**
     * Returns the simulation run ID for this delegate.
     * <p>
     * Subclasses can use this to access the run ID for schema selection, path construction, etc.
     *
     * @return The simulation run ID, or null if not yet set.
     */
    protected final String getSimulationRunId() {
        return simulationRunId;
    }
    
    /**
     * Template method called after {@link #setSimulationRun(String)} is invoked.
     * <p>
     * Subclasses can override this to perform run-specific initialization:
     * <ul>
     *   <li>H2: Set database schema to run-specific schema</li>
     *   <li>Chronicle: Open run-specific queue directory</li>
     *   <li>Kafka: Subscribe to run-specific topic partition</li>
     * </ul>
     * <p>
     * Default implementation is a no-op.
     *
     * @param simulationRunId The simulation run ID (never null or blank).
     */
    protected void onSimulationRunSet(String simulationRunId) {
        // Default: no-op (subclasses override as needed)
    }
    
    public final AbstractResource getWrappedResource() {
        return parent;
    }
    
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        
        // Include parent's aggregate metrics (type-safe access!)
        metrics.put("parent_messages_published", parent.messagesPublished.get());
        metrics.put("parent_messages_received", parent.messagesReceived.get());
        metrics.put("parent_messages_acknowledged", parent.messagesAcknowledged.get());
    }
}

