package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.organism.instructions.ControlFlowInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PRProcRegisterFrameTest {

    private World world;
    private Simulation simulation;
    private Organism organism;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setup() {
        Config.WORLD_SHAPE[0] = 30;
        Config.WORLD_SHAPE[1] = 5;
        world = new World(Config.WORLD_SHAPE, true);
        simulation = new Simulation(world);
        organism = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
    }

    @Test
    void pr0pr1_areRestoredAfterRet() {
        // Prepare CALL (delta 4,0) at (0,0); RET at (4,0)
        int callId = Instruction.getInstructionIdByName("CALL");
        int retId = Instruction.getInstructionIdByName("RET");
        int nopId = Instruction.getInstructionIdByName("NOP");

        world.setSymbol(new Symbol(Config.TYPE_CODE, callId), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 4), 1, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 2, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, retId), 4, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, nopId), 3, 0);

        // Set PR0/PR1 before CALL
        organism.setPr(0, 111);
        organism.setPr(1, 222);

        // CALL
        Instruction call = ControlFlowInstruction.plan(organism, world);
        call.execute(simulation);
        assertFalse(organism.isInstructionFailed(), "CALL should succeed");
        assertArrayEquals(new int[]{4,0}, organism.getIp(), "IP should jump to target");

        // Simulate callee clobbering PR0/PR1
        organism.setPr(0, 999);
        organism.setPr(1, 888);

        // RET
        Instruction ret = ControlFlowInstruction.plan(organism, world);
        ret.execute(simulation);
        assertFalse(organism.isInstructionFailed(), "RET should succeed");

        // PR0/PR1 restored to values from before CALL
        assertEquals(111, organism.getPr(0), "PR0 should be restored");
        assertEquals(222, organism.getPr(1), "PR1 should be restored");

        // IP should return to (3,0) (after CALL)
        assertArrayEquals(new int[]{3,0}, organism.getIp());
    }
}
