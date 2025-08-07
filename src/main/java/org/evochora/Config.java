// src/main/java/org/evochora/Config.java
package org.evochora;

import javafx.scene.paint.Color;

public final class Config {

    private Config() {}

    // World Settings
    public static final int[] WORLD_SHAPE = {80, 40};
    public static final boolean IS_TOROIDAL = true;
    public static final int WORLD_DIMENSIONS = WORLD_SHAPE.length;

    // Simulation Settings
    public static final int INITIAL_ORGANISM_ENERGY = 2000;
    public static final int ERROR_PENALTY_COST = 5;
    public static final int MAX_ORGANISM_ENERGY = 10000;
    public static final boolean STRICT_TYPING = true;

    // Organism Settings
    public static final int NUM_DATA_REGISTERS = 8; // Geändert von 16 auf 8
    public static final int STACK_MAX_DEPTH = 1024; // Neu: Maximaltiefe des Stacks als Sicherheitsnetz

    // Graphics Settings
    public static final int CELL_SIZE = 22;
    public static final int HEADER_HEIGHT = 50;
    public static final int FOOTER_HEIGHT = 90;

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

    // --- Cell Type Definition (Dynamisch konfigurierbar) ---
    public static final int VALUE_BITS = 12;
    public static final int TYPE_BITS = 4;

    public static final int TYPE_SHIFT = VALUE_BITS;
    public static final int TYPE_MASK = ((1 << TYPE_BITS) - 1) << TYPE_SHIFT;
    public static final int VALUE_MASK = (1 << VALUE_BITS) - 1;

    public static final int TYPE_CODE      = (0x00 & ((1 << TYPE_BITS) - 1)) << TYPE_SHIFT;
    public static final int TYPE_DATA      = (0x01 & ((1 << TYPE_BITS) - 1)) << TYPE_SHIFT;
    public static final int TYPE_ENERGY    = (0x02 & ((1 << TYPE_BITS) - 1)) << TYPE_SHIFT;
    public static final int TYPE_STRUCTURE = (0x03 & ((1 << TYPE_BITS) - 1)) << TYPE_SHIFT;

    // GEÄNDERT: Alle Opcode-bezogenen Maps, Opcode Record, OP_ Konstanten, static {} Block und addOpcode Methode entfernt.
    // Die Instruktionsdetails werden nun direkt von der Instruction-Klasse (Instruction.java) bezogen.

    /**
     * Prüft, ob ein gegebener Opcode (per seiner vollen ID) Argumente als Vektorkomponenten erwartet.
     * DIESE METHODE WIRD NUN AUS Config ENTFERNT und ihre Logik wird direkt in AssemblyProgram
     * über Instruction.getArgumentType() oder ähnliche Mechanismen abgebildet.
     * Diese Methode wird als Teil dieses Refactorings entfernt.
     */
    // public static boolean expectsVectorArguments(int opcodeFullValue) { ... }
}