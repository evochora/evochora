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
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0).toInt());
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

    // IFR: True then False
    @Test
    void testIfr_TrueCondition_ExecutesNext() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 0).toInt());
        placeInstruction("IFR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFR")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testIfr_FalseCondition_SkipsNext() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 1).toInt());
        placeInstruction("IFR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFR")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 0).toInt());
    }

    // IFI: True then False
    @Test
    void testIfi_TrueCondition_ExecutesNext() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0).toInt());
        placeInstruction("IFI", 0, 0);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFI")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testIfi_FalseCondition_SkipsNext() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 1).toInt());
        placeInstruction("IFI", 0, 0);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFI")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }

    // IFS: True then False
    @Test
    void testIfs_TrueCondition_ExecutesNext() {
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 5).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 5).toInt());
        placeInstruction("IFS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFS")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testIfs_FalseCondition_SkipsNext() {
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 10).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 5).toInt());
        placeInstruction("IFS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFS")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 0).toInt());
    }

    // LTR: True then False
    @Test
    void testLtr_TrueCondition_ExecutesNext() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 1).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 2).toInt());
        placeInstruction("LTR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LTR")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 2).toInt());
    }

    @Test
    void testLtr_FalseCondition_SkipsNext() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 2).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 1).toInt());
        placeInstruction("LTR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LTR")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 2).toInt());
    }

    // LTI: True then False
    @Test
    void testLti_TrueCondition_ExecutesNext() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 1).toInt());
        placeInstruction("LTI", 0, 2);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LTI")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 2).toInt());
    }

    @Test
    void testLti_FalseCondition_SkipsNext() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 2).toInt());
        placeInstruction("LTI", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LTI")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 2).toInt());
    }

    // LTS: True then False
    @Test
    void testLts_TrueCondition_ExecutesNext() {
        // op1 (top) < op2 -> true: push 2, then 1 -> op1=1, op2=2
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 2).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 1).toInt());
        placeInstruction("LTS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LTS")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testLts_FalseCondition_SkipsNext() {
        // op1 (top) < op2 -> false: push 1, then 2 -> op1=2, op2=1
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 1).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 2).toInt());
        placeInstruction("LTS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LTS")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 0).toInt());
    }

    // GTR: True then False
    @Test
    void testGtr_TrueCondition_ExecutesNext() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 3).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 2).toInt());
        placeInstruction("GTR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GTR")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 4).toInt());
    }

    @Test
    void testGtr_FalseCondition_SkipsNext() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 1).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 2).toInt());
        placeInstruction("GTR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GTR")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }

    // GTI: True then False
    @Test
    void testGti_TrueCondition_ExecutesNext() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 2).toInt());
        placeInstruction("GTI", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GTI")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 3).toInt());
    }

    @Test
    void testGti_FalseCondition_SkipsNext() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 1).toInt());
        placeInstruction("GTI", 0, 2);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GTI")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }

    // GTS: True then False
    @Test
    void testGts_TrueCondition_ExecutesNext() {
        // op1 (top) > op2 -> true: push 1, then 2 -> op1=2, op2=1
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 1).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 2).toInt());
        placeInstruction("GTS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GTS")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testGts_FalseCondition_SkipsNext() {
        // op1 (top) > op2 -> false: push 2, then 1 -> op1=1, op2=2
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 2).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 1).toInt());
        placeInstruction("GTS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GTS")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 0).toInt());
    }

    // IFTR: True then False
    @Test
    void testIftr_TrueCondition_ExecutesNext() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 7).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 9).toInt());
        placeInstruction("IFTR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFTR")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 8).toInt());
    }

    @Test
    void testIftr_FalseCondition_SkipsNext() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new Symbol(Config.TYPE_CODE, 2).toInt());
        placeInstruction("IFTR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFTR")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 0).toInt());
    }

    // IFTI: True then False
    @Test
    void testIfti_TrueCondition_ExecutesNext() {
        // Register TYPE_DATA, Immediate TYPE_DATA (von placeInstruction) -> true
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0).toInt());
        placeInstruction("IFTI", 0, 123);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFTI")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testIfti_FalseCondition_SkipsNext() {
        // Register TYPE_CODE, Immediate TYPE_DATA -> false
        org.setDr(0, new Symbol(Config.TYPE_CODE, 5).toInt());
        placeInstruction("IFTI", 0, 123);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFTI")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_CODE, 5).toInt());
    }

    // IFTS: True then False
    @Test
    void testIfts_TrueCondition_ExecutesNext() {
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 1).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 2).toInt());
        placeInstruction("IFTS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFTS")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }

        @org.junit.jupiter.api.AfterEach
        void assertNoInstructionFailure() {
            assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        }

    @Test
    void testIfts_FalseCondition_SkipsNext() {
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 1).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_CODE, 2).toInt());
        placeInstruction("IFTS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFTS")));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 0).toInt());
    }
}
