package org.evochora.runtime.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.test.utils.SimulationTestUtils;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.internal.services.CallBindingRegistry;
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

    /**
     * Verifies that mixed REF + LREF parameter bindings are correctly mapped to
     * FDR and FLR keys in ProcFrame.parameterBindings. The callSiteBindings map
     * uses formal register IDs as keys (FDR_BASE+i for data, FLR_BASE+i for location).
     */
    @Test
    @Tag("unit")
    void testMixedRefAndLrefParameterBindings() {
        int labelHash = 55555 & Config.VALUE_MASK;
        int[] labelPos = new int[]{20};
        int[] afterLabel = new int[]{21};

        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash), labelPos);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), afterLabel);

        // Register mixed bindings: FDR0→DR1, FLR0→LR0
        int dr1Id = 1; // DR1
        int lr0Id = RegisterBank.LR.base; // LR0
        CallBindingRegistry.getInstance().clearAll();
        CallBindingRegistry.getInstance().registerBindingForAbsoluteCoord(
                org.getIp(), java.util.Map.of(
                        RegisterBank.FDR.base + 0, dr1Id,
                        RegisterBank.FLR.base + 0, lr0Id));

        placeInstruction("CALL", labelHash);
        sim.tick();

        assertThat(org.getCallStack()).isNotEmpty();
        Organism.ProcFrame frame = org.getCallStack().peek();
        java.util.Map<Integer, Integer> bindings = frame.parameterBindings();

        // FDR0 should be bound to DR1 (data parameter)
        assertThat(bindings.get(RegisterBank.FDR.base + 0))
                .as("FDR0 should be bound to DR1")
                .isEqualTo(dr1Id);

        // FLR0 should be bound to LR0 (location parameter)
        assertThat(bindings.get(RegisterBank.FLR.base + 0))
                .as("FLR0 should be bound to LR0")
                .isEqualTo(lr0Id);
    }

    // --- Persistent register (SDR/SLR) tests ---

    /**
     * Helper: sets up a CALL/RET pair. CALL at current IP jumps to labelPos.
     * A WAIT is placed at afterLabel (procedure body) and at returnTarget (after CALL).
     * Returns the positions for further manipulation.
     */
    private record CallRetSetup(int labelHash, int[] labelPos, int[] afterLabel, int[] returnTarget) {}

    private CallRetSetup setupCallRet(int hash) {
        int labelHash = hash & Config.VALUE_MASK;
        int[] labelPos = new int[]{20};
        int[] afterLabel = new int[]{21};

        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash), labelPos);
        // WAIT in procedure body — gives us a tick to inspect/modify state before RET
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), afterLabel);

        // WAIT after the CALL (return target)
        int[] returnTarget = org.getNextInstructionPosition(org.getIp(), org.getDv(), environment);
        returnTarget = org.getNextInstructionPosition(returnTarget, org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), returnTarget);

        return new CallRetSetup(labelHash, labelPos, afterLabel, returnTarget);
    }

    /**
     * Tests that persistent register state (SDR) survives across CALL/RET cycles.
     * CALL PROC_A, write SDR0=42, RET, CALL PROC_A again — SDR0 should still be 42.
     */
    @Test
    @Tag("unit")
    void testPersistentStateSurvivesCallRetCycle() {
        CallRetSetup setup = setupCallRet(88888);
        placeInstruction("CALL", setup.labelHash);

        // Tick 1: CALL → jumps to procedure, lands on WAIT at afterLabel
        sim.tick();
        assertThat(org.getCallStack()).hasSize(1);

        // Inside procedure: write SDR0 = 42
        org.writeOperand(RegisterBank.SDR.base, 42);

        // Place RET at afterLabel for next tick
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("RET")), setup.afterLabel);

        // Tick 2: RET → returns to returnTarget (WAIT)
        sim.tick();
        assertThat(org.getCallStack()).isEmpty();

        // Now call the same procedure again
        // Reset IP to start and place another CALL
        org.setIp(startPos);
        placeInstruction("CALL", setup.labelHash);
        // Restore WAIT at afterLabel for the procedure body
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), setup.afterLabel);

        // Tick 3: CALL → jumps to procedure again
        sim.tick();
        assertThat(org.getCallStack()).hasSize(1);

        // SDR0 should still be 42 from the first call
        assertThat((int) org.readOperand(RegisterBank.SDR.base))
                .as("SDR0 should persist across CALL/RET cycles")
                .isEqualTo(42);
    }

    /**
     * Tests that persistent register state is isolated between different procedures.
     * CALL PROC_A writes SDR0=42, then CALL PROC_B should see SDR0=0.
     */
    @Test
    @Tag("unit")
    void testPersistentStateIsolationBetweenProcedures() {
        int hashA = 88881 & Config.VALUE_MASK;
        int hashB = 88882 & Config.VALUE_MASK;
        int[] labelPosA = new int[]{20};
        int[] labelPosB = new int[]{30};
        int[] afterLabelA = new int[]{21};
        int[] afterLabelB = new int[]{31};

        environment.setMolecule(new Molecule(Config.TYPE_LABEL, hashA), labelPosA);
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, hashB), labelPosB);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), afterLabelA);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), afterLabelB);

        int[] returnTarget = org.getNextInstructionPosition(org.getIp(), org.getDv(), environment);
        returnTarget = org.getNextInstructionPosition(returnTarget, org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), returnTarget);

        // CALL PROC_A
        placeInstruction("CALL", hashA);
        sim.tick();

        // Inside PROC_A: write SDR0 = 42
        org.writeOperand(RegisterBank.SDR.base, 42);

        // RET from PROC_A
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("RET")), afterLabelA);
        sim.tick();
        assertThat(org.getCallStack()).isEmpty();

        // CALL PROC_B
        org.setIp(startPos);
        placeInstruction("CALL", hashB);
        sim.tick();
        assertThat(org.getCallStack()).hasSize(1);

        // Inside PROC_B: SDR0 should be 0 (PROC_B has its own persistent state)
        assertThat((int) org.readOperand(RegisterBank.SDR.base))
                .as("PROC_B should have its own persistent state, not PROC_A's")
                .isEqualTo(0);
    }

    /**
     * Tests that the dirty flag prevents unnecessary persistent state snapshots.
     * When SDR/SLR are never written, isPersistentDirty() stays false.
     */
    @Test
    @Tag("unit")
    void testDirtyFlagNotSetWhenPersistentRegistersUnwritten() {
        CallRetSetup setup = setupCallRet(88883);
        placeInstruction("CALL", setup.labelHash);

        // Before any writes, dirty flag should be false
        assertThat(org.isPersistentDirty()).as("Dirty flag should be false before any SDR/SLR write").isFalse();

        // CALL — no SDR/SLR written
        sim.tick();

        // Still false — CALL itself doesn't set persistentDirty
        assertThat(org.isPersistentDirty()).as("Dirty flag should stay false when SDR/SLR never written").isFalse();
    }

    /**
     * Tests that main-level persistent state is restored after returning from a procedure.
     * Main writes SDR0=99, CALL PROC_A writes SDR0=42, RET → SDR0 should be 99 again.
     */
    @Test
    @Tag("unit")
    void testMainLevelPersistentStateRestoredAfterRet() {
        // Write SDR0 = 99 at main level
        org.writeOperand(RegisterBank.SDR.base, 99);

        CallRetSetup setup = setupCallRet(88884);
        placeInstruction("CALL", setup.labelHash);

        // Tick 1: CALL
        sim.tick();

        // Inside procedure: SDR0 should be 0 (fresh proc state), write 42
        assertThat((int) org.readOperand(RegisterBank.SDR.base))
                .as("SDR0 inside new procedure should be 0")
                .isEqualTo(0);
        org.writeOperand(RegisterBank.SDR.base, 42);

        // Place RET
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("RET")), setup.afterLabel);

        // Tick 2: RET
        sim.tick();

        // Back at main level: SDR0 should be 99 (main-level state restored)
        assertThat((int) org.readOperand(RegisterBank.SDR.base))
                .as("Main-level SDR0 should be restored to 99 after RET")
                .isEqualTo(99);
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}