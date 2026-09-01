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

    /**
     * General-purpose data registers holding scalar molecule values. Their contents are unaffected
     * by CALL and RET, so a value written here stays visible to caller and callee alike.
     */
    DR    (   0, Config.NUM_DATA_REGISTERS,     false, CallBehavior.GLOBAL,      false, true,  "%DR",  3),
    /**
     * General-purpose location registers holding coordinate vectors. Unaffected by CALL and RET,
     * and writable only through the location write path, never through a data write.
     */
    LR    ( 256, Config.NUM_LOCATION_REGISTERS, true,  CallBehavior.GLOBAL,      false, true,  "%LR",  3),
    /**
     * Data registers scoped to a procedure body. A CALL snapshots them into the procedure frame and
     * the matching RET restores that snapshot, so writes inside a procedure never reach its caller.
     * Assembly source may name them only inside a procedure.
     */
    PDR   ( 512, Config.NUM_PDR_REGISTERS,      false, CallBehavior.STACK_SAVED, false, false, "%PDR", 4),
    /**
     * Location registers scoped to a procedure body — the coordinate-vector counterpart of
     * {@link #PDR}, with the same save-on-CALL, restore-on-RET behavior and the same restriction
     * to procedure bodies.
     */
    PLR   ( 768, Config.NUM_PLR_REGISTERS,      true,  CallBehavior.STACK_SAVED, false, false, "%PLR", 4),
    /**
     * Data registers that carry a procedure's formal parameters. Assembly source must not name
     * them: the compiler assigns one per declared data parameter and emits the code that fills
     * them, and the call site binds them to the caller's registers. Saved and restored across a
     * CALL like {@link #PDR}.
     */
    FDR   (1024, Config.NUM_FDR_REGISTERS,      false, CallBehavior.STACK_SAVED, true,  false, "%FDR", 4),
    /**
     * Location registers that carry a procedure's formal location parameters — the
     * coordinate-vector counterpart of {@link #FDR}, equally forbidden to assembly source.
     */
    FLR   (1280, Config.NUM_FLR_REGISTERS,      true,  CallBehavior.STACK_SAVED, true,  false, "%FLR", 4),
    /**
     * Data registers that keep one set of values per procedure. A CALL parks the current values
     * under the running procedure and swaps in the values the called procedure left behind, or
     * default values if it has not run before; the matching RET swaps back. Usable everywhere,
     * inside a procedure and outside one.
     */
    SDR   (1536, Config.NUM_SDR_REGISTERS,      false, CallBehavior.PERSISTENT,  false, true,  "%SDR", 4),
    /**
     * Location registers that keep one set of values per procedure — the coordinate-vector
     * counterpart of {@link #SDR}, with the same per-procedure swap on CALL and RET.
     */
    SLR   (1792, Config.NUM_SLR_REGISTERS,      true,  CallBehavior.PERSISTENT,  false, true,  "%SLR", 4);

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
     * Maps register ID → whether the bank has STACK_SAVED call behavior. Indexed by register ID.
     * Used by Organism dirty-flag tracking to avoid snapshot/restore when no STACK_SAVED register
     * has been written.
     */
    public static final boolean[] IS_STACK_SAVED_BY_ID;

    /**
     * Maps register ID → whether the bank has PERSISTENT call behavior. Indexed by register ID.
     * Used by Organism dirty-flag tracking to avoid persistent state operations when no PERSISTENT
     * register has been written.
     */
    public static final boolean[] IS_PERSISTENT_BY_ID;

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

        // Build ID-indexed lookup tables
        ID_TO_SLOT = new int[TABLE_SIZE];
        IS_LOCATION_BY_ID = new boolean[TABLE_SIZE];
        IS_STACK_SAVED_BY_ID = new boolean[TABLE_SIZE];
        IS_PERSISTENT_BY_ID = new boolean[TABLE_SIZE];
        Arrays.fill(ID_TO_SLOT, -1);
        for (RegisterBank bank : values()) {
            for (int i = 0; i < bank.count; i++) {
                int id = bank.base + i;
                ID_TO_SLOT[id] = bank.slotOffset + i;
                IS_LOCATION_BY_ID[id] = bank.isLocation;
                IS_STACK_SAVED_BY_ID[id] = bank.callBehavior == CallBehavior.STACK_SAVED;
                IS_PERSISTENT_BY_ID[id] = bank.callBehavior == CallBehavior.PERSISTENT;
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

    /**
     * Register ID of this bank's register number 0. The bank occupies the IDs {@code base} through
     * {@code base + count - 1}; the unused IDs up to the next bank's base belong to no bank and
     * every lookup rejects them.
     */
    public final int base;
    /**
     * Number of registers this bank actually provides, taken from the configuration. A bank
     * configured with 0 registers occupies no ID at all and is left out of the cached bank lists.
     */
    public final int count;
    /**
     * Whether this bank holds coordinate vectors ({@code true}) rather than scalar molecule values
     * ({@code false}). The two kinds are kept apart on write: a data write to a location register
     * and a location write to a data register both fail the instruction.
     */
    public final boolean isLocation;
    /** What a CALL and the matching RET do with the contents of this bank's registers. */
    public final CallBehavior callBehavior;
    /**
     * Whether assembly source is forbidden from naming this bank's registers. Forbidden banks hold
     * procedure parameters, which the compiler allocates and the call site binds; a program that
     * names one is rejected with a compile error.
     */
    public final boolean isForbidden;
    /** Whether this bank is available everywhere (true) or only inside procedures (false). */
    public final boolean isAlwaysAvailable;
    /**
     * Token spelling that introduces a register of this bank in assembly source, leading
     * {@code %} included. A register token is this prefix followed by the register number, for
     * example {@code %DR0}.
     */
    public final String prefix;
    /**
     * Length of {@link #prefix}, and with that the offset at which the register number starts in a
     * register token. Held separately so a token can be split without measuring the prefix.
     */
    public final int prefixLength;

    private int slotOffset;

    RegisterBank(int base, int count, boolean isLocation, CallBehavior callBehavior,
                 boolean isForbidden, boolean isAlwaysAvailable, String prefix, int prefixLength) {
        this.base = base;
        this.count = count;
        this.isLocation = isLocation;
        this.callBehavior = callBehavior;
        this.isForbidden = isForbidden;
        this.isAlwaysAvailable = isAlwaysAvailable;
        this.prefix = prefix;
        this.prefixLength = prefixLength;
    }

    /**
     * Returns the starting index of this bank's registers in the flat register array.
     * Computed during static initialization — not available in the enum constructor.
     *
     * @return the flat-array index of this bank's register number 0
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
    private static final List<RegisterBank> CACHED_PERSISTENT;

    /** Total number of register slots across all STACK_SAVED banks. */
    public static final int STACK_SAVED_SNAPSHOT_SIZE;

    /** Total number of register slots across all PERSISTENT banks. */
    public static final int PERSISTENT_SNAPSHOT_SIZE;

    static {
        CACHED_SAVED_ON_CALL = Arrays.stream(values())
                .filter(b -> b.callBehavior == CallBehavior.STACK_SAVED && b.count > 0)
                .toList();
        CACHED_PROC_SCOPED = Arrays.stream(values())
                .filter(b -> !b.isAlwaysAvailable && b.count > 0)
                .toList();
        CACHED_PERSISTENT = Arrays.stream(values())
                .filter(b -> b.callBehavior == CallBehavior.PERSISTENT && b.count > 0)
                .toList();
        STACK_SAVED_SNAPSHOT_SIZE = CACHED_SAVED_ON_CALL.stream().mapToInt(b -> b.count).sum();
        PERSISTENT_SNAPSHOT_SIZE = CACHED_PERSISTENT.stream().mapToInt(b -> b.count).sum();
    }

    /**
     * Returns all banks with {@link CallBehavior#STACK_SAVED} that have registers allocated
     * (count &gt; 0). Cached — safe to call on the hotpath.
     *
     * @return an unmodifiable list shared by all callers, in enum declaration order, which is the
     *         order the compact STACK_SAVED snapshot is laid out in
     */
    public static List<RegisterBank> allSavedOnCall() {
        return CACHED_SAVED_ON_CALL;
    }

    /**
     * Returns all banks that are only available inside procedures (not always-available)
     * and have registers allocated (count &gt; 0). Cached — safe to call on the hotpath.
     *
     * @return an unmodifiable list shared by all callers, in enum declaration order
     */
    public static List<RegisterBank> allProcScoped() {
        return CACHED_PROC_SCOPED;
    }

    /**
     * Returns all banks with {@link CallBehavior#PERSISTENT} that have registers allocated
     * (count &gt; 0). Cached — safe to call on the hotpath.
     *
     * @return an unmodifiable list shared by all callers, in enum declaration order, which is the
     *         order the compact PERSISTENT snapshot is laid out in
     */
    public static List<RegisterBank> allPersistent() {
        return CACHED_PERSISTENT;
    }
}
