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
public interface IMetadataReader extends ISchemaAwareDatabase, AutoCloseable {
    
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
    
    /**
     * Retrieves the simulation run ID from metadata in the current schema.
     * <p>
     * This method reads the run-id from the metadata table without requiring
     * the run-id as a parameter. Used for run-id discovery scenarios where
     * the schema is known but the original run-id needs to be retrieved.
     * <p>
     * <strong>Use Case:</strong> HTTP API controllers discovering the latest
     * run-id by querying the latest schema's metadata.
     * <p>
     * <strong>Precondition:</strong> Schema must be set via {@code setSimulationRun()}
     * before calling this method.
     *
     * @return The simulation run ID stored in current schema's metadata
     * @throws MetadataNotFoundException if metadata doesn't exist in current schema
     */
    String getRunIdInCurrentSchema() throws MetadataNotFoundException;
    
    /**
     * Releases the cached database connection back to the pool.
     * <p>
     * Call before long idle periods (e.g., during polling sleeps) to reduce
     * connection pool pressure. Connection will be re-acquired automatically on next operation.
     */
    void releaseConnection();
    
    @Override
    void close();
}

