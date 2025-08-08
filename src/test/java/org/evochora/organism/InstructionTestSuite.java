package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.organism.instructions.*;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

public class InstructionTestSuite {

    private World world;
    private Simulation simulation;
    private Organism organism;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setup() {
        world = new World(new int[]{20, 20}, true);
        simulation = new Simulation(world);
        organism = Organism.create(simulation, new int[]{5, 5}, 2000, simulation.getLogger());
        simulation.addOrganism(organism);
    }

    private int getOpcode(String name) {
        return Instruction.getInstructionIdByName(name);
    }

    // === Daten & Speicher ===

    @Test
    void testSETI() {
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("SETI")), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5); // %DR0
        world.setSymbol(new Symbol(Config.TYPE_DATA, 123), 7, 5);  // DATA:123
        simulation.tick();
        Assertions.assertEquals(123, Symbol.fromInt((Integer) organism.getDr(0)).value());
    }

    @Test
    void testSETR() {
        organism.setDr(1, new Symbol(Config.TYPE_DATA, 456).toInt());
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("SETR")), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 7, 5);
        simulation.tick();
        Assertions.assertEquals(456, Symbol.fromInt((Integer) organism.getDr(0)).value());
    }

    @Test
    void testSETV() {
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("SETV")), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 10), 7, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, -20), 8, 5);
        simulation.tick();
        Assertions.assertArrayEquals(new int[]{10, -20}, (int[]) organism.getDr(0));
    }

    @Test
    void testPUSH_and_POP() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 789).toInt());
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("PUSH")), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        simulation.tick();
        Assertions.assertEquals(1, organism.getDataStack().size());

        organism.setDr(0, 0); // Register leeren
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("POP")), 7, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 8, 5);
        simulation.tick();

        Assertions.assertEquals(0, organism.getDataStack().size());
        Assertions.assertEquals(789, Symbol.fromInt((Integer) organism.getDr(0)).value());
    }

    @Test
    void testPUSI() {
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("PUSI")), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 999), 6, 5);
        simulation.tick();
        Assertions.assertEquals(1, organism.getDataStack().size());
        Assertions.assertEquals(999, Symbol.fromInt((Integer) organism.getDataStack().peek()).value());
    }

    // === Arithmetik & Logik ===

    @Test
    void testADDR_Scalar() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());
        organism.setDr(1, new Symbol(Config.TYPE_DATA, 22).toInt());
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("ADDR")), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 7, 5);
        simulation.tick();
        Assertions.assertEquals(32, Symbol.fromInt((Integer) organism.getDr(0)).value());
    }

    @Test
    void testMULR() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());
        organism.setDr(1, new Symbol(Config.TYPE_DATA, 5).toInt());
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("MULR")), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 7, 5);
        simulation.tick();
        Assertions.assertEquals(50, Symbol.fromInt((Integer) organism.getDr(0)).value());
    }

    @Test
    void testDIVR_byZero() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());
        organism.setDr(1, new Symbol(Config.TYPE_DATA, 0).toInt());
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("DIVR")), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 7, 5);
        simulation.tick();
        Assertions.assertTrue(organism.isInstructionFailed());
        Assertions.assertTrue(organism.getFailureReason().contains("Division durch Null"));
    }

    @Test
    void testMODR() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());
        organism.setDr(1, new Symbol(Config.TYPE_DATA, 3).toInt());
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("MODR")), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 7, 5);
        simulation.tick();
        Assertions.assertEquals(1, Symbol.fromInt((Integer) organism.getDr(0)).value());
    }

    // === Bitweise Operationen ===

    @Test
    void testNOT() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 5).toInt()); // 0101
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("NOT")), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        simulation.tick();
        Assertions.assertEquals(-6, Symbol.fromInt((Integer) organism.getDr(0)).toScalarValue());
    }

    @Test
    void testSHLI() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 5).toInt()); // 0101
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("SHLI")), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 2), 7, 5); // um 2 shiften
        simulation.tick();
        Assertions.assertEquals(20, Symbol.fromInt((Integer) organism.getDr(0)).value()); // 5 << 2 = 20
    }

    // === Kontrollfluss und Bedingungen ===

    @Test
    void testIFR_ConditionFalse_skipsNext() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());
        organism.setDr(1, new Symbol(Config.TYPE_DATA, 20).toInt());

        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("IFR")), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 7, 5);

        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("NOP")), 8, 5); // Wird übersprungen
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("NOP")), 9, 5);

        simulation.tick();
        Assertions.assertArrayEquals(new int[]{9, 5}, organism.getIp());
    }

    // === Welt & Zustand ===

    @Test
    void testRAND() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 100).toInt());
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("RAND")), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        simulation.tick();
        int result = Symbol.fromInt((Integer) organism.getDr(0)).value();
        Assertions.assertTrue(result >= 0 && result < 100);
    }

    @Test
    void testSEKI() {
        // DP startet bei 5|5
        world.setSymbol(new Symbol(Config.TYPE_CODE, getOpcode("SEKI")), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, -1), 7, 5);
        simulation.tick();
        Assertions.assertArrayEquals(new int[]{6, 4}, organism.getDp());
    }
}