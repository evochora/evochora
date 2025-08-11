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

public class VMConditionalInstructionTest {

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
        org.setDr(0, 0);
    }

    private void placeInstruction(String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        world.setSymbol(new Symbol(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
            world.setSymbol(new Symbol(Config.TYPE_DATA, arg), currentPos);
        }
    }

    private void placeFollowingAddi(int instructionLength) {
        int[] nextIp = org.getIp();
        for (int i = 0; i < instructionLength; i++) {
            nextIp = org.getNextInstructionPosition(nextIp, world, org.getDv());
        }

        int addiOpcode = Instruction.getInstructionIdByName("ADDI");
        world.setSymbol(new Symbol(Config.TYPE_CODE, addiOpcode), nextIp);
        int[] arg1Ip = org.getNextInstructionPosition(nextIp, world, org.getDv());
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), arg1Ip);
        int[] arg2Ip = org.getNextInstructionPosition(arg1Ip, world, org.getDv());
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), arg2Ip);
    }

    @Test
    void testIfs_FalseCondition_SkipsNext() {
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 10).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 5).toInt());
        placeInstruction("IFS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFS")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(0);
    }
}
