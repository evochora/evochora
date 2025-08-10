package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.organism.instructions.DataInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

public class DataStackLimitsTest {

    private World world;
    private Simulation simulation;
    private Organism organism;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setup() {
        Config.WORLD_SHAPE[0] = 12;
        Config.WORLD_SHAPE[1] = 6;
        world = new World(Config.WORLD_SHAPE, true);
        simulation = new Simulation(world);
        organism = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
    }

    @Test
    void testPopUnderflowTriggersInstructionFailed() {
        // Place POP at (0,0), destination register %DR0
        int popId = Instruction.getInstructionIdByName("POP");
        world.setSymbol(new Symbol(Config.TYPE_CODE, popId), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);

        Instruction pop = DataInstruction.plan(organism, world);
        pop.execute(simulation);

        assertTrue(organism.isInstructionFailed(), "POP should fail on empty DS (underflow)");
    }

    @Test
    void testPushOverflowTriggersInstructionFailed() {
        // Fill DS to the limit, then try one more PUSH
        Deque<Object> ds = organism.getDataStack();
        for (int i = 0; i < Config.DS_MAX_DEPTH; i++) {
            ds.push(0);
        }

        // Prepare PUSH from %DR0
        int pushId = Instruction.getInstructionIdByName("PUSH");
        world.setSymbol(new Symbol(Config.TYPE_CODE, pushId), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 1, 0);
        organism.setDr(0, 123);

        Instruction push = DataInstruction.plan(organism, world);
        push.execute(simulation);

        assertTrue(organism.isInstructionFailed(), "PUSH should fail on DS overflow");
    }
}
