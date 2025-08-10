package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.organism.instructions.SetrInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SetrPrDrMixedTest {

    private World world;
    private Simulation simulation;
    private Organism organism;

    private static final int SETR_ID = 2 | Config.TYPE_CODE;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setup() {
        world = new World(Config.WORLD_SHAPE, true);
        simulation = new Simulation(world);
        organism = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
    }

    private Object dataVal(int v) { return new Symbol(Config.TYPE_DATA, v).toInt(); }
    private Object vecVal(int x, int y) { return new int[]{x, y}; }

    @Test
    void setr_dr_to_pr_and_back_scalar() {
        // DR0 := DATA:7
        assertTrue(organism.setDr(0, dataVal(7)));

        // PR0 := DR0
        Instruction i1 = new SetrInstruction(organism, 1000, 0, SETR_ID); // 1000 encodes PR0 as dest
        i1.execute(simulation);

        // DR1 := PR0
        Instruction i2 = new SetrInstruction(organism, 1, 1000, SETR_ID);
        i2.execute(simulation);

        Object dr1 = organism.getDr(1);
        assertTrue(dr1 instanceof Integer);
        assertEquals(7, Symbol.fromInt((Integer) dr1).toScalarValue());
        assertEquals(Config.TYPE_DATA, Symbol.fromInt((Integer) dr1).type());
    }

    @Test
    void setr_dr_to_pr_and_back_vector() {
        assertTrue(organism.setDr(2, vecVal(3, 4)));

        // PR1 := DR2
        Instruction i1 = new SetrInstruction(organism, 1001, 2, SETR_ID); // PR1 dest
        i1.execute(simulation);

        // DR3 := PR1
        Instruction i2 = new SetrInstruction(organism, 3, 1001, SETR_ID);
        i2.execute(simulation);

        Object dr3 = organism.getDr(3);
        assertTrue(dr3 instanceof int[]);
        int[] v = (int[]) dr3;
        assertArrayEquals(new int[]{3,4}, v);
    }

    @Test
    void setr_pr_to_pr_scalar() {
        // Put a scalar into PR0 via DR move
        assertTrue(organism.setDr(0, dataVal(42)));
        Instruction toPr0 = new SetrInstruction(organism, 1000, 0, SETR_ID);
        toPr0.execute(simulation);

        // PR1 := PR0
        Instruction prToPr = new SetrInstruction(organism, 1001, 1000, SETR_ID);
        prToPr.execute(simulation);

        // Move PR1 back to DR4 and assert
        Instruction back = new SetrInstruction(organism, 4, 1001, SETR_ID);
        back.execute(simulation);

        Object dr4 = organism.getDr(4);
        assertTrue(dr4 instanceof Integer);
        assertEquals(42, Symbol.fromInt((Integer) dr4).toScalarValue());
    }
}
