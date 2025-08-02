// src/main/java/org/evochora/Config.java
package org.evochora;

import javafx.scene.paint.Color;
import java.util.HashMap;
import java.util.Map;

public final class Config {

    private Config() {}

    // World Settings
    public static final int[] WORLD_SHAPE = {40, 30};
    public static final boolean IS_TOROIDAL = true;
    public static final int WORLD_DIMENSIONS = WORLD_SHAPE.length;

    // Simulation Settings
    public static final int INITIAL_ORGANISM_ENERGY = 2000;
    public static final int ERROR_PENALTY_COST = 5;
    public static final int MAX_ORGANISM_ENERGY = 10000;

    // Organism Settings
    public static final int NUM_DATA_REGISTERS = 16;

    // Graphics Settings
    public static final int CELL_SIZE = 22;
    public static final int HEADER_HEIGHT = 50;
    public static final int FOOTER_HEIGHT = 90;
    public static final int SCREEN_WIDTH = WORLD_SHAPE[0] * CELL_SIZE;
    public static final int SCREEN_HEIGHT = HEADER_HEIGHT + (WORLD_SHAPE[1] * CELL_SIZE) + FOOTER_HEIGHT;

    // Farbdefinitionen
    public static final Color COLOR_BG = Color.rgb(10, 10, 20);
    public static final Color COLOR_HEADER_FOOTER = Color.rgb(25, 25, 35);
    public static final Color COLOR_TEXT = Color.LIGHTGRAY;
    public static final Color COLOR_TEXT_IN_CELL = Color.BLACK;
    public static final Color COLOR_DEAD = Color.rgb(80, 80, 80);
    public static final Color COLOR_EMPTY = Color.rgb(20, 20, 30);
    public static final Color COLOR_CODE = Color.rgb(60, 80, 120);
    public static final Color COLOR_DATA = Color.rgb(100, 100, 100);
    public static final Color COLOR_STRUCTURE = Color.rgb(150, 150, 180);
    public static final Color COLOR_ENERGY = Color.rgb(200, 200, 50);

    // --- Cell Type Definition (8-Bit-System) ---
    public static final int TYPE_SHIFT = 24;
    public static final int TYPE_MASK = 0xFF << TYPE_SHIFT;
    public static final int VALUE_MASK = ~TYPE_MASK;

    // FINALE TYPEN-DEFINITION
    public static final int TYPE_CODE      = 0x00 << TYPE_SHIFT;
    public static final int TYPE_DATA      = 0x01 << TYPE_SHIFT;
    public static final int TYPE_ENERGY    = 0x02 << TYPE_SHIFT;
    public static final int TYPE_STRUCTURE = 0x03 << TYPE_SHIFT;
    // Typen 0x04 bis 0xFF sind für die Zukunft reserviert.

    // --- Opcode Definition ---
    public record Opcode(int id, String name, int baseCost, int length) {}
    public static final Map<Integer, Opcode> OPCODE_DEFINITIONS = new HashMap<>();
    public static final Map<String, Integer> NAME_TO_OPCODE = new HashMap<>();
    public static final Map<Integer, Integer> OPCODE_COSTS = new HashMap<>();
    public static final Map<Integer, Integer> OPCODE_LENGTHS = new HashMap<>();

    public static int OP_NOP, OP_SETL, OP_SETR, OP_SETV, OP_ADD, OP_SUB, OP_NAND, OP_IF,
            OP_IFLT, OP_IFGT, OP_JUMP, OP_TURN, OP_SEEK, OP_SYNC, OP_PEEK,
            OP_POKE, OP_SCAN, OP_NRG, OP_FORK, OP_DIFF;

    static {
        addOpcode(0, "NOP", 1, 1);
        addOpcode(1, "SETL", 1, 3);
        addOpcode(2, "SETR", 1, 3);
        addOpcode(3, "SETV", 1, 2 + WORLD_DIMENSIONS);
        addOpcode(4, "ADD", 1, 3);
        addOpcode(5, "SUB", 1, 3);
        addOpcode(6, "NAND", 1, 3);
        addOpcode(7, "IF", 1, 3);
        addOpcode(8, "IFLT", 1, 3);
        addOpcode(9, "IFGT", 1, 3);
        addOpcode(10, "JUMP", 1, 2);
        addOpcode(11, "TURN", 1, 2);
        addOpcode(12, "SEEK", 1, 2);
        addOpcode(13, "SYNC", 1, 1);
        addOpcode(14, "PEEK", 1, 3);
        addOpcode(15, "POKE", 1, 3);
        addOpcode(16, "SCAN", 0, 3);
        addOpcode(17, "NRG", 0, 2);
        addOpcode(18, "FORK", 10, 4);
        addOpcode(19, "DIFF", 1, 2);
    }

    private static void addOpcode(int value, String name, int cost, int length) {
        // FINALE LOGIK: 0 ist NOP und hat Typ CODE. Alle anderen Werte werden mit TYPE_CODE kombiniert.
        int finalId = (value == 0) ? 0 : TYPE_CODE | value;
        Opcode opcode = new Opcode(finalId, name, cost, length);

        OPCODE_DEFINITIONS.put(finalId, opcode);
        NAME_TO_OPCODE.put(name, finalId);
        OPCODE_COSTS.put(finalId, cost);
        OPCODE_LENGTHS.put(finalId, length);

        try {
            Config.class.getField("OP_" + name).setInt(null, finalId);
        } catch (Exception e) { e.printStackTrace(); }
    }
}