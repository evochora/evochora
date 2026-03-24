package org.evochora.runtime.isa;

import org.evochora.runtime.Config;

import java.util.Arrays;
import java.util.List;

/**
 * Single source of truth for all register bank metadata. Each entry defines the base ID,
 * register count, type (data/location), call behavior, and prefix for one register bank.
 *
 * <p>Adding a new bank requires only a new enum entry here and a {@code Config.NUM_*_REGISTERS}
 * constant. All dispatch, validation, serialization, and save/restore logic works generically
 * through this enum.
 *
 * <p>Base IDs use 256-spacing for compact lookup tables (total table size: 2048 entries, ~8KB).
 */
public enum RegisterBank {

    DR    (   0, Config.NUM_DATA_REGISTERS,     false, CallBehavior.GLOBAL,      false, "%DR",  3),
    LR    ( 256, Config.NUM_LOCATION_REGISTERS, true,  CallBehavior.GLOBAL,      false, "%LR",  3),
    PDR   ( 512, Config.NUM_PDR_REGISTERS,      false, CallBehavior.STACK_SAVED, false, "%PDR", 4),
    PLR   ( 768, 0,                             true,  CallBehavior.STACK_SAVED, false, "%PLR", 4),
    FDR   (1024, Config.NUM_FDR_REGISTERS,      false, CallBehavior.STACK_SAVED, true,  "%FDR", 4),
    FLR   (1280, 0,                             true,  CallBehavior.STACK_SAVED, true,  "%FLR", 4),
    SDR   (1536, 0,                             false, CallBehavior.PERSISTENT,  false, "%SDR", 4),
    SLR   (1792, 0,                             true,  CallBehavior.PERSISTENT,  false, "%SLR", 4);

    /** Behavior of this bank's registers when a CALL or RET instruction executes. */
    public enum CallBehavior {
        /** Global registers — not affected by CALL/RET. */
        GLOBAL,
        /** Snapshot saved to ProcFrame on CALL, restored on RET. */
        STACK_SAVED,
        /** Swapped in/out of a per-procedure backing store on CALL/RET. */
        PERSISTENT
    }

    /** Size of all ID-indexed lookup tables. */
    public static final int TABLE_SIZE = 2048;

    /** Total number of register slots across all banks with count > 0. */
    public static final int TOTAL_REGISTER_COUNT;

    /**
     * Maps register ID → flat array slot index. Sentinel -1 for IDs not in any bank.
     * Size: {@link #TABLE_SIZE}. Indexed by register ID.
     */
    public static final int[] ID_TO_SLOT;

    /**
     * Maps register ID → whether the bank is a location bank. Indexed by register ID (NOT slot).
     * Deliberate performance duplication of {@code forId(id).isLocation} — the instruction
     * execution hotpath needs a single array lookup, not a {@code forId()} call + field access.
     */
    public static final boolean[] IS_LOCATION_BY_ID;

    /**
     * Maps flat array slot → RegisterBank. Size: {@link #TOTAL_REGISTER_COUNT}.
     * Used by {@link #forId(int)} to resolve bank from slot without a separate 2048-entry table.
     */
    public static final RegisterBank[] SLOT_TO_BANK;

    static {
        // Compute slot offsets and total count
        int offset = 0;
        for (RegisterBank bank : values()) {
            bank.slotOffset = offset;
            offset += bank.count;
        }
        TOTAL_REGISTER_COUNT = offset;

        // Build ID_TO_SLOT and IS_LOCATION_BY_ID tables
        ID_TO_SLOT = new int[TABLE_SIZE];
        IS_LOCATION_BY_ID = new boolean[TABLE_SIZE];
        Arrays.fill(ID_TO_SLOT, -1);
        for (RegisterBank bank : values()) {
            for (int i = 0; i < bank.count; i++) {
                ID_TO_SLOT[bank.base + i] = bank.slotOffset + i;
                IS_LOCATION_BY_ID[bank.base + i] = bank.isLocation;
            }
        }

        // Build SLOT_TO_BANK table
        SLOT_TO_BANK = new RegisterBank[TOTAL_REGISTER_COUNT];
        for (RegisterBank bank : values()) {
            for (int i = 0; i < bank.count; i++) {
                SLOT_TO_BANK[bank.slotOffset + i] = bank;
            }
        }
    }

    public final int base;
    public final int count;
    public final boolean isLocation;
    public final CallBehavior callBehavior;
    public final boolean isForbidden;
    public final String prefix;
    public final int prefixLength;

    private int slotOffset;

    RegisterBank(int base, int count, boolean isLocation, CallBehavior callBehavior,
                 boolean isForbidden, String prefix, int prefixLength) {
        this.base = base;
        this.count = count;
        this.isLocation = isLocation;
        this.callBehavior = callBehavior;
        this.isForbidden = isForbidden;
        this.prefix = prefix;
        this.prefixLength = prefixLength;
    }

    /**
     * Returns the starting index of this bank's registers in the flat register array.
     * Computed during static initialization — not available in the enum constructor.
     */
    public int slotOffset() {
        return slotOffset;
    }

    /**
     * Returns the bank for a register ID, or {@code null} if the ID is not in any bank.
     *
     * @param id the full register ID
     * @return the bank, or {@code null}
     */
    public static RegisterBank forId(int id) {
        if (id < 0 || id >= TABLE_SIZE) {
            return null;
        }
        int slot = ID_TO_SLOT[id];
        return slot == -1 ? null : SLOT_TO_BANK[slot];
    }

    /**
     * Checks whether a register ID belongs to a location register bank.
     * Uses the {@link #IS_LOCATION_BY_ID} table for O(1) performance on the hotpath.
     *
     * @param id the full register ID
     * @return {@code true} if the ID is in a location bank
     */
    public static boolean isLocationBank(int id) {
        return id >= 0 && id < TABLE_SIZE && IS_LOCATION_BY_ID[id];
    }

    private static final List<RegisterBank> CACHED_SAVED_ON_CALL;
    private static final List<RegisterBank> CACHED_PROC_SCOPED;

    static {
        // (appended to existing static init block content via field initializer)
        CACHED_SAVED_ON_CALL = Arrays.stream(values())
                .filter(b -> b.callBehavior == CallBehavior.STACK_SAVED && b.count > 0)
                .toList();
        CACHED_PROC_SCOPED = Arrays.stream(values())
                .filter(b -> b.callBehavior != CallBehavior.GLOBAL && b.count > 0)
                .toList();
    }

    /**
     * Returns all banks with {@link CallBehavior#STACK_SAVED} that have registers allocated
     * (count &gt; 0). Cached — safe to call on the hotpath.
     */
    public static List<RegisterBank> allSavedOnCall() {
        return CACHED_SAVED_ON_CALL;
    }

    /**
     * Returns all banks that are procedure-scoped (not global) and have registers allocated
     * (count &gt; 0). Cached — safe to call on the hotpath.
     */
    public static List<RegisterBank> allProcScoped() {
        return CACHED_PROC_SCOPED;
    }
}
