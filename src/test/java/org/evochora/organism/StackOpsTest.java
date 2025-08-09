package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.organism.instructions.DupInstruction;
import org.evochora.organism.instructions.SwapInstruction;
import org.evochora.organism.instructions.DropInstruction;
import org.evochora.organism.instructions.RotInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

public class StackOpsTest {

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
        organism = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
    }

    @Test
    void dup_basic_and_overflow() {
        Deque<Object> ds = organism.getDataStack();
        ds.push(1);
        int dupId = Instruction.getInstructionIdByName("DUP");
        world.setSymbol(new Symbol(Config.TYPE_CODE, dupId), 0, 0);

        Instruction dup = DupInstruction.plan(organism, world);
        dup.execute(simulation);

        assertFalse(organism.isInstructionFailed(), "DUP should succeed with 1 element");
        assertEquals(2, ds.size());
        assertEquals(1, ds.pop());
        assertEquals(1, ds.pop());

        // overflow
        for (int i = 0; i < Config.DS_MAX_DEPTH; i++) ds.push(0);
        dup = DupInstruction.plan(organism, world);
        dup.execute(simulation);
        assertTrue(organism.isInstructionFailed(), "DUP should fail on overflow");
    }

    @Test
    void dup_underflow() {
        int dupId = Instruction.getInstructionIdByName("DUP");
        world.setSymbol(new Symbol(Config.TYPE_CODE, dupId), 0, 0);
        Instruction dup = DupInstruction.plan(organism, world);
        dup.execute(simulation);
        assertTrue(organism.isInstructionFailed(), "DUP should fail on empty stack");
    }

    @Test
    void swap_ok_and_underflow() {
        Deque<Object> ds = organism.getDataStack();
        ds.push(10); // A
        ds.push(20); // B (top)
        int swapId = Instruction.getInstructionIdByName("SWAP");
        world.setSymbol(new Symbol(Config.TYPE_CODE, swapId), 0, 0);
        Instruction swap = SwapInstruction.plan(organism, world);
        swap.execute(simulation);
        assertFalse(organism.isInstructionFailed());
        assertEquals(10, ds.pop()); // A now top after swap
        assertEquals(20, ds.pop()); // B below
        // underflow
        ds.push(1);
        swap = SwapInstruction.plan(organism, world);
        swap.execute(simulation);
        assertTrue(organism.isInstructionFailed(), "SWAP should fail with only 1 element");
    }

    @Test
    void drop_ok_and_underflow() {
        Deque<Object> ds = organism.getDataStack();
        int dropId = Instruction.getInstructionIdByName("DROP");
        world.setSymbol(new Symbol(Config.TYPE_CODE, dropId), 0, 0);
        // underflow first
        Instruction drop = DropInstruction.plan(organism, world);
        drop.execute(simulation);
        assertTrue(organism.isInstructionFailed());

        // ok: use a fresh organism to avoid lingering failure flag
        organism = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
        ds = organism.getDataStack();
        world.setSymbol(new Symbol(Config.TYPE_CODE, dropId), 0, 0);
        ds.push(7);
        drop = DropInstruction.plan(organism, world);
        drop.execute(simulation);
        assertFalse(organism.isInstructionFailed());
        assertEquals(0, ds.size());
    }

    @Test
    void rot_ok_and_underflow() {
        Deque<Object> ds = organism.getDataStack();
        // [..., A, B, C] top=C → [..., B, C, A]
        ds.push(100); // A
        ds.push(200); // B
        ds.push(300); // C (top)
        int rotId = Instruction.getInstructionIdByName("ROT");
        world.setSymbol(new Symbol(Config.TYPE_CODE, rotId), 0, 0);
        Instruction rot = RotInstruction.plan(organism, world);
        rot.execute(simulation);
        assertFalse(organism.isInstructionFailed());
        assertEquals(100, ds.pop()); // A becomes top after rotation
        assertEquals(300, ds.pop()); // then C
        assertEquals(200, ds.pop()); // then B

        // underflow (<3 elements)
        ds.push(1);
        ds.push(2);
        rot = RotInstruction.plan(organism, world);
        rot.execute(simulation);
        assertTrue(organism.isInstructionFailed(), "ROT should fail with less than 3 elements");
    }
}
