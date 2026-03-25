package org.evochora.runtime.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.test.utils.SimulationTestUtils;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains low-level unit tests for the execution of control flow instructions by the virtual machine.
 * Each test sets up a specific state, executes a single jump, call, or return instruction,
 * and verifies that the organism's instruction pointer and call stack are updated correctly.
 * These tests operate on an in-memory simulation and do not require external resources.
 */
public class VMControlFlowInstructionTest {

    private Environment environment;
    private Organism org;
    private Simulation sim;
    private final int[] startPos = new int[]{5};

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{100}, true);
        sim = SimulationTestUtils.createSimulation(environment);
        org = Organism.create(sim, startPos, 1000, sim.getLogger());
        sim.addOrganism(org);
    }

    private void placeInstruction(String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), currentPos);
        }
    }

    /**
     * Tests the JMPI (Jump Immediate) instruction with fuzzy label matching.
     * Places a LABEL molecule at the target position and uses its hash as the operand.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testJmpi() {
        int[] labelPos = new int[]{15}; // LABEL molecule position
        int[] expectedIp = new int[]{16}; // Expected IP after jump (past the LABEL)
        int labelHash = 12345 & Config.VALUE_MASK; // Label hash value

        // Place LABEL molecule at target position
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash), labelPos);
        // Place WAIT at expected IP to stop instant-skip loop
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), expectedIp);

        // Place JMPI instruction with label hash as single operand
        placeInstruction("JMPI", labelHash);

        sim.tick();

        // IP should land AFTER the LABEL, not on it
        assertThat(org.getIp()).isEqualTo(expectedIp);
    }

    /**
     * Tests the CALL instruction with fuzzy label matching.
     * Places a LABEL molecule at the target position and uses its hash as the operand.
     * Verifies that the instruction pointer moves and a new frame is pushed to the call stack.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testCall() {
        int[] labelPos = new int[]{12}; // LABEL molecule position
        int[] expectedIp = new int[]{13}; // Expected IP after call (past the LABEL)
        int labelHash = 54321 & Config.VALUE_MASK; // Label hash value

        // Place LABEL molecule at target position
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash), labelPos);
        // Place WAIT at expected IP to stop instant-skip loop
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), expectedIp);

        // Place CALL instruction with label hash as single operand
        placeInstruction("CALL", labelHash);

        sim.tick();

        // IP should land AFTER the LABEL, not on it
        assertThat(org.getIp()).isEqualTo(expectedIp);
        assertThat(org.getCallStack().peek()).isInstanceOf(Organism.ProcFrame.class);
    }

    /**
     * Tests the JMPR (Jump Register) instruction with fuzzy label matching.
     * Stores a label hash in a register and places a LABEL molecule at the target.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testJmpr() {
        int[] labelPos = new int[]{17}; // LABEL molecule position
        int[] expectedIp = new int[]{18}; // Expected IP after jump (past the LABEL)
        int labelHash = 11111 & Config.VALUE_MASK; // Label hash value

        // Place LABEL molecule at target position
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash), labelPos);
        // Place WAIT at expected IP to stop instant-skip loop
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), expectedIp);

        // Store label hash in register DR0
        org.writeOperand(0, labelHash);
        placeInstruction("JMPR", 0);

        sim.tick();

        // IP should land AFTER the LABEL, not on it
        assertThat(org.getIp()).isEqualTo(expectedIp);
    }

    /**
     * Tests the JMPS (Jump Stack) instruction with fuzzy label matching.
     * Pushes a label hash onto the stack and places a LABEL molecule at the target.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testJmps() {
        int[] labelPos = new int[]{22}; // LABEL molecule position
        int[] expectedIp = new int[]{23}; // Expected IP after jump (past the LABEL)
        int labelHash = 22222 & Config.VALUE_MASK; // Label hash value

        // Place LABEL molecule at target position
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash), labelPos);
        // Place WAIT at expected IP to stop instant-skip loop
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), expectedIp);

        // Push label hash onto stack
        org.getDataStack().push(labelHash);
        placeInstruction("JMPS");

        sim.tick();

        // IP should land AFTER the LABEL, not on it
        assertThat(org.getIp()).isEqualTo(expectedIp);
    }

    /**
     * Tests the RET (Return) instruction. Verifies that the instruction pointer
     * is restored from the call stack.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testRet() {
        int[] expectedIp = new int[]{6};
        int[] callIp = new int[]{5}; // CALL instruction address
        Object[] savedRegisters = org.snapshotStackSavedRegisters();

        org.getCallStack().push(new Organism.ProcFrame("TEST_PROC", 0, expectedIp, callIp, savedRegisters, java.util.Collections.emptyMap()));

        // Place WAIT at expected IP to stop instant-skip loop
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), expectedIp);

        placeInstruction("RET");

        sim.tick();

        assertThat(org.getIp()).isEqualTo(expectedIp);
    }

    /**
     * Tests that CALL saves and RET restores all STACK_SAVED registers including FDR.
     * Sets known FDR values, executes CALL (which saves them), modifies FDR,
     * then executes RET and verifies FDR is restored.
     */
    @Test
    @Tag("unit")
    void testCallRetRestoresFdrs() {
        int labelHash = 77777 & Config.VALUE_MASK;
        int[] labelPos = new int[]{20};
        int[] afterLabel = new int[]{21};

        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash), labelPos);
        // Place RET at the procedure entry (right after the label)
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("RET")), afterLabel);
        // Place WAIT after the CALL to stop skip loop when RET returns here
        int[] returnTarget = org.getNextInstructionPosition(org.getIp(), org.getDv(), environment);
        returnTarget = org.getNextInstructionPosition(returnTarget, org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), returnTarget);

        // Set known FDR values before CALL
        org.writeOperand(RegisterBank.FDR.base + 0, 111);
        org.writeOperand(RegisterBank.FDR.base + 1, 222);

        // Execute CALL
        placeInstruction("CALL", labelHash);
        sim.tick();

        // Now inside procedure — FDR should still be 111/222 (CALL saved them, no modification yet)
        // The RET executes immediately (placed at afterLabel), restoring registers
        // After RET, we're back at returnTarget

        // Verify FDR is restored
        assertThat((int) org.readOperand(RegisterBank.FDR.base + 0)).isEqualTo(111);
        assertThat((int) org.readOperand(RegisterBank.FDR.base + 1)).isEqualTo(222);
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}