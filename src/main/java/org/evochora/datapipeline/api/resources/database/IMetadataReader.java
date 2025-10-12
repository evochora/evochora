package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IMonitorable;

/**
 * Database capability for reading simulation metadata.
 * <p>
 * Provides read-only access to metadata written by MetadataIndexer.
 * Used by other indexers to access simulation configuration (e.g., samplingInterval for gap detection).
 * <p>
 * Extends {@link ISchemaAwareDatabase} - AbstractIndexer automatically calls
 * {@code setSimulationRun()} after run discovery to set the schema.
 * <p>
 * Implements {@link AutoCloseable} to enable try-with-resources pattern for
 * automatic connection cleanup.
 */
public interface IMetadataReader extends ISchemaAwareDatabase, IMonitorable, AutoCloseable {
    
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
     * Releases the cached database connection back to the pool.
     * <p>
     * Call before long idle periods (e.g., during polling sleeps) to reduce
     * connection pool pressure. Connection will be re-acquired automatically on next operation.
     */
    void releaseConnection();
    
    @Override
    void close();
}

