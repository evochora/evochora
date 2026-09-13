package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.dto.ChunkIndexSummary;
import org.evochora.datapipeline.api.resources.database.dto.SampledTickRange;
import org.evochora.datapipeline.api.resources.database.dto.TickRange;

import java.sql.SQLException;
import java.util.List;

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
     * Reads how much environment data the run this reader was created for has indexed: the number
     * of chunks and the highest tick they reach.
     * <p>
     * One aggregate query, cheap enough to answer on every request. Because an indexer only
     * appends chunks, a caller that keeps a result derived from {@link #getTickRanges()} can hold
     * on to it for as long as this summary is unchanged.
     *
     * @return The chunk count and the highest last tick; a count of zero when nothing is indexed
     * @throws SQLException if database query fails
     */
    ChunkIndexSummary getChunkIndexSummary() throws SQLException;

    /**
     * Reads the stretches of ticks the run this reader was created for has recorded.
     * <p>
     * A run records only every n-th tick, and not necessarily from tick 0: a run forked from
     * another starts where the fork began, and a run may hold several stretches recorded at
     * different steps. Each returned range covers one such stretch - from its first to its last
     * recorded tick, in steps of {@link SampledTickRange#step()} - and the ranges come ordered by
     * their first tick. No tick outside these ranges, and none between the steps within one, is
     * part of the run; a viewer navigates along them and must not assume any other tick exists.
     *
     * @return The ranges ordered by first tick; empty when the run has recorded nothing
     * @throws SQLException if database query fails
     * @throws IllegalStateException if the stored chunks contradict each other - they overlap, or
     *                               one spans a stretch that does not fit the number of ticks it
     *                               holds - or if nothing in the run says what step it recorded at
     */
    List<SampledTickRange> getTickRanges() throws SQLException;

    /**
     * Gets the range of available organism ticks for the run this reader was created for.
     * <p>
     * Returns the minimum and maximum tick numbers that exist in the organism_states table.
     * If no ticks are available, returns null.
     *
     * @return TickRange containing minTick and maxTick, or null if no ticks exist
     * @throws SQLException if database query fails
     */
    TickRange getOrganismTickRange() throws SQLException;
    
    /**
     * Closes this reader and returns the connection to the pool.
     */
    @Override
    void close();
}
