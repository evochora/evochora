package org.evochora.datapipeline.api.resources.database.dto;

/**
 * Combined static and runtime view of an organism at a specific tick.
 */
public final class OrganismTickDetails {

    /** Id of the organism this view describes. */
    public final int organismId;
    /** The indexed tick the runtime state was sampled at; not every tick of the run is indexed. */
    public final long tick;
    /** Birth-time metadata of the organism, identical for every tick of its life. */
    public final OrganismStaticInfo staticInfo;
    /** Runtime state of the organism at {@link #tick}. */
    public final OrganismRuntimeView state;

    /**
     * Combines the organism's birth-time metadata with its state at one tick.
     *
     * @param organismId Id of the organism.
     * @param tick       The indexed tick the state was sampled at.
     * @param staticInfo Birth-time metadata of the organism.
     * @param state      Runtime state at that tick.
     */
    public OrganismTickDetails(int organismId,
                               long tick,
                               OrganismStaticInfo staticInfo,
                               OrganismRuntimeView state) {
        this.organismId = organismId;
        this.tick = tick;
        this.staticInfo = staticInfo;
        this.state = state;
    }
}


