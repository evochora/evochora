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

public class VMDataInstructionTest {

    private World world;
    private Organism org;
    private Simulation sim;
    private final int[] startPos = new int[]{5, 5};

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        world = new World(new int[]{100, 100}, true);
        sim = new Simulation(world);
        org = Organism.create(sim, startPos, 1000, sim.getLogger());
        sim.addOrganism(org);
    }

    private void placeInstruction(String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        world.setSymbol(new Symbol(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
            world.setSymbol(Symbol.fromInt(arg), currentPos);
        }
    }

    @Test
    void testSeti() {
        int immediateValue = new Symbol(Config.TYPE_DATA, 123).toInt();
        placeInstruction("SETI", 0, immediateValue);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(immediateValue);
    }

    @Test
    void testPush() {
        int value = new Symbol(Config.TYPE_DATA, 789).toInt();
        org.setDr(0, value);
        placeInstruction("PUSH", 0);
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(value);
    }
}
