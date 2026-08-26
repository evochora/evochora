package org.evochora.datapipeline.api.resources.database;

import org.evochora.datapipeline.api.resources.database.dto.OrganismTickDetails;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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
     * Reads the ancestor closure of the given genomes: every genome reachable upwards from them,
     * mapped to its parent genome.
     * <p>
     * <strong>What a parent genome is.</strong> A genome's parent genome is the genome of the
     * parent organism of that genome's <em>first carrier</em> — the carrier with the lowest
     * organism id among those that either have no parent, or whose parent carries a different
     * genome. Filtering precedes ordering: a carrier that inherited its genome unchanged is never
     * the first carrier, so a genome never becomes its own ancestor.
     * <p>
     * A first carrier with no parent, or whose parent carries genome hash 0, makes the genome a
     * root. So does a parent whose organism is not indexed, which happens while a run is still
     * being indexed and resolves itself once the missing rows arrive.
     * <p>
     * Genome hash 0 is not part of the relation. Organisms carrying it exist — a defective
     * replication loop can produce children without marked molecules — but 0 is neither a key nor
     * a parent, and requesting it yields nothing.
     * <p>
     * The relation is structural: it asks only whether a child's genome differs from its parent's,
     * never why. Mutation and defective replication are covered alike.
     * <p>
     * <strong>What the result means.</strong> A key present with a {@code null} value is a root.
     * An absent key means the genome does not occur in this run; every genome that does occur, other
     * than 0, has an entry. Callers depend on that distinction, so the returned map must permit null
     * values — {@code Map.of}, {@code Map.copyOf} and {@code Collectors.toMap} cannot be used to
     * build it.
     * <p>
     * <strong>Invariants an implementation may rely on.</strong> Organism ids are assigned in
     * creation order and a parent is always created before its child, so the lowest id is the
     * earliest carrier. It follows that the relation does not depend on any tick: the first carrier
     * of a genome visible at a tick was born no later than that tick. It also follows that the
     * relation is acyclic, since walking upwards strictly decreases the id of the first carrier.
     * <p>
     * Input collections of any size are accepted; an implementation whose query language limits
     * parameters batches internally rather than asking callers to split their request.
     *
     * @param genomeHashes Genomes to start from. May contain duplicates and genome hash 0.
     * @return Map of genomeHash → parentGenomeHash, null value for roots. Never null.
     * @throws SQLException if database read fails.
     */
    Map<Long, Long> readGenomeAncestors(Collection<Long> genomeHashes) throws SQLException;
}


