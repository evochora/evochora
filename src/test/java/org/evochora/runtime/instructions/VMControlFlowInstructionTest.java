package org.evochora.runtime.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.test.utils.SimulationTestUtils;
import org.evochora.runtime.isa.Instruction;
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

        // Store label hash in register DR0
        org.setDr(0, labelHash);
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
        Object[] prsSnapshot = org.getPrs().toArray(new Object[0]);
        Object[] fprsSnapshot = org.getFprs().toArray(new Object[0]);

        org.getCallStack().push(new Organism.ProcFrame("TEST_PROC", expectedIp, callIp, prsSnapshot, fprsSnapshot, java.util.Collections.emptyMap()));

        placeInstruction("RET");

        sim.tick();

        assertThat(org.getIp()).isEqualTo(expectedIp);
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}