package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.organism.instructions.ScnsInstruction;
import org.evochora.organism.instructions.SeksInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

public class ScanSeekVariantsTest {

    private World world;
    private Simulation simulation;
    private Organism organism;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setup() {
        world = new World(Config.WORLD_SHAPE, true);
        simulation = new Simulation(world);
        organism = Organism.create(simulation, new int[]{5, 5}, 2000, simulation.getLogger());
    }

    @Test
    void scns_reads_symbol_non_destructive() {
        // place a DATA:7 at DP+vec (vec = 1|0), DP starts at (5,5)
        int[] vec = new int[]{1, 0};
        int[] target = organism.getTargetCoordinate(organism.getDp(), vec, world);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 7), target);

        int scnsId = Instruction.getInstructionIdByName("SCNS");
        world.setSymbol(new Symbol(Config.TYPE_CODE, scnsId), 5, 5); // at IP

        Deque<Object> ds = organism.getDataStack();
        ds.push(vec);

        Instruction scns = ScnsInstruction.plan(organism, world);
        scns.execute(simulation);

        assertFalse(organism.isInstructionFailed());
        Object popped = ds.pop();
        assertTrue(popped instanceof Integer);
        assertEquals(7, Symbol.fromInt((Integer) popped).toScalarValue(), "SCNS should push scanned symbol");
        // non-destructive: cell still has DATA:7
        assertEquals(7, world.getSymbol(target).value());
        assertEquals(Config.TYPE_DATA, world.getSymbol(target).type());
    }

    @Test
    void seks_moves_dp_if_empty_else_fails() {
        int seksId = Instruction.getInstructionIdByName("SEKS");
        world.setSymbol(new Symbol(Config.TYPE_CODE, seksId), 5, 5);

        // empty target: should move
        int[] vec = new int[]{0, 1};
        Deque<Object> ds = organism.getDataStack();
        ds.push(vec);
        Instruction seks = SeksInstruction.plan(organism, world);
        seks.execute(simulation);
        assertFalse(organism.isInstructionFailed());
        int[] expectedDp = organism.getTargetCoordinate(new int[]{5, 5}, vec, world);
        assertArrayEquals(expectedDp, organism.getDp());

        // occupied target: should fail
        organism = Organism.create(simulation, new int[]{5, 5}, 2000, simulation.getLogger());
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 5, 6); // occupy DP+(0,1)
        ds = organism.getDataStack();
        ds.push(new int[]{0, 1});
        world.setSymbol(new Symbol(Config.TYPE_CODE, seksId), 5, 5);
        seks = SeksInstruction.plan(organism, world);
        seks.execute(simulation);
        assertTrue(organism.isInstructionFailed(), "SEKS should fail when target cell is not empty");
    }
}
