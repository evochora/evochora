package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.organism.instructions.JmpsInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

public class StackJumpTest {

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
    void jmps_jumps_program_relative_from_stack() {
        int jmpsId = Instruction.getInstructionIdByName("JMPS");
        world.setSymbol(new Symbol(Config.TYPE_CODE, jmpsId), 0, 0);
        Deque<Object> ds = organism.getDataStack();
        ds.push(new int[]{3, 0});
        Instruction jmps = JmpsInstruction.plan(organism, world);
        jmps.execute(simulation);
        assertArrayEquals(new int[]{3,0}, organism.getIp());
    }
}
