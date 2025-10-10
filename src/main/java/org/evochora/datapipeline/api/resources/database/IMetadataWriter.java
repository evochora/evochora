package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;

/**
 * Defines the capability interface for a database that can store and retrieve
 * simulation metadata. This interface is used by the MetadataIndexer service.
 * <p>
 * Implements {@link AutoCloseable} to enable try-with-resources pattern for
 * automatic connection cleanup:
 * <pre>
 * try (IMetadataWriter db = resource.getWrappedResource(context)) {
 *     db.createSimulationRun(runId);
 *     db.setSimulationRun(runId);
 *     db.insertMetadata(metadata);
 * }  // Connection automatically released
 * </pre>
 */
public interface IMetadataWriter extends IResource, IMonitorable, AutoCloseable {
    /**
     * Sets the database schema for all subsequent operations on the current connection/wrapper.
     * This must be called once after an indexer discovers its target simulation run ID.
     * This method is thread-safe, as each wrapper instance holds an isolated connection.
     *
     * @param simulationRunId The unique identifier for the simulation run.
     */
    void setSimulationRun(String simulationRunId);

    /**
     * Creates a new schema in the database for a specific simulation run.
     * Implementations should be idempotent, typically using 'CREATE SCHEMA IF NOT EXISTS'.
     *
     * @param simulationRunId The unique identifier for the simulation run, which will be
     *                        sanitized to a valid schema name (e.g., sim_20251006143025_uuid).
     */
    void createSimulationRun(String simulationRunId);

    /**
     * Writes the complete simulation metadata to the database. This operation
     * should be atomic, ensuring that either all metadata is inserted successfully
     * or the transaction is rolled back on failure.
     *
     * @param metadata The {@link SimulationMetadata} protobuf message to persist.
     */
    void insertMetadata(SimulationMetadata metadata);

    /**
     * Closes the database wrapper and releases its dedicated connection back to the pool.
     * <p>
     * This method is automatically called when used with try-with-resources.
     * Implementations must ensure the connection is properly closed even if errors occur.
     */
    @Override
    void close();
}