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
        org = Organism.create(sim, startPos, 1000);
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
        // Place WAIT after the CALL to stop execution when RET returns here
        int[] returnTarget = org.getNextInstructionPosition(org.getIp(), org.getDv(), environment);
        returnTarget = org.getNextInstructionPosition(returnTarget, org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), returnTarget);

        // Set known FDR values before CALL
        org.writeOperand(RegisterBank.FDR.base + 0, 111);
        org.writeOperand(RegisterBank.FDR.base + 1, 222);

        // Tick 1: CALL → jumps to afterLabel (RET instruction)
        placeInstruction("CALL", labelHash);
        sim.tick();
        assertThat(org.getCallStack()).hasSize(1);

        // Mutate FDRs inside the callee to verify RET restores them
        org.writeOperand(RegisterBank.FDR.base + 0, 999);
        org.writeOperand(RegisterBank.FDR.base + 1, 888);

        // Tick 2: RET → restores saved registers and returns
        sim.tick();
        assertThat(org.getCallStack()).isEmpty();

        // Verify FDRs are restored to pre-CALL values (not the callee's 999/888)
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

    /**
     * Tests that callee writes to STACK_SAVED registers do not leak to the caller
     * when the caller never wrote to any STACK_SAVED register (stackSavedDirty=false at CALL).
     * Bug: savedRegisters=null when !dirty → no restore on RET → callee values leak.
     */
    @Test
    @Tag("unit")
    void testCallRetDoesNotLeakCalleeStackSavedRegisters() {
        CallRetSetup setup = setupCallRet(99999);

        // Verify PDR0 is 0 (default) and stackSavedDirty is false
        assertThat((int) org.readOperand(RegisterBank.PDR.base)).isEqualTo(0);

        // CALL
        placeInstruction("CALL", setup.labelHash);
        sim.tick();
        assertThat(org.getCallStack()).hasSize(1);

        // Callee writes PDR0 = 42
        org.writeOperand(RegisterBank.PDR.base, 42);

        // RET
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("RET")), setup.afterLabel);
        sim.tick();
        assertThat(org.getCallStack()).isEmpty();

        // PDR0 must be 0 (caller's state), not 42 (callee's value that should not leak)
        assertThat((int) org.readOperand(RegisterBank.PDR.base))
                .as("Callee's PDR0 write must not leak to caller when savedRegisters was null")
                .isEqualTo(0);
    }

    /**
     * Tests that nested callee writes to PERSISTENT registers do not leak to the intermediate
     * caller when that caller never wrote any PERSISTENT register (no stored snapshot).
     * Scenario: Main → CALL A → CALL B, B writes SDR0=42, B RETs to A → A must see SDR0=0.
     */
    @Test
    @Tag("unit")
    void testNestedCallRetDoesNotLeakCalleePersistentRegisters() {
        // Set up two procedures: A at pos 20, B at pos 40
        int hashA = 88801 & Config.VALUE_MASK;
        int hashB = 88802 & Config.VALUE_MASK;
        int[] labelPosA = new int[]{20};
        int[] afterLabelA = new int[]{21}; // WAIT — A's body, we'll replace with CALL B
        int[] labelPosB = new int[]{40};
        int[] afterLabelB = new int[]{41}; // WAIT — B's body

        environment.setMolecule(new Molecule(Config.TYPE_LABEL, hashA), labelPosA);
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, hashB), labelPosB);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), afterLabelA);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), afterLabelB);

        // Return target for Main's CALL A
        int[] returnTarget = org.getNextInstructionPosition(org.getIp(), org.getDv(), environment);
        returnTarget = org.getNextInstructionPosition(returnTarget, org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), returnTarget);

        // Tick 1: Main CALL A (persistentDirty=false, no persistent save)
        placeInstruction("CALL", hashA);
        sim.tick();
        assertThat(org.getCallStack()).hasSize(1);

        // Now inside A. Place CALL B at A's body position
        int callOpcode = Instruction.getInstructionIdByName("CALL");
        environment.setMolecule(new Molecule(Config.TYPE_CODE, callOpcode), afterLabelA);
        int[] callBOperand = org.getNextInstructionPosition(afterLabelA, org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_DATA, hashB), callBOperand);
        // Return target for A's CALL B
        int[] returnFromB = org.getNextInstructionPosition(callBOperand, org.getDv(), environment);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), returnFromB);

        // Tick 2: A CALL B (persistentDirty still false, no persistent save for A)
        sim.tick();
        assertThat(org.getCallStack()).hasSize(2);

        // Inside B: write SDR0 = 42 (first persistent write, sets persistentDirty=true)
        org.writeOperand(RegisterBank.SDR.base, 42);

        // RET from B
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("RET")), afterLabelB);

        // Tick 3: B RET → should restore A's persistent state (A has no snapshot → must reset)
        sim.tick();
        assertThat(org.getCallStack()).hasSize(1);

        // SDR0 must be 0 (A's default), not 42 (B's value that should not leak to A)
        assertThat((int) org.readOperand(RegisterBank.SDR.base))
                .as("B's SDR0=42 must not leak to A when A has no persistent snapshot")
                .isEqualTo(0);
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

    /**
     * Tests that recoverFromStall correctly resets currentProcLabelHash after
     * unwinding the call stack. Without the fix, currentProcLabelHash stays on
     * the callee's hash, causing subsequent SDR writes to be attributed to the
     * wrong procedure.
     */
    @Test
    @Tag("unit")
    void testRecoverFromStallResetsPersistentContext() {
        // Use a non-toroidal environment so IP advance runs off the edge → stall
        Environment nonToroidal = new Environment(new int[]{100}, false);
        Simulation stalSim = SimulationTestUtils.createSimulation(nonToroidal);
        Organism stalOrg = Organism.create(stalSim, new int[]{5}, 1000);
        stalSim.addOrganism(stalOrg);

        // Do NOT write SDR before the CALL — persistentDirty stays false.
        // This is the key: recoverFromStall skips currentProcLabelHash reset when !persistentDirty.

        // Label at position 95, nothing after it → IP advance runs off edge → stall
        int labelHash = 66666 & Config.VALUE_MASK;
        nonToroidal.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash), new int[]{95});

        // Place WAIT at the return target (pos 7, after CALL at 5 + operand at 6)
        nonToroidal.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), new int[]{7});

        // Place CALL
        int callOpcode = Instruction.getInstructionIdByName("CALL");
        nonToroidal.setMolecule(new Molecule(Config.TYPE_CODE, callOpcode), stalOrg.getIp());
        int[] afterCall = stalOrg.getNextInstructionPosition(stalOrg.getIp(), stalOrg.getDv(), nonToroidal);
        nonToroidal.setMolecule(new Molecule(Config.TYPE_DATA, labelHash), afterCall);

        // Tick: CALL → jump to label → IP advance past label → empty zone → stall → recovery
        stalSim.tick();

        assertThat(stalOrg.getCallStack()).as("Call stack should be empty after stall recovery").isEmpty();
        assertThat(stalOrg.isInstructionFailed()).isTrue();

        // Now write SDR0 = 77 — this should be attributed to main level
        stalOrg.writeOperand(RegisterBank.SDR.base, 77);

        // To expose the bug, we need a CALL/RET cycle that triggers persistent state save/restore.
        // If currentProcLabelHash is wrong (still callee), the CALL saves SDR0=77 under the
        // callee's hash instead of main. After RET, main's persistent state (SDR0=99) is restored.
        stalOrg.resetTickState();
        stalOrg.setIp(new int[]{5});

        // Set up a second procedure for the CALL/RET cycle
        int labelHash2 = 77777 & Config.VALUE_MASK;
        nonToroidal.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash2), new int[]{50});
        // RET right after the label
        nonToroidal.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("RET")), new int[]{51});

        // Place CALL to second proc
        nonToroidal.setMolecule(new Molecule(Config.TYPE_CODE, callOpcode), stalOrg.getIp());
        int[] afterCall2 = stalOrg.getNextInstructionPosition(stalOrg.getIp(), stalOrg.getDv(), nonToroidal);
        nonToroidal.setMolecule(new Molecule(Config.TYPE_DATA, labelHash2), afterCall2);
        // WAIT at return target
        int[] retTarget2 = stalOrg.getNextInstructionPosition(afterCall2, stalOrg.getDv(), nonToroidal);
        nonToroidal.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), retTarget2);

        // Tick 1: CALL → enters proc2
        stalSim.tick();
        assertThat(stalOrg.getCallStack()).hasSize(1);

        // Tick 2: RET → back to main
        stalSim.tick();
        assertThat(stalOrg.getCallStack()).isEmpty();

        // SDR0 should be 77 (main-level write after stall recovery).
        // Bug: currentProcLabelHash was wrong after stall (still on stalled proc), so CALL
        // saved SDR0=77 under the stalled proc's hash. RET restored main's state (SDR0=0 default).
        assertThat((int) stalOrg.readOperand(RegisterBank.SDR.base))
                .as("SDR0 should be 77 — stall recovery must reset currentProcLabelHash so persistent state is attributed to main")
                .isEqualTo(77);
    }

    /**
     * Tests that recoverFromStall does not leak callee's STACK_SAVED register writes
     * when the caller had never written any STACK_SAVED register (savedRegisters=null).
     * Setup: CALL → callee executes SETI %PDR0 → next instruction is in empty zone → stall.
     */
    @Test
    @Tag("unit")
    void testRecoverFromStallDoesNotLeakCalleeStackSavedRegisters() {
        Environment nonToroidal = new Environment(new int[]{100}, false);
        Simulation stalSim = SimulationTestUtils.createSimulation(nonToroidal);
        Organism stalOrg = Organism.create(stalSim, new int[]{5}, 1000);
        stalSim.addOrganism(stalOrg);

        // PDR0 = 0 (default), stackSavedDirty=false
        assertThat((int) stalOrg.readOperand(RegisterBank.PDR.base)).isEqualTo(0);

        // Place CALL at pos 5, targeting label at pos 90
        int labelHash = 55555 & Config.VALUE_MASK;
        int callOpcode = Instruction.getInstructionIdByName("CALL");
        nonToroidal.setMolecule(new Molecule(Config.TYPE_CODE, callOpcode), new int[]{5});
        nonToroidal.setMolecule(new Molecule(Config.TYPE_DATA, labelHash), new int[]{6});
        // WAIT at return target (pos 7)
        nonToroidal.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("WAIT")), new int[]{7});

        // Label at pos 90, SETI %PDR0 DATA:42 at pos 91 (callee code)
        nonToroidal.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash), new int[]{90});
        int setiOpcode = Instruction.getInstructionIdByName("SETI");
        nonToroidal.setMolecule(new Molecule(Config.TYPE_CODE, setiOpcode), new int[]{91});
        // SETI operands: register ID for PDR0, then DATA:42
        nonToroidal.setMolecule(new Molecule(Config.TYPE_REGISTER, RegisterBank.PDR.base), new int[]{92});
        nonToroidal.setMolecule(new Molecule(Config.TYPE_DATA, 42), new int[]{93});
        // Pos 94+ is empty → after SETI executes, skipNopCells finds nothing → stall

        // Tick 1: CALL → jumps to pos 91 (after label at 90)
        stalSim.tick();
        assertThat(stalOrg.getCallStack()).hasSize(1);

        // Tick 2: SETI %PDR0 DATA:42 → executes, then skipNopCells → empty → stall → recovery
        stalSim.tick();

        // After recovery: call stack should be empty, instructionFailed should be true
        assertThat(stalOrg.getCallStack()).isEmpty();
        assertThat(stalOrg.isInstructionFailed()).isTrue();

        // PDR0 must be 0 (caller's default), not 42 (callee's write that should not leak)
        assertThat((int) stalOrg.readOperand(RegisterBank.PDR.base))
                .as("Callee's PDR0=42 must not leak to caller after stall recovery with null savedRegisters")
                .isEqualTo(0);

        stalOrg.resetTickState();
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        CallBindingRegistry.getInstance().clearAll();
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}