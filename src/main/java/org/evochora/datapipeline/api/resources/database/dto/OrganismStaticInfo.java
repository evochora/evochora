package org.evochora.datapipeline.api.resources.database.dto;

/**
 * Static organism metadata as indexed in the organisms table.
 */
public final class OrganismStaticInfo {

    public final Integer parentId;    // nullable
    public final long birthTick;
    public final String programId;
    public final int[] initialPosition;

    public OrganismStaticInfo(Integer parentId,
                              long birthTick,
                              String programId,
                              int[] initialPosition) {
        this.parentId = parentId;
        this.birthTick = birthTick;
        this.programId = programId;
        this.initialPosition = initialPosition;
    }
}


