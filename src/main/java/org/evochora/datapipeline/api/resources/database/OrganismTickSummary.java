package org.evochora.datapipeline.api.resources.database;

/**
 * Summary view of an organism at a specific tick.
 * <p>
 * Used by the HTTP API to populate grid and dropdown data for a tick.
 */
public final class OrganismTickSummary {

    public final int organismId;
    public final int energy;
    public final int[] ip;
    public final int[] dv;
    public final int[][] dataPointers;
    public final int activeDpIndex;

    public OrganismTickSummary(int organismId,
                               int energy,
                               int[] ip,
                               int[] dv,
                               int[][] dataPointers,
                               int activeDpIndex) {
        this.organismId = organismId;
        this.energy = energy;
        this.ip = ip;
        this.dv = dv;
        this.dataPointers = dataPointers;
        this.activeDpIndex = activeDpIndex;
    }
}


