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

    private static final int TABLE_SIZE = 2048;
    private static final int[] ID_TO_BANK_ORDINAL;

    static {
        ID_TO_BANK_ORDINAL = new int[TABLE_SIZE];
        Arrays.fill(ID_TO_BANK_ORDINAL, -1);
        for (RegisterBank bank : values()) {
            for (int i = 0; i < bank.count; i++) {
                ID_TO_BANK_ORDINAL[bank.base + i] = bank.ordinal();
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
     * Returns the bank for a register ID, or {@code null} if the ID is not in any bank.
     *
     * @param id the full register ID
     * @return the bank, or {@code null}
     */
    public static RegisterBank forId(int id) {
        if (id < 0 || id >= TABLE_SIZE) {
            return null;
        }
        int ordinal = ID_TO_BANK_ORDINAL[id];
        return ordinal == -1 ? null : values()[ordinal];
    }

    /**
     * Checks whether a register ID belongs to a location register bank.
     *
     * @param id the full register ID
     * @return {@code true} if the ID is in a location bank
     */
    public static boolean isLocationBank(int id) {
        RegisterBank bank = forId(id);
        return bank != null && bank.isLocation;
    }

    /**
     * Returns all banks with {@link CallBehavior#STACK_SAVED} that have registers allocated
     * (count &gt; 0).
     */
    public static List<RegisterBank> allSavedOnCall() {
        return Arrays.stream(values())
                .filter(b -> b.callBehavior == CallBehavior.STACK_SAVED && b.count > 0)
                .toList();
    }

    /**
     * Returns all banks that are procedure-scoped (not global) and have registers allocated
     * (count &gt; 0).
     */
    public static List<RegisterBank> allProcScoped() {
        return Arrays.stream(values())
                .filter(b -> b.callBehavior != CallBehavior.GLOBAL && b.count > 0)
                .toList();
    }
}
