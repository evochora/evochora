// src/main/java/org/evochora/Config.java
package org.evochora.runtime;

public final class Config {

    private Config() {}

    // Environment Settings (moved to JSON config loaded at runtime)

    // Simulation Settings (per-organism initial energy moved to config)
    public static final int ERROR_PENALTY_COST = 5;
    public static final int MAX_ORGANISM_ENERGY = 10000;
    public static final boolean STRICT_TYPING = true;

    // Organism Settings
    public static final int NUM_DATA_REGISTERS = 8;
    public static final int NUM_PROC_REGISTERS = 8;
    public static final int NUM_FORMAL_PARAM_REGISTERS = 8;
    public static final int DS_MAX_DEPTH = 1024;
    public static final int CALL_STACK_MAX_DEPTH = 1024;
    public static final int STACK_MAX_DEPTH = DS_MAX_DEPTH;
    public static final int NUM_DATA_POINTERS = 2;
    public static final int NUM_LOCATION_REGISTERS = 4;
    public static final int LOCATION_STACK_MAX_DEPTH = 64;


    // Server/CLI Settings (moved from Setup)
    public static final String RUNS_DIRECTORY = "runs";
    public static final long MAX_QUEUE_BYTES = 512L * 1024L * 1024L; // 512 MB

    // --- Cell Type Definition (Dynamisch konfigurierbar) ---
    public static final int VALUE_BITS = 16;
    public static final int TYPE_BITS = 4;

    public static final int TYPE_SHIFT = VALUE_BITS;
    public static final int TYPE_MASK = ((1 << TYPE_BITS) - 1) << TYPE_SHIFT;
    public static final int VALUE_MASK = (1 << VALUE_BITS) - 1;

    public static final int TYPE_CODE      = (0x00 & ((1 << TYPE_BITS) - 1)) << TYPE_SHIFT;
    public static final int TYPE_DATA      = (0x01 & ((1 << TYPE_BITS) - 1)) << TYPE_SHIFT;
    public static final int TYPE_ENERGY    = (0x02 & ((1 << TYPE_BITS) - 1)) << TYPE_SHIFT;
    public static final int TYPE_STRUCTURE = (0x03 & ((1 << TYPE_BITS) - 1)) << TYPE_SHIFT;

}