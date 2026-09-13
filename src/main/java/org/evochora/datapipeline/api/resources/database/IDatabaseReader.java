package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.dto.ChunkIndexSummary;
import org.evochora.datapipeline.api.resources.database.dto.SampledTickRange;
import org.evochora.datapipeline.api.resources.database.dto.TickRange;
import org.evochora.datapipeline.api.resources.database.dto.TickRangeExtension;

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
     * of chunks, the highest tick they reach and the ticks they hold together.
     * <p>
     * One aggregate query, cheap enough to answer on every request. A caller that keeps a result
     * derived from the ranges holds on to it for as long as this summary is unchanged, and where
     * it has changed, the summary tells an index that only grew at its end - count and sample
     * count raised by exactly what {@link #extendTickRanges(List, long)} read - from one whose
     * existing chunks were rewritten and which has to be read again in full.
     *
     * @return The chunk count, the highest last tick and the sample count; a count of zero when
     *         nothing is indexed
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
     * <p>
     * This reads every chunk of the run. While a run is being indexed, a caller that already holds
     * ranges continues them with {@link #extendTickRanges(List, long)} instead.
     *
     * @return The ranges ordered by first tick and what the read took in; the ranges are empty
     *         when the run has recorded nothing
     * @throws SQLException if database query fails
     * @throws IllegalStateException if the stored chunks contradict each other - they overlap, or
     *                               one spans a stretch that does not fit the number of ticks it
     *                               holds - or if nothing in the run says what step it recorded at
     */
    TickRangeExtension getTickRanges() throws SQLException;

    /**
     * Continues ranges read earlier with the chunks the run has indexed since.
     * <p>
     * Reads only the chunks beginning past {@code afterFirstTick} and lengthens the last of the
     * known ranges with them where they continue it, which is what a run that is still being
     * indexed does with every batch. The result equals a full {@link #getTickRanges()} as long as
     * the chunks before that tick are unchanged; the caller establishes that by comparing the
     * chunks and ticks taken in against the growth {@link #getChunkIndexSummary()} reports, and
     * reads again in full where they do not add up.
     *
     * @param known The ranges of the earlier read, ordered by first tick
     * @param afterFirstTick First tick of the last chunk the earlier read saw
     * @return The extended ranges and what this read took in
     * @throws SQLException if database query fails
     * @throws IllegalStateException on the same contradictions as {@link #getTickRanges()}
     */
    TickRangeExtension extendTickRanges(List<SampledTickRange> known, long afterFirstTick) throws SQLException;

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
