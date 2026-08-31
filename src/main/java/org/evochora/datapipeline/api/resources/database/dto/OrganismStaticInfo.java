package org.evochora.datapipeline.api.resources.database.dto;

import java.util.List;

/**
 * Static organism metadata as indexed in the organisms table.
 */
public final class OrganismStaticInfo {

    /** Id of the organism this one was forked from, {@code null} for an organism placed at the start of the run. */
    public final Integer parentId;    // nullable
    /** Simulation tick at which the organism came into existence. */
    public final long birthTick;
    /**
     * Id of the program artifact the organism was born with, used to resolve label hashes and to
     * disassemble its code. Since code mutates while the id is inherited unchanged, the artifact
     * need not describe the code the organism is actually running.
     */
    public final String programId;
    /** Coordinates the organism's instruction pointer started at, one component per environment dimension. */
    public final int[] initialPosition;
    /** Ancestry chain: direct parent first, oldest ancestor last. Empty for initial organisms. */
    public final List<LineageEntry> lineage;

    /**
     * Constructs a static organism view from the values held in the organisms table.
     *
     * @param parentId        Id of the parent organism, or {@code null} if the organism has none.
     * @param birthTick       Simulation tick at which the organism came into existence.
     * @param programId       Id of the program artifact the organism was born with.
     * @param initialPosition Coordinates the organism's instruction pointer started at.
     * @param lineage         Ancestry chain, direct parent first; empty for initial organisms.
     */
    public OrganismStaticInfo(Integer parentId,
                              long birthTick,
                              String programId,
                              int[] initialPosition,
                              List<LineageEntry> lineage) {
        this.parentId = parentId;
        this.birthTick = birthTick;
        this.programId = programId;
        this.initialPosition = initialPosition;
        this.lineage = lineage;
    }
}
