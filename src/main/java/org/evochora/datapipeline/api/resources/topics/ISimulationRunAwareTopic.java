package org.evochora.datapipeline.api.resources.topics;

import org.evochora.datapipeline.api.resources.IResource;

/**
 * Base capability for topic operations that work within a simulation run context.
 * <p>
 * All topic capabilities that operate on run-specific data extend this interface.
 * The simulation run is set once per delegate instance after creation.
 * <p>
 * <strong>Lifecycle:</strong>
 * <ol>
 *   <li>Service/Indexer creates topic delegate (writer or reader)</li>
 *   <li>Service/Indexer calls setSimulationRun(runId) on the delegate</li>
 *   <li>All subsequent operations on the delegate work within that simulation run</li>
 * </ol>
 * <p>
 * <strong>Run Isolation:</strong>
 * Each simulation run has its own isolated storage (e.g., H2 schema, Chronicle path).
 * This provides complete data isolation between runs and enables automatic cleanup
 * when a run's schema/directory is deleted.
 * <p>
 * <strong>Implementation Strategy:</strong>
 * <ul>
 *   <li>H2: Uses database schema (e.g., SIM_20251006_UUID)</li>
 *   <li>Chronicle: Uses directory path (e.g., ./data/SIM_20251006_UUID/topic_name)</li>
 *   <li>Kafka: Uses topic suffix (e.g., batch-notifications-SIM_20251006_UUID)</li>
 * </ul>
 */
public interface ISimulationRunAwareTopic extends IResource {
    /**
     * Sets the simulation run for this topic delegate.
     * <p>
     * Must be called once before any topic operations (send/receive).
     * Implementation-specific behavior:
     * <ul>
     *   <li>H2: Creates and sets database schema</li>
     *   <li>Chronicle: Adjusts queue path</li>
     *   <li>Kafka: Adjusts topic name</li>
     * </ul>
     *
     * @param simulationRunId Raw simulation run ID (sanitized internally if needed)
     * @throws IllegalStateException if already set (delegates are single-run)
     */
    void setSimulationRun(String simulationRunId);
}

