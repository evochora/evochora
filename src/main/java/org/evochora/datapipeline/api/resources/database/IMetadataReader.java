package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;

/**
 * Database capability for reading simulation metadata.
 * <p>
 * Provides read-only access to metadata written by MetadataIndexer.
 * Used by other indexers to access simulation configuration (e.g., samplingInterval for gap detection).
 * <p>
 * <strong>Pure Capability Interface:</strong> This interface defines only the metadata reading
 * operations, without resource management concerns (IMonitorable). Implementations that ARE
 * resources (like wrappers) will get those concerns from their base classes (AbstractResource).
 * <p>
 * Extends {@link ISchemaAwareDatabase} - AbstractIndexer automatically calls
 * {@code setSimulationRun()} after run discovery to set the schema.
 * <p>
 * Implements {@link AutoCloseable} to enable try-with-resources pattern for
 * automatic connection cleanup.
 */
public interface IMetadataReader extends AutoCloseable {
    
    /**
     * Retrieves simulation metadata for the current schema.
     * <p>
     * Reads from the metadata table in the active schema (set via setSimulationRun).
     * Parses JSON values and reconstructs SimulationMetadata protobuf.
     *
     * @param simulationRunId The simulation run ID (for validation and error messages)
     * @return The complete simulation metadata
     * @throws MetadataNotFoundException if metadata doesn't exist for this run
     */
    SimulationMetadata getMetadata(String simulationRunId) throws MetadataNotFoundException;
    
    /**
     * Checks if metadata exists for the current schema.
     * <p>
     * Non-blocking check used for polling scenarios.
     *
     * @param simulationRunId The simulation run ID
     * @return true if metadata exists, false otherwise
     */
    boolean hasMetadata(String simulationRunId);
    
    
    
    @Override
    void close();
}

