package org.evochora.runtime.instructions;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Contains low-level unit tests for the execution of conditional instructions by the virtual machine.
 * Each test verifies that an instruction correctly decides whether to execute or skip the following
 * instruction based on the condition being tested.
 * These tests operate on an in-memory simulation and do not require external resources.
 */
public class VMConditionalInstructionTest {

    private Environment environment;
    private Organism org;
    private Simulation sim;
    private final int[] startPos = new int[]{5, 5};

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{100, 100}, true);
        sim = SimulationTestUtils.createSimulation(environment);
        // Create a dummy organism to ensure the main test organism does not have ID 0
        Organism.create(sim, new int[]{-1, -1}, 1, sim.getLogger());
        org = Organism.create(sim, startPos, 1000, sim.getLogger());
        sim.addOrganism(org);
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
    }

    private void placeInstruction(String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);
            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), currentPos);
        }
    }

    private void placeInstructionWithVector(String name, int... vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);
            environment.setMolecule(new Molecule(Config.TYPE_DATA, val), currentPos);
        }
    }

    /**
     * Helper method to place a standard "marker" instruction (ADDI %DR0, 1)
     * after the instruction being tested. This allows tests to easily check
     * if the marker instruction was executed or skipped.
     * @param instructionLength The length of the preceding instruction, to calculate placement.
     */
    private void placeFollowingAddi(int instructionLength) {
        int[] nextIp = org.getIp();
        for (int i = 0; i < instructionLength; i++) {
            nextIp = org.getNextInstructionPosition(nextIp, org.getDv(), environment);
        }

        int addiOpcode = Instruction.getInstructionIdByName("ADDI");
        environment.setMolecule(new Molecule(Config.TYPE_CODE, addiOpcode), nextIp);
        int[] arg1Ip = org.getNextInstructionPosition(nextIp, org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 0), arg1Ip);
        int[] arg2Ip = org.getNextInstructionPosition(arg1Ip, org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 1), arg2Ip);
    }

    private void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }

    /**
     * Tests that IFR (If Equal Register) executes the next instruction when the condition is true.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIfr_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0).toInt());
        placeInstruction("IFR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFR (If Equal Register) skips the next instruction when the condition is false.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIfr_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("IFR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFI (If Equal Immediate) executes the next instruction when the condition is true.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIfi_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        placeInstruction("IFI", 0, 0);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFI (If Equal Immediate) skips the next instruction when the condition is false.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIfi_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("IFI", 0, 0);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFS (If Equal Stack) executes the next instruction when the condition is true.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIfs_TrueCondition_ExecutesNext() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 5).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 5).toInt());
        placeInstruction("IFS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFS (If Equal Stack) skips the next instruction when the condition is false.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIfs_FalseCondition_SkipsNext() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 10).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 5).toInt());
        placeInstruction("IFS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that LTR (Less Than Register) executes the next instruction when the condition is true.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testLtr_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("LTR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LTR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that LTR (Less Than Register) skips the next instruction when the condition is false.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testLtr_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 2).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("LTR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LTR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that LTI (Less Than Immediate) executes the next instruction when the condition is true.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testLti_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("LTI", 0, 2);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LTI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that LTI (Less Than Immediate) skips the next instruction when the condition is false.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testLti_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("LTI", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LTI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that LTS (Less Than Stack) executes the next instruction when the condition is true.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testLts_TrueCondition_ExecutesNext() {
        // op1 (top) < op2 -> true: push 2, then 1 -> op1=1, op2=2
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("LTS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LTS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that LTS (Less Than Stack) skips the next instruction when the condition is false.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testLts_FalseCondition_SkipsNext() {
        // op1 (top) < op2 -> false: push 1, then 2 -> op1=2, op2=1
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("LTS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LTS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that GTR (Greater Than Register) executes the next instruction when the condition is true.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testGtr_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 3).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("GTR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GTR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 4).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that GTR (Greater Than Register) skips the next instruction when the condition is false.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testGtr_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("GTR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GTR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that GTI (Greater Than Immediate) executes the next instruction when the condition is true.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testGti_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("GTI", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GTI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 3).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that GTI (Greater Than Immediate) skips the next instruction when the condition is false.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testGti_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("GTI", 0, 2);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GTI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that GTS (Greater Than Stack) executes the next instruction when the condition is true.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testGts_TrueCondition_ExecutesNext() {
        // op1 (top) > op2 -> true: push 1, then 2 -> op1=2, op2=1
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("GTS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GTS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that GTS (Greater Than Stack) skips the next instruction when the condition is false.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testGts_FalseCondition_SkipsNext() {
        // op1 (top) > op2 -> false: push 2, then 1 -> op1=1, op2=2
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("GTS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GTS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFTR (If Type Equal Register) executes the next instruction when the condition is true.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIftr_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 7).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 9).toInt());
        placeInstruction("IFTR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFTR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 8).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFTR (If Type Equal Register) skips the next instruction when the condition is false.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIftr_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new Molecule(Config.TYPE_CODE, 2).toInt());
        placeInstruction("IFTR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFTR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFTI (If Type Equal Immediate) executes the next instruction when the condition is true.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIfti_TrueCondition_ExecutesNext() {
        // Register TYPE_DATA, Immediate TYPE_DATA (von placeInstruction) -> true
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        placeInstruction("IFTI", 0, 123);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFTI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFTI (If Type Equal Immediate) skips the next instruction when the condition is false.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIfti_FalseCondition_SkipsNext() {
        // Register TYPE_CODE, Immediate TYPE_DATA -> false
        org.setDr(0, new Molecule(Config.TYPE_CODE, 5).toInt());
        placeInstruction("IFTI", 0, 123);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFTI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_CODE, 5).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFTS (If Type Equal Stack) executes the next instruction when the condition is true.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIfts_TrueCondition_ExecutesNext() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("IFTS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFTS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFMR (If Memory owned Register) skips the next instruction when the target is not owned.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIfmr_NotOwned_SkipsNext() {
        org.setDr(1, new int[]{0, 1}); // unit vector
        placeInstruction("IFMR", 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFMR"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFMR (If Memory owned Register) executes the next instruction when the target is owned.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIfmr_Owned_ExecutesNext() {
        org.setDr(1, new int[]{0, 1}); // unit vector
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);        environment.setOwnerId(org.getId(), target);
        placeInstruction("IFMR", 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFMR"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFMI (If Memory owned Immediate) skips the next instruction when the target is not owned.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIfmi_NotOwned_SkipsNext() {
        placeInstructionWithVector("IFMI", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFMI"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFMS (If Memory owned Stack) skips the next instruction when the target is not owned.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIfms_NotOwned_SkipsNext() {
        org.getDataStack().push(new int[]{0, 1});
        placeInstruction("IFMS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFMS"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    /**
     * Tests that IFMR fails if the provided register does not contain a valid unit vector.
     * This is a unit test for the VM's error handling.
     */
    @Test
    @Tag("unit")
    void testIfmr_InvalidVector_Fails() {
        org.setDr(1, new int[]{1, 1}); // not a unit vector
        placeInstruction("IFMR", 1);
        sim.tick();
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("not a unit vector");
    }

    /**
     * Tests that IFTS (If Type Equal Stack) skips the next instruction when the condition is false.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testIfts_FalseCondition_SkipsNext() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_CODE, 2).toInt());
        placeInstruction("IFTS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFTS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInr_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("INR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInr_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0).toInt());
        placeInstruction("INR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testIni_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("INI", 0, 0);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testIni_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        placeInstruction("INI", 0, 0);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testIns_TrueCondition_ExecutesNext() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 10).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 5).toInt());
        placeInstruction("INS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testIns_FalseCondition_SkipsNext() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 5).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 5).toInt());
        placeInstruction("INS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testGetr_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 2).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("GETR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GETR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 3).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testGetr_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("GETR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GETR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testGeti_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("GETI", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GETI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 3).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testGeti_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("GETI", 0, 2);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GETI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testGets_TrueCondition_ExecutesNext() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("GETS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GETS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testGets_FalseCondition_SkipsNext() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("GETS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("GETS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testLetr_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("LETR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LETR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testLetr_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 2).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("LETR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LETR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testLeti_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("LETI", 0, 2);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LETI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testLeti_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("LETI", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LETI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 2).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testLets_TrueCondition_ExecutesNext() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("LETS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LETS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testLets_FalseCondition_SkipsNext() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("LETS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("LETS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testIntr_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new Molecule(Config.TYPE_CODE, 2).toInt());
        placeInstruction("INTR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INTR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testIntr_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 7).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 9).toInt());
        placeInstruction("INTR", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INTR"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInti_TrueCondition_ExecutesNext() {
        org.setDr(0, new Molecule(Config.TYPE_CODE, 5).toInt());
        placeInstruction("INTI", 0, 123);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INTI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_CODE, 6).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInti_FalseCondition_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        placeInstruction("INTI", 0, 123);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INTI"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInts_TrueCondition_ExecutesNext() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_CODE, 2).toInt());
        placeInstruction("INTS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INTS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInts_FalseCondition_SkipsNext() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("INTS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INTS"), environment));

        sim.tick();
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInmr_NotOwned_ExecutesNext() {
        org.setDr(1, new int[]{0, 1}); // unit vector
        placeInstruction("INMR", 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INMR"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInmr_Owned_SkipsNext() {
        org.setDr(1, new int[]{0, 1}); // unit vector
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);
        environment.setOwnerId(org.getId(), target);
        placeInstruction("INMR", 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INMR"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInmi_NotOwned_ExecutesNext() {
        placeInstructionWithVector("INMI", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INMI"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInmi_Owned_SkipsNext() {
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);
        environment.setOwnerId(org.getId(), target);
        placeInstructionWithVector("INMI", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INMI"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInms_NotOwned_ExecutesNext() {
        org.getDataStack().push(new int[]{0, 1});
        placeInstruction("INMS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INMS"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInms_Owned_SkipsNext() {
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);
        environment.setOwnerId(org.getId(), target);
        org.getDataStack().push(new int[]{0, 1});
        placeInstruction("INMS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INMS"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    // ===== Foreign Ownership Tests (IFF*, INF*) =====

    @Test
    @Tag("unit")
    void testIffr_Foreign_ExecutesNext() {
        org.setDr(1, new int[]{0, 1}); // unit vector
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);
        environment.setOwnerId(999, target); // Foreign owner
        placeInstruction("IFFR", 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFFR"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testIffr_NotForeign_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new int[]{0, 1}); // unit vector
        // Vacant (no owner) - not foreign
        placeInstruction("IFFR", 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFFR"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testIffr_Own_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new int[]{0, 1}); // unit vector
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);
        environment.setOwnerId(org.getId(), target); // Own
        placeInstruction("IFFR", 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFFR"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInfr_Foreign_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new int[]{0, 1}); // unit vector
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);
        environment.setOwnerId(999, target); // Foreign owner
        placeInstruction("INFR", 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INFR"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInfr_NotForeign_ExecutesNext() {
        org.setDr(1, new int[]{0, 1}); // unit vector
        // Vacant (no owner) - should execute next
        placeInstruction("INFR", 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INFR"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testIffi_Foreign_ExecutesNext() {
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);
        environment.setOwnerId(999, target); // Foreign owner
        placeInstructionWithVector("IFFI", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFFI"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInfi_Foreign_SkipsNext() {
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);
        environment.setOwnerId(999, target); // Foreign owner - INFI should skip when foreign
        placeInstructionWithVector("INFI", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INFI"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testIffs_Foreign_ExecutesNext() {
        org.getDataStack().push(new int[]{0, 1});
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);
        environment.setOwnerId(999, target); // Foreign owner
        placeInstruction("IFFS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFFS"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInfs_Foreign_SkipsNext() {
        org.getDataStack().push(new int[]{0, 1});
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);
        environment.setOwnerId(999, target); // Foreign owner - INFS should skip when foreign
        placeInstruction("INFS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INFS"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    // ===== Vacant Ownership Tests (IFV*, INV*) =====

    @Test
    @Tag("unit")
    void testIfvr_Vacant_ExecutesNext() {
        org.setDr(1, new int[]{0, 1}); // unit vector
        // Vacant (no owner, ownerId == 0)
        placeInstruction("IFVR", 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFVR"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testIfvr_NotVacant_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new int[]{0, 1}); // unit vector
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);
        environment.setOwnerId(999, target); // Foreign owner
        placeInstruction("IFVR", 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFVR"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testIfvr_Own_SkipsNext() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new int[]{0, 1}); // unit vector
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);
        environment.setOwnerId(org.getId(), target); // Own
        placeInstruction("IFVR", 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFVR"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInvr_Vacant_SkipsNext() {
        org.setDr(1, new int[]{0, 1}); // unit vector
        // Vacant (no owner)
        placeInstruction("INVR", 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INVR"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInvr_NotVacant_ExecutesNext() {
        org.setDr(1, new int[]{0, 1}); // unit vector
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);
        environment.setOwnerId(999, target); // Foreign owner
        placeInstruction("INVR", 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INVR"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testIfvi_Vacant_ExecutesNext() {
        // Vacant (no owner) - IFVI should execute next when vacant
        placeInstructionWithVector("IFVI", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFVI"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInvi_Vacant_SkipsNext() {
        // Vacant (no owner) - INVI should skip when vacant
        placeInstructionWithVector("INVI", 0, 1);
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INVI"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testIfvs_Vacant_ExecutesNext() {
        org.getDataStack().push(new int[]{0, 1});
        // Vacant (no owner) - IFVS should execute next when vacant
        placeInstruction("IFVS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("IFVS"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
        assertNoInstructionFailure();
    }

    @Test
    @Tag("unit")
    void testInvs_Vacant_SkipsNext() {
        org.getDataStack().push(new int[]{0, 1});
        // Vacant (no owner) - INVS should skip when vacant
        placeInstruction("INVS");
        placeFollowingAddi(Instruction.getInstructionLengthById(Instruction.getInstructionIdByName("INVS"), environment));
        sim.tick();
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
        assertNoInstructionFailure();
    }
}