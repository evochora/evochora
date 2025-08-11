package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class VMControlFlowInstructionTest {

    private World world;
    private Organism org;
    private Simulation sim;
    private final int[] startPos = new int[]{5};

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        world = new World(new int[]{100}, true);
        sim = new Simulation(world);
        org = Organism.create(sim, startPos, 1000, sim.getLogger());
        sim.addOrganism(org);
    }

    private void placeInstructionWithVector(String name, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        world.setSymbol(new Symbol(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
            world.setSymbol(new Symbol(Config.TYPE_DATA, val), currentPos);
        }
    }

    @Test
    void testJmpi() {
        int[] jumpDelta = new int[]{10};
        int[] expectedIp = org.getTargetCoordinate(org.getIp(), jumpDelta, world);
        placeInstructionWithVector("JMPI", jumpDelta);

        sim.tick();

        assertThat(org.getIp()).isEqualTo(expectedIp);
    }
}
