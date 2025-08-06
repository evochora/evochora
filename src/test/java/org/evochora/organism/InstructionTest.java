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
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 0, 1);
        world.setSymbol(new Symbol(Config.TYPE_DATA, new Symbol(Config.TYPE_DATA, 10).toInt()), 0, 2);
        world.setSymbol(new Symbol(Config.TYPE_CODE, NopInstruction.ID), 0, 3);

        Instruction ifi = IfiInstruction.plan(organism, world);
        organism.processTickAction(ifi, simulation);

        Assertions.assertArrayEquals(new int[]{0, 3}, organism.getIp());
    }

    @Test
    void testIfiInstruction_ConditionNotMet() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());

        world.setSymbol(new Symbol(Config.TYPE_CODE, IfiInstruction.ID_IFI), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 0, 1);
        world.setSymbol(new Symbol(Config.TYPE_DATA, new Symbol(Config.TYPE_DATA, 20).toInt()), 0, 2);
        world.setSymbol(new Symbol(Config.TYPE_CODE, NopInstruction.ID), 0, 3);

        Instruction ifi = IfiInstruction.plan(organism, world);
        organism.processTickAction(ifi, simulation);

        Assertions.assertArrayEquals(new int[]{0, 4}, organism.getIp());
    }
}