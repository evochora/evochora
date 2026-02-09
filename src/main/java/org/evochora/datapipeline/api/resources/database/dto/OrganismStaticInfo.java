package org.evochora.datapipeline.api.resources.database.dto;

import java.util.List;

/**
 * Static organism metadata as indexed in the organisms table.
 */
public final class OrganismStaticInfo {

    public final Integer parentId;    // nullable
    public final long birthTick;
    public final String programId;
    public final int[] initialPosition;
    /** Ancestry chain: direct parent first, oldest ancestor last. Empty for initial organisms. */
    public final List<LineageEntry> lineage;

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
