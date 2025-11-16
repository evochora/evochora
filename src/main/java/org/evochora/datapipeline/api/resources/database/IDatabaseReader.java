package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;

import java.sql.SQLException;

/**
 * Per-request database reader bundling all read capabilities.
 * <p>
 * Holds a dedicated connection with schema already set.
 * MUST be used with try-with-resources to ensure connection return to pool.
 * <p>
 * <strong>Note:</strong> Does NOT extend {@link IMetadataReader} directly because
 * readers know their runId from construction and don't need {@code getRunIdInCurrentSchema()}.
 * That method is only needed by wrappers/indexers via {@link IResourceSchemaAwareMetadataReader}.
 */
public interface IDatabaseReader extends IEnvironmentDataReader,
                                        IOrganismDataReader,
                                        AutoCloseable {
    /**
     * Gets simulation metadata for the run this reader was created for.
     * @return Metadata protobuf
     * @throws SQLException if database query fails
     * @throws MetadataNotFoundException if metadata doesn't exist for this run
     */
    SimulationMetadata getMetadata() throws SQLException, MetadataNotFoundException;
    
    /**
     * Checks if metadata exists for the run this reader was created for.
     * @return true if metadata exists
     * @throws SQLException if database query fails
     */
    boolean hasMetadata() throws SQLException;
    
    /**
     * Gets the range of available ticks for the run this reader was created for.
     * <p>
     * Returns the minimum and maximum tick numbers that exist in the database.
     * If no ticks are available, returns null.
     *
     * @return TickRange containing minTick and maxTick, or null if no ticks exist
     * @throws SQLException if database query fails
     */
    TickRange getTickRange() throws SQLException;
    
    /**
     * Closes this reader and returns the connection to the pool.
     */
    @Override
    void close();
}
