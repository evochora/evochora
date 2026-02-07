package org.evochora.datapipeline.api.resources.database.dto;

/**
 * Summary view of an organism at a specific tick.
 * <p>
 * Used by the HTTP API to populate grid and dropdown data for a tick.
 * Includes both per-tick state (energy, ip, dv, dataPointers) and static
 * organism metadata (parentId, birthTick) via a JOIN with the organisms table.
 */
public final class OrganismTickSummary {

    /** The unique organism identifier. */
    public final int organismId;
    /** The current energy level (ER). */
    public final int energy;
    /** The instruction pointer coordinates. */
    public final int[] ip;
    /** The direction vector for IP advancement. */
    public final int[] dv;
    /** The data pointer coordinates (one per DP index). */
    public final int[][] dataPointers;
    /** The index of the currently active data pointer (0 or 1). */
    public final int activeDpIndex;
    /** The parent organism ID, or {@code null} if this is a primordial organism. */
    public final Integer parentId;
    /** The tick at which the organism was born. */
    public final long birthTick;
    /** The entropy register (SR) value - thermodynamic constraint. */
    public final int entropyRegister;
    /** The genome hash computed at birth, or 0 if not available. */
    public final long genomeHash;

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
     * @param genomeHash      The genome hash computed at birth.
     */
    public OrganismTickSummary(int organismId,
                               int energy,
                               int[] ip,
                               int[] dv,
                               int[][] dataPointers,
                               int activeDpIndex,
                               Integer parentId,
                               long birthTick,
                               int entropyRegister,
                               long genomeHash) {
        this.organismId = organismId;
        this.energy = energy;
        this.ip = ip;
        this.dv = dv;
        this.dataPointers = dataPointers;
        this.activeDpIndex = activeDpIndex;
        this.parentId = parentId;
        this.birthTick = birthTick;
        this.entropyRegister = entropyRegister;
        this.genomeHash = genomeHash;
    }
}


