package org.evochora.datapipeline.api.resources.database.dto;

import java.util.List;

/**
 * Combined static and runtime view of an organism at a specific tick, together with what a walk
 * over its ancestry yields.
 * <p>
 * The ancestry stands beside the static data rather than in it: the static data is one row of the
 * organisms table, while the chain and the label namespace are the result of following
 * {@code parent_id} from birth to birth. A caller that only needs the row is not made to pay for
 * that walk.
 */
public final class OrganismTickDetails {

    /** Id of the organism this view describes. */
    public final int organismId;
    /** The indexed tick the runtime state was sampled at; not every tick of the run is indexed. */
    public final long tick;
    /** What the organisms table holds about it, identical for every tick of its life. */
    public final OrganismStaticInfo staticInfo;
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
    /** Runtime state of the organism at {@link #tick}. */
    public final OrganismRuntimeView state;

    /**
     * Combines the organism's static data and ancestry with its state at one tick.
     *
     * @param organismId         Id of the organism.
     * @param tick               The indexed tick the state was sampled at.
     * @param staticInfo         What the organisms table holds about it.
     * @param lineage            Ancestry chain, direct parent first; empty for initial organisms.
     * @param labelNamespaceMask Composed label mask of every birth of the ancestry.
     * @param state              Runtime state at that tick.
     */
    public OrganismTickDetails(int organismId,
                               long tick,
                               OrganismStaticInfo staticInfo,
                               List<LineageEntry> lineage,
                               int labelNamespaceMask,
                               OrganismRuntimeView state) {
        this.organismId = organismId;
        this.tick = tick;
        this.staticInfo = staticInfo;
        this.lineage = lineage;
        this.labelNamespaceMask = labelNamespaceMask;
        this.state = state;
    }
}


