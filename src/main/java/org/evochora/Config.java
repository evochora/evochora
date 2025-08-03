// src/main/java/org/evochora/Config.java
package org.evochora;

import javafx.scene.paint.Color;
import org.evochora.organism.Action;
import org.evochora.organism.Organism;
import org.evochora.organism.*;
import org.evochora.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

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
    public static final boolean STRICT_TYPING = true;

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
    public static final Color COLOR_DEAD = Color.rgb(80, 80, 80);

    // Hintergrundfarben für Zelltypen
    public static final Color COLOR_EMPTY_BG = Color.rgb(20, 20, 30);
    public static final Color COLOR_CODE_BG = Color.rgb(60, 80, 120);
    public static final Color COLOR_DATA_BG = Color.rgb(50, 50, 60);
    public static final Color COLOR_STRUCTURE_BG = Color.rgb(255, 120, 120);
    public static final Color COLOR_ENERGY_BG = Color.rgb(255, 230, 100);

    // Textfarben für Zelltypen
    public static final Color COLOR_CODE_TEXT = Color.WHITE;
    public static final Color COLOR_DATA_TEXT = Color.WHITE;
    public static final Color COLOR_STRUCTURE_TEXT = Color.rgb(50, 50, 50);
    public static final Color COLOR_ENERGY_TEXT = Color.rgb(50, 50, 50);

    // --- Cell Type Definition (8-Bit-System) ---
    public static final int TYPE_SHIFT = 24;
    public static final int TYPE_MASK = 0xFF << TYPE_SHIFT;
    public static final int VALUE_MASK = ~TYPE_MASK;

    public static final int TYPE_CODE      = 0x00 << TYPE_SHIFT;
    public static final int TYPE_DATA      = 0x01 << TYPE_SHIFT;
    public static final int TYPE_ENERGY    = 0x02 << TYPE_SHIFT;
    public static final int TYPE_STRUCTURE = 0x03 << TYPE_SHIFT;

    // --- Opcode Definition ---
    public record Opcode(int id, String name, int baseCost, int length) {}
    public static final Map<Integer, Opcode> OPCODE_DEFINITIONS = new HashMap<>();
    public static final Map<String, Integer> NAME_TO_OPCODE = new HashMap<>();
    public static final Map<Integer, Integer> OPCODE_COSTS = new HashMap<>();
    public static final Map<Integer, Integer> OPCODE_LENGTHS = new HashMap<>();

    public static final Map<Integer, BiFunction<Organism, World, Action>> OPCODE_TO_ACTION_PLANNER = new HashMap<>();

    @FunctionalInterface
    public interface AssemblerPlanner {
        List<Integer> apply(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap);
    }
    public static final Map<Integer, AssemblerPlanner> OPCODE_TO_ASSEMBLER = new HashMap<>();

    public static int OP_NOP, OP_SETL, OP_SETR, OP_SETV, OP_ADD, OP_SUB, OP_NAND, OP_IF,
            OP_IFLT, OP_IFGT, OP_JUMP, OP_TURN, OP_SEEK, OP_SYNC, OP_PEEK,
            OP_POKE, OP_SCAN, OP_NRG, OP_FORK, OP_DIFF;

    static {
        addOpcode(0, "NOP", 1, 1, NopAction::plan, NopAction::assemble);
        addOpcode(1, "SETL", 1, 3, SetlAction::plan, SetlAction::assemble);
        addOpcode(2, "SETR", 1, 3, SetrAction::plan, SetrAction::assemble);
        addOpcode(3, "SETV", 1, 2 + WORLD_DIMENSIONS, SetvAction::plan, SetvAction::assemble);
        addOpcode(4, "ADD", 1, 3, AddAction::plan, AddAction::assemble);
        addOpcode(5, "SUB", 1, 3, SubAction::plan, SubAction::assemble);
        addOpcode(6, "NAND", 1, 3, NandAction::plan, NandAction::assemble);
        addOpcode(7, "IF", 1, 3, IfAction::plan, IfAction::assemble);
        addOpcode(8, "IFLT", 1, 3, IfAction::plan, IfAction::assemble);
        addOpcode(9, "IFGT", 1, 3, IfAction::plan, IfAction::assemble);
        addOpcode(10, "JUMP", 1, 2, JumpAction::plan, JumpAction::assemble);
        addOpcode(11, "TURN", 1, 2, TurnAction::plan, TurnAction::assemble);
        addOpcode(12, "SEEK", 1, 2, SeekAction::plan, SeekAction::assemble);
        addOpcode(13, "SYNC", 1, 1, SyncAction::plan, SyncAction::assemble);
        addOpcode(14, "PEEK", 1, 3, PeekAction::plan, PeekAction::assemble);
        addOpcode(15, "POKE", 1, 3, PokeAction::plan, PokeAction::assemble);
        addOpcode(16, "SCAN", 0, 3, ScanAction::plan, ScanAction::assemble);
        addOpcode(17, "NRG", 0, 2, NrgAction::plan, NrgAction::assemble);
        addOpcode(18, "FORK", 10, 4, ForkAction::plan, ForkAction::assemble);
        addOpcode(19, "DIFF", 1, 2, DiffAction::plan, DiffAction::assemble);
    }

    private static void addOpcode(int value, String name, int cost, int length,
                                  BiFunction<Organism, World, Action> planner,
                                  AssemblerPlanner assembler) {
        int finalId = (value == 0) ? 0 : TYPE_CODE | value;
        Opcode opcode = new Opcode(finalId, name, cost, length);

        OPCODE_DEFINITIONS.put(finalId, opcode);
        NAME_TO_OPCODE.put(name, finalId);
        OPCODE_COSTS.put(finalId, cost);
        OPCODE_LENGTHS.put(finalId, length);
        OPCODE_TO_ACTION_PLANNER.put(finalId, planner);
        OPCODE_TO_ASSEMBLER.put(finalId, assembler);

        try {
            Config.class.getField("OP_" + name).setInt(null, finalId);
        } catch (Exception e) {
            System.err.println("Fehler beim Setzen der Opcode-Konstante für " + name);
            e.printStackTrace();
        }
    }
}