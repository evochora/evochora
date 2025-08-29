package org.evochora.runtime.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
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
        sim = new Simulation(environment);
        org = Organism.create(sim, startPos, 1000, sim.getLogger());
        sim.addOrganism(org);
    }

    private void placeInstructionWithVector(String name, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment); // CORRECTED
            environment.setMolecule(new Molecule(Config.TYPE_DATA, val), currentPos);
        }
    }

    private void placeInstruction(String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment); // CORRECTED
            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), currentPos);
        }
    }

    /**
     * Tests the JMPI (Jump Immediate) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testJmpi() {
        int[] jumpDelta = new int[]{10};
        int[] expectedIp = org.getTargetCoordinate(org.getIp(), jumpDelta, environment);
        placeInstructionWithVector("JMPI", jumpDelta);

        sim.tick();

        assertThat(org.getIp()).isEqualTo(expectedIp);
    }

    /**
     * Tests the CALL instruction. Verifies that the instruction pointer moves
     * and a new frame is pushed to the call stack.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testCall() {
        int[] jumpDelta = new int[]{7};
        int[] expectedIp = org.getTargetCoordinate(org.getIp(), jumpDelta, environment);
        placeInstructionWithVector("CALL", jumpDelta);

        sim.tick();

        assertThat(org.getIp()).isEqualTo(expectedIp);
        assertThat(org.getCallStack().peek()).isInstanceOf(Organism.ProcFrame.class);
    }

    /**
     * Tests the JMPR (Jump Register) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testJmpr() {
        int[] jumpVector = new int[]{12};
        int[] currentIp = org.getIp();
        int[] expectedIp = org.getTargetCoordinate(currentIp, jumpVector, environment);

        org.setDr(0, jumpVector);
        placeInstruction("JMPR", 0);

        sim.tick();

        assertThat(org.getIp()).isEqualTo(expectedIp);
    }

    /**
     * Tests the JMPS (Jump Stack) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testJmps() {
        int[] jumpDelta = new int[]{8};
        int[] expectedIp = org.getTargetCoordinate(org.getIp(), jumpDelta, environment);
        org.getDataStack().push(jumpDelta);
        placeInstruction("JMPS");

        sim.tick();

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
        Object[] prsSnapshot = org.getPrs().toArray(new Object[0]);
        Object[] fprsSnapshot = org.getFprs().toArray(new Object[0]);

        org.getCallStack().push(new Organism.ProcFrame("TEST_PROC", expectedIp, prsSnapshot, fprsSnapshot, java.util.Collections.emptyMap()));

        placeInstruction("RET");

        sim.tick();

        assertThat(org.getIp()).isEqualTo(expectedIp);
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}