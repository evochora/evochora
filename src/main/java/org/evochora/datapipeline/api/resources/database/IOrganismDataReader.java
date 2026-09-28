package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.resources.database.dto.LineageMutations;
import org.evochora.datapipeline.utils.LabelNamespaceMask;
import org.evochora.datapipeline.api.resources.database.dto.OrganismStaticInfo;
import org.evochora.datapipeline.api.resources.database.dto.ParentRows;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;

import java.sql.SQLException;
import java.util.List;

/**
 * Capability interface for reading indexed organism data.
 */
public interface IOrganismDataReader {

    /**
     * Reads all organisms that have state in {@code organism_states} for the given tick.
     *
     * @param tickNumber Tick number to query (must be &gt;= 0).
     * @return List of organism summaries for this tick (may be empty if no organisms exist).
     * @throws SQLException if database read fails.
     */
    List<OrganismTickSummary> readOrganismsAtTick(long tickNumber) throws SQLException;

    /**
     * Reads static and dynamic state of a single organism at the given tick.
     *
     * @param tickNumber Tick number to query.
     * @param organismId Organism identifier (must be &gt;= 0).
     * @return Detailed view of the organism at the given tick.
     * @throws SQLException if database read fails.
     * @throws OrganismNotFoundException if no state exists for the given organism at the given tick.
     */
    OrganismTickDetails readOrganismDetails(long tickNumber, int organismId)
            throws SQLException, OrganismNotFoundException;

    /**
     * Reads what is recorded about one organism itself, as opposed to about one of its ticks.
     * <p>
     * This is one row and no tick state at all, which is what a caller needs that wants to know
     * whether an organism existed at a tick, what it was born with, or where its body is
     * anchored. The ancestry is not part of it; {@link #readLineageMutations(int)} answers that.
     *
     * @param organismId Organism to look up (must be &gt;= 0).
     * @return Its static data, or {@code null} if no organism with that id is indexed.
     * @throws SQLException if database read fails.
     */
    OrganismStaticInfo readOrganismStaticInfo(int organismId) throws SQLException;

    /**
     * Reads the total number of organisms created up to (and including) the given tick.
     * <p>
     * This is the value the simulation reported for that tick, not a value derived from organism
     * ids. A tick for which no data was indexed has no such value.
     * <p>
     * The result is an {@code int} because the model is bounded at its root: organism ids are
     * {@code INT}, so a run cannot exceed that many organisms without overflowing the ids
     * themselves. An implementation must fail rather than truncate if the stored value exceeds
     * that range.
     *
     * @param tickNumber Tick number.
     * @return Total organisms created by this tick.
     * @throws SQLException if database read fails.
     * @throws TickNotFoundException if no data is indexed for the given tick.
     */
    int readTotalOrganismsCreated(long tickNumber) throws SQLException, TickNotFoundException;

    /**
     * Reads one page of the parent relation: the organisms with an id above {@code afterId}, in
     * ascending id order, at most {@code limit} of them, each with the id of its parent.
     * <p>
     * This is a keyset page, which a caller walks by passing the last id of one page as the
     * {@code afterId} of the next. A page shorter than {@code limit} is the last one. The read
     * holds its connection for one page only and never buffers more than one page, whatever the
     * size of the run, so a caller that reads a whole run can stop between any two pages.
     * <p>
     * An organism without a parent is reported with parent {@code 0}. Organism ids start at 1, so
     * {@code 0} is never the id of an organism.
     * <p>
     * Ids need not be contiguous: while a run is still being indexed, and in a run forked from
     * another, rows are missing between present ones. A page skips them.
     *
     * @param afterId Exclusive lower bound of the ids to read (must be &gt;= 0)
     * @param limit   Maximum number of rows to return (must be &gt; 0)
     * @return The page, possibly empty. Never null.
     * @throws SQLException if database read fails.
     */
    ParentRows readParents(int afterId, int limit) throws SQLException;

    /**
     * Reads the birth mutations of one organism and of every ancestor along {@code parent_id}.
     * <p>
     * A mutation is recorded at the birth of the organism that received it and stays there; a
     * descendant carries it in its body without carrying the record. Reading the whole chain is
     * therefore what it takes to see everything a displayed body was shaped by, and it is one
     * request per selected organism, not one per tick — which is why the chain is expected in a
     * single round trip rather than one query per ancestor.
     * <p>
     * <strong>What the result contains.</strong> One entry per organism of the chain, the given
     * organism first and the oldest ancestor last, so that walking the list is walking from a
     * descendant towards its ancestors. Every organism of the chain is present; one that received
     * no mutation at its birth carries the default instance of its event message. An implementation
     * returns the entries in that order and never an empty list — an organism that is not indexed
     * is reported as missing instead.
     *
     * @param organismId Organism to start the chain at (must be &gt;= 0).
     * @return The chain, given organism first, oldest ancestor last. Never null, never empty.
     * @throws SQLException if database read fails, or the stored events cannot be decoded.
     * @throws OrganismNotFoundException if no organism with that id is indexed.
     */
    List<LineageMutations> readLineageMutations(int organismId)
            throws SQLException, OrganismNotFoundException;

    /**
     * The label namespace the body of the organism at the head of a chain stands in.
     * <p>
     * Every newborn's LABEL and LABELREF values are XOR-masked at birth, cumulatively down the
     * lineage, so a label value in a body is the compiled value XORed with this mask. Anything that
     * holds a body's label value against the program it descends from — a procedure name, a jump
     * target, a comparison with the compiled code — has to take the mask out first.
     * <p>
     * It is a default method rather than something each implementation works out for itself,
     * because getting it wrong is silent: a mask that is zero when it should not be resolves every
     * label of every descendant to nothing, which looks exactly like an organism that has none.
     * Composing it from the chain an implementation already returns leaves one definition of the
     * rule and nothing for a new implementation to remember.
     *
     * @param chain The chain as {@link #readLineageMutations(int)} returns it, the organism first
     * @return The composed mask, zero for an ancestry that never rewrote a label
     * @throws IllegalStateException if a recorded label mask carries no mask value
     */
    default int labelNamespaceMaskOf(List<LineageMutations> chain) {
        return LabelNamespaceMask.ofChain(chain.stream().map(LineageMutations::events).toList());
    }
}


