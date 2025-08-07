// src/test/java/org/evochora/organism/OrganismTest.java
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

public class OrganismTest {

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
    void testOrganismRegistersAndStack() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 42).toInt());
        organism.getDataStack().push(organism.getDr(0));

        Assertions.assertEquals(1, organism.getDataStack().size());
        Assertions.assertEquals(42, Symbol.fromInt((Integer) organism.getDataStack().peek()).toScalarValue());

        organism.setDr(1, organism.getDataStack().pop());

        Assertions.assertEquals(0, organism.getDataStack().size());
        Assertions.assertEquals(42, Symbol.fromInt((Integer) organism.getDr(1)).toScalarValue());
    }

    @Test
    void testOrganismArithmeticWithVectors() {
        int[] vec1 = {1, 2};
        int[] vec2 = {3, 4};
        organism.setDr(0, vec1);
        organism.setDr(1, vec2);

        Instruction add = new AddrInstruction(organism, 0, 1);
        add.execute(simulation);

        int[] result = (int[]) organism.getDr(0);
        Assertions.assertArrayEquals(new int[]{4, 6}, result);
    }
}