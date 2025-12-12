package org.evochora.datapipeline.api.resources.database.dto;

/**
 * Summary view of an organism at a specific tick.
 * <p>
 * Used by the HTTP API to populate grid and dropdown data for a tick.
 * Includes both per-tick state (energy, ip, dv, dataPointers) and static
 * organism metadata (parentId, birthTick) via a JOIN with the organisms table.
 */
public final class OrganismTickSummary {

    public final int organismId;
    public final int energy;
    public final int[] ip;
    public final int[] dv;
    public final int[][] dataPointers;
    public final int activeDpIndex;
    public final Integer parentId;
    public final long birthTick;
    public final int entropyRegister;  // SR

    /**
     * Creates a new organism tick summary.
     *
     * @param organismId      The unique organism identifier.
     * @param energy          The current energy level.
     * @param ip              The instruction pointer coordinates.
     * @param dv              The direction vector.
     * @param dataPointers    The data pointer coordinates.
     * @param activeDpIndex   The index of the active data pointer.
     * @param parentId        The parent organism ID (null if no parent).
     * @param birthTick       The tick at which the organism was born.
     * @param entropyRegister The entropy register (SR) value.
     */
    public OrganismTickSummary(int organismId,
                               int energy,
                               int[] ip,
                               int[] dv,
                               int[][] dataPointers,
                               int activeDpIndex,
                               Integer parentId,
                               long birthTick,
                               int entropyRegister) {
        this.organismId = organismId;
        this.energy = energy;
        this.ip = ip;
        this.dv = dv;
        this.dataPointers = dataPointers;
        this.activeDpIndex = activeDpIndex;
        this.parentId = parentId;
        this.birthTick = birthTick;
        this.entropyRegister = entropyRegister;
    }
}


