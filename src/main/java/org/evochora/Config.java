// src/main/java/org/evochora/Config.java
package org.evochora;

import java.util.HashMap;
import java.util.Map;

public class Config {
    // World Settings
    public static final int[] WORLD_SHAPE = {80, 60};
    public static final boolean IS_TOROIDAL = true;

    // Organism Settings
    public static final int NUM_DATA_REGISTERS = 8;
    public static final int INITIAL_ORGANISM_ENERGY = 500;

    // Graphics Settings
    public static final int CELL_SIZE = 10;
    public static final int SIDEBAR_WIDTH = 250;
    public static final int SCREEN_WIDTH = (WORLD_SHAPE[0] * CELL_SIZE) + SIDEBAR_WIDTH;
    public static final int SCREEN_HEIGHT = WORLD_SHAPE[1] * CELL_SIZE;

    // --- Opcode Definition ---
    public static class Opcode {
        public final int id;
        public final String name;
        public final int baseCost;
        public final int length;

        public Opcode(int id, String name, int baseCost, int length) {
            this.id = id;
            this.name = name;
            this.baseCost = baseCost;
            this.length = length;
        }
    }

    public static final Map<Integer, Opcode> OPCODE_DEFINITIONS = new HashMap<>();
    public static final Map<Integer, Integer> OPCODE_LENGTHS = new HashMap<>();

    // Opcodes
    public static final int OP_NOP = 0;
    public static final int OP_SETL = 32;

    static {
        addOpcode(OP_NOP, "NOP", 1, 1);
        addOpcode(OP_SETL, "SETL", 1, 3);
    }

    private static void addOpcode(int id, String name, int cost, int length) {
        Opcode opcode = new Opcode(id, name, cost, length);
        OPCODE_DEFINITIONS.put(id, opcode);
        OPCODE_LENGTHS.put(id, length);
    }
}