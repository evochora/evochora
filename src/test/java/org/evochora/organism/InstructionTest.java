// src/test/java/org/evochora/organism/InstructionTest.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Map;

public class InstructionTest {

    private World world;
    private Simulation simulation;
    private Organism organism;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setup() {
        Config.WORLD_SHAPE[0] = 10;
        Config.WORLD_SHAPE[1] = 10;
        world = new World(Config.WORLD_SHAPE, true);
        simulation = new Simulation(world);
        organism = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
    }

    @Test
    void testIfiInstruction_ConditionMet() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());

        world.setSymbol(new Symbol(Config.TYPE_CODE, IfiInstruction.ID_IFI), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, new Symbol(Config.TYPE_DATA, 10).toInt()), 2, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, NopInstruction.ID), 3, 0);

        Instruction ifi = IfiInstruction.plan(organism, world);
        organism.processTickAction(ifi, simulation);

        Assertions.assertArrayEquals(new int[]{3, 0}, organism.getIp());
    }

    @Test
    void testIfiInstruction_ConditionNotMet() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());

        world.setSymbol(new Symbol(Config.TYPE_CODE, IfiInstruction.ID_IFI), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, new Symbol(Config.TYPE_DATA, 20).toInt()), 2, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, NopInstruction.ID), 3, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, NopInstruction.ID), 4, 0);

        Instruction ifi = IfiInstruction.plan(organism, world);
        organism.processTickAction(ifi, simulation);

        Assertions.assertArrayEquals(new int[]{4, 0}, organism.getIp());
    }

    @Test
    void testJmprInstruction_JumpWithRegister() {
        // Platziere JMPR an Koordinate (0,0)
        world.setSymbol(new Symbol(Config.TYPE_CODE, JmprInstruction.ID), 0, 0);
        // Argument ist Register 0
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);

        // NEU: Setze einen Vektor im Register 0, der eine ABSOLUTE Zielkoordinate darstellt.
        int[] targetCoordinate = {5, 0};
        organism.setDr(0, targetCoordinate);

        // Führe den JMPR Befehl aus
        Instruction jmpr = JmprInstruction.plan(organism, world);
        organism.processTickAction(jmpr, simulation);

        // Erwartete Position nach dem Sprung ist die absolute Koordinate aus dem Register.
        Assertions.assertArrayEquals(new int[]{5, 0}, organism.getIp());
    }

    @Test
    void testAddrInstruction_ScalarAddition() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());
        organism.setDr(1, new Symbol(Config.TYPE_DATA, 20).toInt());

        world.setSymbol(new Symbol(Config.TYPE_CODE, AddrInstruction.ID), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 2, 0);

        Instruction addr = AddrInstruction.plan(organism, world);
        organism.processTickAction(addr, simulation);

        Assertions.assertEquals(30, Symbol.fromInt((Integer) organism.getDr(0)).toScalarValue());
    }

    @Test
    void testSubrInstruction_ScalarSubtraction() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 30).toInt());
        organism.setDr(1, new Symbol(Config.TYPE_DATA, 15).toInt());

        world.setSymbol(new Symbol(Config.TYPE_CODE, SubrInstruction.ID), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 2, 0);

        Instruction subr = SubrInstruction.plan(organism, world);
        organism.processTickAction(subr, simulation);

        Assertions.assertEquals(15, Symbol.fromInt((Integer) organism.getDr(0)).toScalarValue());
    }

    @Test
    void testNadrInstruction_BitwiseNand() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 5).toInt()); // 0101
        organism.setDr(1, new Symbol(Config.TYPE_DATA, 3).toInt()); // 0011

        world.setSymbol(new Symbol(Config.TYPE_CODE, NadrInstruction.ID), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 2, 0);

        Instruction nadr = NadrInstruction.plan(organism, world);
        organism.processTickAction(nadr, simulation);

        // (5 & 3) = 1 (0001) -> ~(1) = -2 (1110)
        Assertions.assertEquals(-2, Symbol.fromInt((Integer) organism.getDr(0)).toScalarValue());
    }

    @Test
    void testAddiInstruction() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());

        world.setSymbol(new Symbol(Config.TYPE_CODE, AddiInstruction.ID), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, new Symbol(Config.TYPE_DATA, 5).toInt()), 2, 0);

        Instruction addi = AddiInstruction.plan(organism, world);
        organism.processTickAction(addi, simulation);

        Assertions.assertEquals(15, Symbol.fromInt((Integer) organism.getDr(0)).toScalarValue());
    }

    @Test
    void testSubiInstruction() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());

        world.setSymbol(new Symbol(Config.TYPE_CODE, SubiInstruction.ID), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, new Symbol(Config.TYPE_DATA, 5).toInt()), 2, 0);

        Instruction subi = SubiInstruction.plan(organism, world);
        organism.processTickAction(subi, simulation);

        Assertions.assertEquals(5, Symbol.fromInt((Integer) organism.getDr(0)).toScalarValue());
    }

    @Test
    void testNadiInstruction() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 5).toInt());

        world.setSymbol(new Symbol(Config.TYPE_CODE, NadiInstruction.ID), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, new Symbol(Config.TYPE_DATA, 3).toInt()), 2, 0);

        Instruction nadi = NadiInstruction.plan(organism, world);
        organism.processTickAction(nadi, simulation);

        Assertions.assertEquals(-2, Symbol.fromInt((Integer) organism.getDr(0)).toScalarValue());
    }

    @Test
    void testIftrInstruction_ConditionMet() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());
        organism.setDr(1, new Symbol(Config.TYPE_DATA, 20).toInt());

        world.setSymbol(new Symbol(Config.TYPE_CODE, IftrInstruction.ID), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 2, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, NopInstruction.ID), 3, 0);

        Instruction iftr = IftrInstruction.plan(organism, world);
        organism.processTickAction(iftr, simulation);

        Assertions.assertArrayEquals(new int[]{3, 0}, organism.getIp());
    }

    @Test
    void testIftrInstruction_ConditionNotMet() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());
        organism.setDr(1, new Symbol(Config.TYPE_ENERGY, 20).toInt());

        world.setSymbol(new Symbol(Config.TYPE_CODE, IftrInstruction.ID), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 2, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, NopInstruction.ID), 3, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, NopInstruction.ID), 4, 0);

        Instruction iftr = IftrInstruction.plan(organism, world);
        organism.processTickAction(iftr, simulation);

        Assertions.assertArrayEquals(new int[]{4, 0}, organism.getIp());
    }
}