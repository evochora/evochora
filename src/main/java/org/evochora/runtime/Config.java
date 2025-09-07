// src/main/java/org/evochora/Config.java
package org.evochora.runtime;

/**
 * Provides centralized configuration settings for the Evochora simulation environment.
 * This final class contains static constants that define various parameters for the simulation,
 * organisms, and server/CLI operations. It is not meant to be instantiated.
 */
public final class Config {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private Config() {}

    // Environment Settings are now loaded from a JSON config file at runtime.

    /**
     * The energy cost deducted from an organism when an error occurs during execution.
     */
    public static final int ERROR_PENALTY_COST = 5;

    /**
     * The maximum energy an organism can accumulate.
     */
    public static final int MAX_ORGANISM_ENERGY = 100000;

    /**
     * If true, enforces strict type checking during operations.
     */
    public static final boolean STRICT_TYPING = true;

    /**
     * The number of general-purpose data registers available to an organism.
     */
    public static final int NUM_DATA_REGISTERS = 8;

    /**
     * The number of procedure registers available for storing code addresses.
     */
    public static final int NUM_PROC_REGISTERS = 8;

    /**
     * The number of registers used for passing formal parameters to procedures.
     */
    public static final int NUM_FORMAL_PARAM_REGISTERS = 8;

    /**
     * The maximum depth of the data stack.
     */
    public static final int DS_MAX_DEPTH = 1024;

    /**
     * The maximum depth of the call stack, preventing infinite recursion.
     */
    public static final int CALL_STACK_MAX_DEPTH = 1024;

    /**
     * The maximum depth of the stack, aliased to DS_MAX_DEPTH.
     */
    public static final int STACK_MAX_DEPTH = DS_MAX_DEPTH;

    /**
     * The number of data pointers available to an organism.
     */
    public static final int NUM_DATA_POINTERS = 2;

    /**
     * The number of location registers for storing coordinates.
     */
    public static final int NUM_LOCATION_REGISTERS = 4;

    /**
     * The maximum depth of the location stack.
     */
    public static final int LOCATION_STACK_MAX_DEPTH = 64;


    /**
     * The directory where simulation runs and their outputs are stored.
     */
    public static final String RUNS_DIRECTORY = "runs";

    /**
     * The number of bits used to represent the value part of a cell.
     */
    public static final int VALUE_BITS = 16;

    /**
     * The number of bits used to represent the type part of a cell.
     */
    public static final int TYPE_BITS = 4;

    /**
     * The bit offset for the type information within a cell's integer representation.
     */
    public static final int TYPE_SHIFT = VALUE_BITS;

    /**
     * A bitmask to extract the type information from a cell's integer representation.
     */
    public static final int TYPE_MASK = ((1 << TYPE_BITS) - 1) << TYPE_SHIFT;

    /**
     * A bitmask to extract the value from a cell's integer representation.
     */
    public static final int VALUE_MASK = (1 << VALUE_BITS) - 1;

    /**
     * The type code for a cell containing executable code.
     */
    public static final int TYPE_CODE      = (0x00 & ((1 << TYPE_BITS) - 1)) << TYPE_SHIFT;

    /**
     * The type code for a cell containing data.
     */
    public static final int TYPE_DATA      = (0x01 & ((1 << TYPE_BITS) - 1)) << TYPE_SHIFT;

    /**
     * The type code for a cell representing an energy source.
     */
    public static final int TYPE_ENERGY    = (0x02 & ((1 << TYPE_BITS) - 1)) << TYPE_SHIFT;

    /**
     * The type code for a cell representing a structural component.
     */
    public static final int TYPE_STRUCTURE = (0x03 & ((1 << TYPE_BITS) - 1)) << TYPE_SHIFT;

    /**
     * Enables sparse cell tracking for performance optimization in large worlds.
     * When enabled, the Environment maintains a set of occupied cells to avoid
     * iterating through all cells during serialization.
     * Needs more memory, depending on world size and cell occupancy, critical for large world:
     * - 10.000x10.000 with 50% non-empty: +3.6 GB.
     * - 1000x1000x1000 with 50% non-empty: +38GB
     *  Also needs more CPU cycles per setMolecule call.
     * But it is much faster in large worlds, esp, if they are rather not dense populated, because they need less memory and CPU cycles to serialize.
     */
    public static final boolean ENABLE_SPARSE_CELL_TRACKING = true;
}