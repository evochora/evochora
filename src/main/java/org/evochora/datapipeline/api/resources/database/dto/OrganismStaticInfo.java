package org.evochora.datapipeline.api.resources.database.dto;

import java.util.List;

/**
 * What does not change over an organism's life: the values the organisms table holds for it, and
 * the label namespace composed from the births of its ancestry.
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
     * The label namespace this organism's body stands in: every LABEL and LABELREF value in it is
     * the value the program was compiled with, XORed with this mask. Zero for an organism whose
     * ancestry never rewrote a label, so a reader may apply it unconditionally. It is the mask
     * itself, not its inverse, because XOR is its own inverse: the same value turns a compiled
     * label value into this body's and back.
     */
    public final int labelNamespaceMask;

    /**
     * Constructs a static organism view from the values held in the organisms table.
     *
     * @param parentId           Id of the parent organism, or {@code null} if the organism has none.
     * @param birthTick          Simulation tick at which the organism came into existence.
     * @param programId          Id of the program artifact the organism was born with.
     * @param initialPosition    Coordinates the organism's instruction pointer started at.
     * @param lineage            Ancestry chain, direct parent first; empty for initial organisms.
     * @param labelNamespaceMask Composed label mask of every birth of the ancestry, this organism's
     *                           own included; zero if no birth rewrote a label.
     */
    public OrganismStaticInfo(Integer parentId,
                              long birthTick,
                              String programId,
                              int[] initialPosition,
                              List<LineageEntry> lineage,
                              int labelNamespaceMask) {
        this.parentId = parentId;
        this.birthTick = birthTick;
        this.programId = programId;
        this.initialPosition = initialPosition;
        this.lineage = lineage;
        this.labelNamespaceMask = labelNamespaceMask;
    }
}
