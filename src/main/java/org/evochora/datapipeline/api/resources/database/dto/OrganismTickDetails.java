package org.evochora.datapipeline.api.resources.database.dto;

/**
 * Combined static and runtime view of an organism at a specific tick.
 */
public final class OrganismTickDetails {

    public final int organismId;
    public final long tick;
    public final OrganismStaticInfo staticInfo;
    public final OrganismRuntimeView state;

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


