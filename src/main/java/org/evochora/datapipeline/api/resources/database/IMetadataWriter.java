package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;

/**
 * Defines the capability interface for a database that can store and retrieve
 * simulation metadata. This interface is used by the MetadataIndexer service.
 * <p>
 * <strong>Pure Capability Interface:</strong> This interface defines only the metadata writing
 * operations, without resource management concerns (IMonitorable). Implementations that ARE
 * resources (like wrappers) will get those concerns from their base classes (AbstractResource).
 * <p>
 * Extends {@link ISchemaAwareDatabase} - AbstractIndexer automatically calls
 * {@code setSimulationRun()} after run discovery to create and set the schema.
 * <p>
 * Implements {@link AutoCloseable} to enable try-with-resources pattern for
 * automatic connection cleanup:
 * <pre>
 * try (IMetadataWriter db = resource.getWrappedResource(context)) {
 *     // setSimulationRun() already called by AbstractIndexer (creates schema + sets it)
 *     db.insertMetadata(metadata);  // Creates metadata table on first call
 * }  // Connection automatically released
 * </pre>
 * <p>
 * <strong>Schema Creation:</strong> The database schema is created automatically by
 * AbstractIndexer via {@link ISchemaAwareDatabase#setSimulationRun(String)}. The metadata
 * table is created lazily on first {@link #insertMetadata(SimulationMetadata)} call using
 * idempotent CREATE TABLE IF NOT EXISTS.
 */
public interface IMetadataWriter extends AutoCloseable {

    /**
     * Writes the complete simulation metadata to the database. This operation
     * should be atomic, ensuring that either all metadata is inserted successfully
     * or the transaction is rolled back on failure.
     * <p>
     * Creates the metadata table on first call (idempotent via CREATE TABLE IF NOT EXISTS).
     * Uses MERGE/UPSERT for idempotency - safe to call multiple times with same data.
     *
     * @param metadata The {@link SimulationMetadata} protobuf message to persist.
     */
    void insertMetadata(SimulationMetadata metadata);

    /**
     * Checks if metadata already exists in the database for the given run.
     * <p>
     * Used to detect resume scenarios where metadata was already indexed in a previous run.
     * This allows services to skip waiting for metadata notifications when data already exists.
     *
     * @param simulationRunId The simulation run ID to check
     * @return true if metadata exists for this run, false otherwise
     */
    boolean hasMetadata(String simulationRunId);

    /**
     * Closes the database wrapper and releases its dedicated connection back to the pool.
     * <p>
     * This method is automatically called when used with try-with-resources.
     * Implementations must ensure the connection is properly closed even if errors occur.
     */
    @Override
    void close();
}