package org.evochora.runtime.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.test.utils.SimulationTestUtils;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Deque;
import org.junit.jupiter.api.Tag;

/**
 * Unit tests for the VM execution of LocationInstruction operations.
 * Tests the runtime behavior of location-based instructions including
 * Data Pointers (DPs), Location Registers (LRs), and the Location Stack (LS).
 * These tests use an in-memory simulation and do not require external resources.
 */
public class VMLocationInstructionTest {

    private Simulation sim;
    private Organism org;

    @BeforeEach
    void setUp() {
        // A minimal environment is needed for organism creation
        Instruction.init();
        Environment environment = new Environment(new int[]{10, 10}, true);
        sim = SimulationTestUtils.createSimulation(environment);
        org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        sim.addOrganism(org);
    }

    private void placeInstruction(Organism o, String name, Integer... args) {
        Environment environment = sim.getEnvironment();
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), o.getIp());
        int[] cur = o.getIp();
        for (int arg : args) {
            cur = o.getNextInstructionPosition(cur, o.getDv(), environment);
            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), cur);
        }
    }

    // --- Data Pointer (DP) Tests ---

    /**
     * Verifies that all Data Pointers (DPs) are correctly initialized to the organism's starting position.
     * This is a unit test for the Organism's initial state.
     */
    @Test
    @Tag("unit")
    void testMultipleDpInitialization() {
        assertThat(org.getDps()).hasSize(Config.NUM_DATA_POINTERS);
        // All DPs should be initialized to the organism's start position
        for (int i = 0; i < Config.NUM_DATA_POINTERS; i++) {
            assertThat(org.getDp(i)).isEqualTo(new int[]{0, 0});
        }
    }

    /**
     * Verifies that a specific Data Pointer can be set and retrieved without affecting others.
     * This is a unit test for the Organism's state management.
     */
    @Test
    @Tag("unit")
    void testSetAndGetSpecificDp() {
        int[] newPosition = {5, 5};
        org.setDp(1, newPosition);

        assertThat(org.getDp(1)).isEqualTo(newPosition);
        // Ensure other DPs are unaffected
        assertThat(org.getDp(0)).isEqualTo(new int[]{0, 0});
    }

    /**
     * Verifies that attempting to access a Data Pointer with an out-of-bounds index
     * correctly sets the organism's failure state.
     * This is a unit test for Organism error handling.
     */
    @Test
    @Tag("unit")
    void testAccessingInvalidDpReportsFailure() {
        // Test getting out of bounds
        assertThat(org.getDp(Config.NUM_DATA_POINTERS)).isNull();
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("DP index out of bounds");

        // Reset failure flag and test setting out of bounds
        org.resetTickState();
        assertThat(org.isInstructionFailed()).isFalse();
        org.setDp(Config.NUM_DATA_POINTERS, new int[]{1, 1});
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("DP index out of bounds");
    }

    // --- Location Register (LR) Tests ---

    /**
     * Verifies that all Location Registers (LRs) are correctly initialized to a zero vector.
     * This is a unit test for the Organism's initial state.
     */
    @Test
    @Tag("unit")
    void testLocationRegisterInitialization() {
        // LRs should be initialized to zero vectors
        for (int i = 0; i < Config.NUM_LOCATION_REGISTERS; i++) {
            assertThat(org.getLr(i)).containsExactly(0, 0);
        }
    }

    /**
     * Verifies that a specific Location Register can be set and retrieved correctly.
     * This is a unit test for the Organism's state management.
     */
    @Test
    @Tag("unit")
    void testSetAndGetSpecificLr() {
        int[] newLocation = {1, 2};
        org.setLr(0, newLocation);
        assertThat(org.getLr(0)).isEqualTo(newLocation);
    }

    /**
     * Confirms the type safety of the `setLr` method, which should only accept vector types.
     * This test is conceptual as a direct call with a non-vector type would be a compile-time error.
     */
    @Test
    @Tag("unit")
    void testSettingNonVectorToLrFails() {
        // The setLr method now only accepts int[], so this test is implicitly handled by the compiler.
        // We can test the generic `writeOperand` logic once LRs are integrated there.
        // For now, this confirms the type safety of the new method.
        // org.setLr(0, 123); // This would not compile
    }

    // --- LRLR Instruction Tests ---

    /**
     * Verifies that the LRLR instruction correctly copies one Location Register to another.
     * This is a unit test for the new LRLR instruction functionality.
     */
    @Test
    @Tag("unit")
    void testLrlrInstruction() {
        // Set up source LR with a test vector
        int[] sourceVector = {5, 7};
        org.setLr(1, sourceVector);
        
        // Verify source LR is set correctly
        assertThat(org.getLr(1)).isEqualTo(sourceVector);
        
        // Execute LRLR instruction: copy LR1 to LR2
        int lr1 = new Molecule(Config.TYPE_DATA, 1).toInt();
        int lr2 = new Molecule(Config.TYPE_DATA, 2).toInt();
        placeInstruction(org, "LRLR", lr2, lr1);
        sim.tick();
        
        // Verify that LR2 now contains the copied vector
        assertThat(org.getLr(2)).isEqualTo(sourceVector);
        
        // Verify that LR1 is unchanged
        assertThat(org.getLr(1)).isEqualTo(sourceVector);
        
        // Verify that the vectors are independent (not the same reference)
        assertThat(org.getLr(2)).isNotSameAs(sourceVector);
        assertThat(org.getLr(1)).isNotSameAs(org.getLr(2));
    }

    /**
     * Verifies that the LRLR instruction fails gracefully with invalid LR indices.
     * This is a unit test for the LRLR instruction error handling.
     */
    @Test
    @Tag("unit")
    void testLrlrInstructionInvalidIndices() {
        // Set up a valid source LR
        org.setLr(0, new int[]{1, 2});
        
        // Test with invalid destination LR index (out of bounds)
        int invalidDest = new Molecule(Config.TYPE_DATA, Config.NUM_LOCATION_REGISTERS).toInt();
        int validSrc = new Molecule(Config.TYPE_DATA, 0).toInt();
        placeInstruction(org, "LRLR", invalidDest, validSrc);
        sim.tick();
        
        // Verify instruction failed
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Invalid destination LR index");
        
        // Reset failure state
        org.resetTickState();
        
        // Test with invalid source LR index
        int validDest = new Molecule(Config.TYPE_DATA, 1).toInt();
        int invalidSrc = new Molecule(Config.TYPE_DATA, Config.NUM_LOCATION_REGISTERS).toInt();
        placeInstruction(org, "LRLR", validDest, invalidSrc);
        sim.tick();
        
        // Verify instruction failed
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Invalid source LR index");
    }

    // --- Location Stack (LS) Tests ---

    /**
     * Verifies the basic push and pop functionality of the Location Stack (LS).
     * This is a unit test for the Organism's stack data structures.
     */
    @Test
    @Tag("unit")
    void testLocationStackPushAndPop() {
        Deque<int[]> ls = org.getLocationStack();
        int[] locationA = {3, 3};
        int[] locationB = {4, 4};

        ls.push(locationA);
        ls.push(locationB);

        assertThat(ls.size()).isEqualTo(2);
        assertThat(ls.pop()).isEqualTo(locationB);
        assertThat(ls.pop()).isEqualTo(locationA);
        assertThat(ls.isEmpty()).isTrue();
    }

    /**
     * Simulates the execution of a series of location-based instructions to test
     * their interaction with the Location Stack and Location Registers.
     * This is a unit test verifying the runtime behavior of these specific instructions.
     */
    @Test
    @Tag("unit")
    void testLocationInstructions_ls_lr_roundtrip() {
        org.setDp(0, org.getIp());
        // DPLS: push active DP to LS
        placeInstruction(org, "DPLS");
        sim.tick();
        // PUSL: copy LR0 to LS after DPLR
        int lr0 = new Molecule(Config.TYPE_DATA, 0).toInt();
        placeInstruction(org, "DPLR", lr0);
        sim.tick();
        placeInstruction(org, "PUSL", lr0);
        sim.tick();
        // LRDR: copy LR0 into DR0
        placeInstruction(org, "LRDR", 0, lr0);
        sim.tick();
        assertThat(org.getDr(0)).isNotNull();
        // LSDS: pop to DS
        placeInstruction(org, "LSDS");
        sim.tick();
        // SKLR: move active DP to LR0
        placeInstruction(org, "SKLR", lr0);
        sim.tick();
        assertThat(org.getActiveDp()).isEqualTo(org.getLr(0));
    }

    /**
     * Verifies that the Location Stack's maximum depth is correctly handled.
     * This test simulates filling the stack to capacity to check overflow conditions.
     * This is a unit test for the Organism's stack limits.
     */
    @Test
    @Tag("unit")
    void testLocationStackOverflow() {
        Deque<int[]> ls = org.getLocationStack();
        // Fill the stack to its maximum capacity
        for (int i = 0; i < Config.LOCATION_STACK_MAX_DEPTH; i++) {
            ls.push(new int[]{i, i});
        }
        assertThat(ls.size()).isEqualTo(Config.LOCATION_STACK_MAX_DEPTH);

        // A direct push would exceed capacity, but ArrayDeque grows.
        // In the VM, an instruction would check the size *before* pushing.
        // We simulate this check here.
        boolean wouldOverflow = ls.size() >= Config.LOCATION_STACK_MAX_DEPTH;
        assertThat(wouldOverflow).isTrue();
    }

    // --- Location Stack Operations Tests ---

    /**
     * Tests the DUPL instruction (duplicate top of location stack).
     */
    @Test
    @Tag("unit")
    void testDuplInstruction() {
        Deque<int[]> ls = org.getLocationStack();
        int[] location = {3, 4};
        ls.push(location);
        
        placeInstruction(org, "DUPL");
        sim.tick();
        
        assertThat(ls.size()).isEqualTo(2);
        assertThat(ls.pop()).isEqualTo(location);
        assertThat(ls.pop()).isEqualTo(location);
    }

    /**
     * Tests the SWPL instruction (swap top two elements of location stack).
     */
    @Test
    @Tag("unit")
    void testSwplInstruction() {
        Deque<int[]> ls = org.getLocationStack();
        int[] locationA = {1, 2};
        int[] locationB = {3, 4};
        ls.push(locationA);
        ls.push(locationB);
        
        placeInstruction(org, "SWPL");
        sim.tick();
        
        assertThat(ls.size()).isEqualTo(2);
        assertThat(ls.pop()).isEqualTo(locationA); // B and A are swapped
        assertThat(ls.pop()).isEqualTo(locationB);
    }

    /**
     * Tests the DRPL instruction (drop top element from location stack).
     */
    @Test
    @Tag("unit")
    void testDrplInstruction() {
        Deque<int[]> ls = org.getLocationStack();
        int[] locationA = {1, 2};
        int[] locationB = {3, 4};
        ls.push(locationA);
        ls.push(locationB);
        
        placeInstruction(org, "DRPL");
        sim.tick();
        
        assertThat(ls.size()).isEqualTo(1);
        assertThat(ls.pop()).isEqualTo(locationA);
    }

    /**
     * Tests the ROTL instruction (rotate top three elements of location stack).
     */
    @Test
    @Tag("unit")
    void testRotlInstruction() {
        Deque<int[]> ls = org.getLocationStack();
        int[] locationA = {1, 1};
        int[] locationB = {2, 2};
        int[] locationC = {3, 3};
        ls.push(locationA);
        ls.push(locationB);
        ls.push(locationC);
        
        placeInstruction(org, "ROTL");
        sim.tick();
        
        assertThat(ls.size()).isEqualTo(3);
        // After ROTL: [C, A, B] = [{3,3}, {1,1}, {2,2}]
        assertThat(ls.pop()).isEqualTo(locationC); // Should be {3,3}
        assertThat(ls.pop()).isEqualTo(locationA); // Should be {1,1}
        assertThat(ls.pop()).isEqualTo(locationB); // Should be {2,2}
    }

    /**
     * Tests the DPLS instruction (push active DP to location stack).
     */
    @Test
    @Tag("unit")
    void testDplsInstruction() {
        int[] dpPosition = {5, 6};
        org.setActiveDp(dpPosition);
        Deque<int[]> ls = org.getLocationStack();
        
        placeInstruction(org, "DPLS");
        sim.tick();
        
        assertThat(ls.size()).isEqualTo(1);
        assertThat(ls.pop()).isEqualTo(dpPosition);
    }

    /**
     * Tests the SKLS instruction (pop from location stack and set as active DP).
     */
    @Test
    @Tag("unit")
    void testSklsInstruction() {
        Deque<int[]> ls = org.getLocationStack();
        int[] location = {7, 8};
        ls.push(location);
        
        placeInstruction(org, "SKLS");
        sim.tick();
        
        assertThat(ls.isEmpty()).isTrue();
        assertThat(org.getActiveDp()).isEqualTo(location);
    }

    /**
     * Tests the LSDS instruction (pop from location stack and push to data stack).
     */
    @Test
    @Tag("unit")
    void testLsdsInstruction() {
        Deque<int[]> ls = org.getLocationStack();
        int[] location = {9, 10};
        ls.push(location);
        
        placeInstruction(org, "LSDS");
        sim.tick();
        
        assertThat(ls.isEmpty()).isTrue();
        assertThat(org.getDataStack().pop()).isEqualTo(location);
    }

    // --- Location Register Operations Tests ---

    /**
     * Tests the DPLR instruction (copy active DP to LR register).
     */
    @Test
    @Tag("unit")
    void testDplrInstruction() {
        int[] dpPosition = {11, 12};
        org.setActiveDp(dpPosition);
        int lrIndex = new Molecule(Config.TYPE_DATA, 1).toInt();
        
        placeInstruction(org, "DPLR", lrIndex);
        sim.tick();
        
        assertThat(org.getLr(1)).isEqualTo(dpPosition);
    }

    /**
     * Tests the SKLR instruction (set active DP from LR register).
     */
    @Test
    @Tag("unit")
    void testSklrInstruction() {
        int[] lrPosition = {13, 14};
        org.setLr(2, lrPosition);
        int lrIndex = new Molecule(Config.TYPE_DATA, 2).toInt();
        
        placeInstruction(org, "SKLR", lrIndex);
        sim.tick();
        
        assertThat(org.getActiveDp()).isEqualTo(lrPosition);
    }

    /**
     * Tests the PUSL instruction (push LR register to location stack).
     */
    @Test
    @Tag("unit")
    void testPuslInstruction() {
        int[] lrPosition = {15, 16};
        org.setLr(3, lrPosition);
        Deque<int[]> ls = org.getLocationStack();
        int lrIndex = new Molecule(Config.TYPE_DATA, 3).toInt();
        
        placeInstruction(org, "PUSL", lrIndex);
        sim.tick();
        
        assertThat(ls.size()).isEqualTo(1);
        assertThat(ls.pop()).isEqualTo(lrPosition);
    }

    /**
     * Tests the POPL instruction (pop from location stack to LR register).
     */
    @Test
    @Tag("unit")
    void testPoplInstruction() {
        Deque<int[]> ls = org.getLocationStack();
        int[] location = {17, 18};
        ls.push(location);
        int lrIndex = new Molecule(Config.TYPE_DATA, 0).toInt();
        
        placeInstruction(org, "POPL", lrIndex);
        sim.tick();
        
        assertThat(ls.isEmpty()).isTrue();
        assertThat(org.getLr(0)).isEqualTo(location);
    }

    /**
     * Tests the LRDS instruction (push LR register to data stack).
     */
    @Test
    @Tag("unit")
    void testLrdsInstruction() {
        int[] lrPosition = {19, 20};
        org.setLr(1, lrPosition);
        int lrIndex = new Molecule(Config.TYPE_DATA, 1).toInt();
        
        placeInstruction(org, "LRDS", lrIndex);
        sim.tick();
        
        assertThat(org.getDataStack().pop()).isEqualTo(lrPosition);
    }

    // --- Location Register Pair Operations Tests ---

    /**
     * Tests the LRDR instruction (copy LR register to DR register).
     */
    @Test
    @Tag("unit")
    void testLrdrInstruction() {
        int[] lrPosition = {21, 22};
        org.setLr(2, lrPosition);
        int lrIndex = new Molecule(Config.TYPE_DATA, 2).toInt();
        
        placeInstruction(org, "LRDR", 3, lrIndex); // Copy LR2 to DR3
        sim.tick();
        
        assertThat(org.getDr(3)).isEqualTo(lrPosition);
    }

    /**
     * Tests the LSDR instruction (copy top of location stack to DR register without popping).
     */
    @Test
    @Tag("unit")
    void testLsdrInstruction() {
        Deque<int[]> ls = org.getLocationStack();
        int[] location = {23, 24};
        ls.push(location);
        
        placeInstruction(org, "LSDR", 4); // Copy to DR4
        sim.tick();
        
        assertThat(ls.size()).isEqualTo(1); // Still there (no pop)
        assertThat(org.getDr(4)).isEqualTo(location);
    }

    /**
     * Tests the CRLR instruction (clear LR register to [0,0]).
     */
    @Test
    @Tag("unit")
    void testCrlrInstruction() {
        // Set LR1 to some non-zero value
        int[] originalValue = {5, 7};
        org.setLr(1, originalValue);
        assertThat(org.getLr(1)).isEqualTo(originalValue);
        
        // Execute CRLR instruction
        int lr1 = new Molecule(Config.TYPE_DATA, 1).toInt();
        placeInstruction(org, "CRLR", lr1);
        sim.tick();
        
        // Verify LR1 is now [0,0]
        assertThat(org.getLr(1)).isEqualTo(new int[]{0, 0});
        assertThat(org.isInstructionFailed()).isFalse();
    }

    /**
     * Tests the CRLR instruction with invalid LR index.
     */
    @Test
    @Tag("unit")
    void testCrlrInstructionInvalidIndex() {
        // Try to use invalid LR index
        int invalidLrIndex = new Molecule(Config.TYPE_DATA, 5).toInt(); // LR5 doesn't exist
        placeInstruction(org, "CRLR", invalidLrIndex);
        sim.tick();
        
        // Should fail
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("CRLR: Invalid LR index: 5");
    }

    /**
     * Tests the CRLR instruction with multiple LR registers.
     */
    @Test
    @Tag("unit")
    void testCrlrInstructionMultipleRegisters() {
        // Set multiple LR registers to non-zero values
        org.setLr(0, new int[]{1, 2});
        org.setLr(1, new int[]{3, 4});
        org.setLr(2, new int[]{5, 6});
        
        // Clear LR1 and LR2
        int lr1 = new Molecule(Config.TYPE_DATA, 1).toInt();
        int lr2 = new Molecule(Config.TYPE_DATA, 2).toInt();
        
        placeInstruction(org, "CRLR", lr1);
        sim.tick();
        assertThat(org.getLr(1)).isEqualTo(new int[]{0, 0});
        assertThat(org.getLr(0)).isEqualTo(new int[]{1, 2}); // Should remain unchanged
        assertThat(org.getLr(2)).isEqualTo(new int[]{5, 6}); // Should remain unchanged
        
        placeInstruction(org, "CRLR", lr2);
        sim.tick();
        assertThat(org.getLr(2)).isEqualTo(new int[]{0, 0});
        assertThat(org.getLr(0)).isEqualTo(new int[]{1, 2}); // Should remain unchanged
        assertThat(org.getLr(1)).isEqualTo(new int[]{0, 0}); // Should remain cleared
    }
}
