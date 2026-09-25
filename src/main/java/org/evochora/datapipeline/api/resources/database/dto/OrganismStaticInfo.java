package org.evochora.datapipeline.api.resources.database.dto;

/**
 * What the organisms table holds about one organism: the values that are settled at its birth,
 * plus the tick it died at.
 * <p>
 * Everything here belongs to the organism and not to a tick, so this view is the same whichever
 * tick it is read at. The one value that is not there from the start is {@link #deathTick}, and
 * it is still not a property of a tick: it is written once, when a recorded tick reports the
 * organism as dead, and never changes afterwards.
 */
public final class OrganismStaticInfo {

    /** Id of the organism this one was forked from, {@code null} for an organism placed at the start of the run. */
    public final Integer parentId;    // nullable
    /** Simulation tick at which the organism came into existence. */
    public final long birthTick;
    /**
     * Simulation tick at which the organism died, or {@code -1} if no recorded tick has reported
     * it as dead yet.
     * <p>
     * The two cases behind {@code -1} are not distinguishable here: the organism may still be
     * alive, or it may have died at the growing end of a run whose later ticks are not indexed
     * yet. The second corrects itself once the tick that carries the death has been indexed.
     */
    public final long deathTick;
    /**
     * Id of the program artifact the organism was born with, used to resolve label hashes and to
     * disassemble its code. Since code mutates while the id is inherited unchanged, the artifact
     * need not describe the code the organism is actually running.
     */
    public final String programId;
    /** Coordinates the organism's instruction pointer started at, one component per environment dimension. */
    public final int[] initialPosition;
    /** Genome hash the organism was born with, zero for an organism without genome molecules. */
    public final long genomeHash;
    /** Number of replications between the founding organisms and this one; a founder is zero. */
    public final int generation;
    /**
     * Genome hash its parent carried at this organism's birth, {@code null} for an organism
     * without a parent and zero for a parent that carried no genome molecules.
     */
    public final Long parentGenomeHash;

    /**
     * Constructs a static organism view from the values held in the organisms table.
     *
     * @param parentId          Id of the parent organism, or {@code null} if the organism has none.
     * @param birthTick         Simulation tick at which the organism came into existence.
     * @param deathTick         Simulation tick it died at, or {@code -1} if no tick has reported it dead.
     * @param programId         Id of the program artifact the organism was born with.
     * @param initialPosition   Coordinates the organism's instruction pointer started at.
     * @param genomeHash        Genome hash the organism was born with.
     * @param generation        Replications between the founding organisms and this one.
     * @param parentGenomeHash  Genome hash the parent carried at this birth, or {@code null}.
     */
    public OrganismStaticInfo(Integer parentId,
                              long birthTick,
                              long deathTick,
                              String programId,
                              int[] initialPosition,
                              long genomeHash,
                              int generation,
                              Long parentGenomeHash) {
        this.parentId = parentId;
        this.birthTick = birthTick;
        this.deathTick = deathTick;
        this.programId = programId;
        this.initialPosition = initialPosition;
        this.genomeHash = genomeHash;
        this.generation = generation;
        this.parentGenomeHash = parentGenomeHash;
    }

    /**
     * Whether the organism existed at the given tick: from its birth on, up to but not including
     * the tick it died at.
     *
     * @param tick The tick to test
     * @return {@code true} if it existed at that tick
     */
    public boolean aliveAt(long tick) {
        return birthTick <= tick && (deathTick < 0 || deathTick > tick);
    }
}
