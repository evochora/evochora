package org.evochora.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Deque;

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
 * Contains unit tests for the core logic of the {@link Organism} class.
 * These tests verify fundamental aspects of an organism's state, lifecycle,
 * and interaction with its immediate environment within a simulation.
 * These are unit tests and do not require external resources.
 */
public class OrganismTest {

    private Environment environment;
    private Simulation sim;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{100, 100}, true);
        sim = SimulationTestUtils.createSimulation(environment);
    }

    /**
     * Verifies that an organism encountering a non-code cell (e.g., DATA)
     * treats it as a NOP instruction (to be skipped). Non-CODE molecules
     * are auto-skipped by the IP, so they should not cause failures.
     * This is a unit test for runtime type handling.
     */
    @Test
    @Tag("unit")
    void testPlanTickStrictTypingOnNonCodeCell() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        sim.addOrganism(org);
        // Place a DATA symbol at IP - should be treated as NOP (auto-skipped)
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 1), org.getIp());

        Instruction planned = sim.getVirtualMachine().plan(org);
        assertThat(planned).isNotNull();
        // Non-CODE molecules are treated as NOP (will be skipped by skipNopCells)
        assertThat(planned.getName()).isEqualTo("NOP");
        assertThat(org.isInstructionFailed()).isFalse();
    }

    /**
     * Verifies that an organism attempting to execute an unknown opcode
     * correctly enters a failed state.
     * This is a unit test for runtime opcode validation.
     */
    @Test
    @Tag("unit")
    void testPlanTickUnknownOpcodeProducesNop() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        sim.addOrganism(org);
        // Place a CODE opcode that doesn't exist (e.g., 999)
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 999), org.getIp());

        Instruction planned = sim.getVirtualMachine().plan(org);
        assertThat(planned).isNotNull();
        // Planner yields a no-op placeholder with name "UNKNOWN" for unknown opcode
        assertThat(planned.getName()).isEqualTo("UNKNOWN");
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Unknown opcode");
    }

    /**
     * Verifies the basic energy consumption logic, ensuring that an organism
     * with minimal energy dies after executing instructions.
     * This is a unit test for the organism's lifecycle.
     */
    @Test
    @Tag("unit")
    void testEnergyDecreasesAndDeath() {
        // Start with small energy; execute WAIT until dead
        // Use WAIT (not NOP) because NOP is instant-skip
        Organism org = Organism.create(sim, new int[]{0, 0}, 2, sim.getLogger());
        sim.addOrganism(org);
        int waitId = Instruction.getInstructionIdByName("WAIT");
        environment.setMolecule(new Molecule(Config.TYPE_CODE, waitId), new int[]{0, 0});
        environment.setMolecule(new Molecule(Config.TYPE_CODE, waitId), new int[]{1, 0});

        // Two ticks should drain energy to <= 0 and mark dead
        sim.tick();
        sim.tick();

        assertThat(org.isDead()).isTrue();
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Ran out of energy");
    }

    /**
     * Verifies that the organism's Instruction Pointer (IP) correctly advances
     * along its Direction Vector (DV) after each simulation tick.
     * This is a unit test for organism movement.
     */
    @Test
    @Tag("unit")
    void testIpAdvancesAlongDv() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 10, sim.getLogger());
        sim.addOrganism(org);
        // Move along +X
        org.setDv(new int[]{1, 0});
        // Use WAIT (not NOP) because NOP is instant-skip and would advance IP multiple times per tick
        int waitId = Instruction.getInstructionIdByName("WAIT");
        // Place WAIT at [0,0], [1,0], and [2,0] to stop instant-skip loop
        environment.setMolecule(new Molecule(Config.TYPE_CODE, waitId), new int[]{0, 0});
        environment.setMolecule(new Molecule(Config.TYPE_CODE, waitId), new int[]{1, 0});
        environment.setMolecule(new Molecule(Config.TYPE_CODE, waitId), new int[]{2, 0});

        assertThat(org.getIp()).isEqualTo(new int[]{0, 0});
        sim.tick();
        assertThat(org.getIp()).isEqualTo(new int[]{1, 0});
        sim.tick();
        assertThat(org.getIp()).isEqualTo(new int[]{2, 0});
    }

    /**
     * Verifies that the helper method for calculating a target coordinate correctly
     * uses the Data Pointer (DP) as its base.
     * This is a unit test for organism coordinate calculations.
     */
    @Test
    @Tag("unit")
    void testGetTargetCoordinateFromDp() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 100, sim.getLogger());
        sim.addOrganism(org);
        // Set DP to somewhere else to ensure DP is used
        org.setDp(0, new int[]{5, 5});        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment);        assertThat(target).isEqualTo(new int[]{5, 6});
    }

    /**
     * Verifies the LIFO (Last-In, First-Out) behavior of the organism's data stack.
     * This is a unit test for the organism's internal data structures.
     */
    @Test
    @Tag("unit")
    void testDataStackPushPopOrder() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        Deque<Object> ds = org.getDataStack();
        int a = new Molecule(Config.TYPE_DATA, 1).toInt();
        int b = new Molecule(Config.TYPE_DATA, 2).toInt();

        ds.push(a);
        ds.push(b);

        assertThat(ds.pop()).isEqualTo(b);
        assertThat(ds.pop()).isEqualTo(a);
        assertThat(ds.isEmpty()).isTrue();
    }

    /**
     * Verifies the basic set and get functionality for all main register types:
     * Data Registers (DR), Pointer Registers (PR), and Formal Parameter Registers (FPR).
     * This is a unit test for the organism's register state management.
     */
    @Test
    @Tag("unit")
    void testRegisterAccessDrPrFpr() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());

        // DR
        int dataVal = new Molecule(Config.TYPE_DATA, 42).toInt();
        org.setDr(0, dataVal);
        assertThat(org.getDr(0)).isEqualTo(dataVal);

        // PR
        org.setPr(0, dataVal);
        assertThat(org.getPr(0)).isEqualTo(dataVal);

        // FPR
        int[] vec = new int[]{3, 4};
        org.setFpr(0, vec);
        assertThat(org.getFpr(0)).isEqualTo(vec);
    }

    // ==================== Cell Accessibility Tests ====================

    /**
     * Verifies that a cell is accessible only when owned by the organism itself.
     * Parent-owned cells are now treated as foreign (not accessible).
     */
    @Test
    @Tag("unit")
    void testIsCellAccessible_OwnedBySelf_ReturnsTrue() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        sim.addOrganism(org);
        
        assertThat(org.isCellAccessible(org.getId())).isTrue();
    }

    /**
     * Verifies that parent-owned cells are NOT accessible to child organisms.
     * This is a behavioral change: children can no longer access parent molecules.
     */
    @Test
    @Tag("unit")
    void testIsCellAccessible_OwnedByParent_ReturnsFalse() {
        Organism parent = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        sim.addOrganism(parent);
        
        Organism child = Organism.create(sim, new int[]{1, 0}, 100, sim.getLogger());
        child.setParentId(parent.getId());
        sim.addOrganism(child);
        
        // Parent-owned cells should NOT be accessible to child
        assertThat(child.isCellAccessible(parent.getId())).isFalse();
    }

    /**
     * Verifies that cells owned by other organisms are not accessible.
     */
    @Test
    @Tag("unit")
    void testIsCellAccessible_OwnedByOther_ReturnsFalse() {
        Organism org1 = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        Organism org2 = Organism.create(sim, new int[]{1, 0}, 100, sim.getLogger());
        sim.addOrganism(org1);
        sim.addOrganism(org2);
        
        assertThat(org1.isCellAccessible(org2.getId())).isFalse();
    }

    /**
     * Verifies that unowned cells (ownerId=0) are not accessible.
     */
    @Test
    @Tag("unit")
    void testIsCellAccessible_Unowned_ReturnsFalse() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        sim.addOrganism(org);
        
        assertThat(org.isCellAccessible(0)).isFalse();
    }

    // ==================== MR Register Tests ====================

    /**
     * Verifies that the MR register is correctly masked to 4 bits.
     */
    @Test
    @Tag("unit")
    void testMrRegister_MaskedTo4Bits() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        
        org.setMr(15); // Max 4-bit value
        assertThat(org.getMr()).isEqualTo(15);
        
        org.setMr(20); // Exceeds 4 bits: 20 & 0xF = 4
        assertThat(org.getMr()).isEqualTo(4);
        
        org.setMr(0);
        assertThat(org.getMr()).isEqualTo(0);
    }

    /**
     * Verifies that the MR register starts at 0.
     */
    @Test
    @Tag("unit")
    void testMrRegister_DefaultsToZero() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        assertThat(org.getMr()).isEqualTo(0);
    }

    // ==================== Death Cleanup Tests ====================

    /**
     * Verifies that when an organism dies, all its owned cells have their
     * ownership and marker cleared to 0.
     */
    @Test
    @Tag("unit")
    void testDeathClearsOwnershipAndMarker() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 100, sim.getLogger());
        sim.addOrganism(org);
        
        // Place some molecules owned by this organism with marker=5
        org.setMr(5);
        int[] cell1 = new int[]{20, 20};
        int[] cell2 = new int[]{21, 21};
        Molecule mol = new Molecule(Config.TYPE_DATA, 42, org.getMr());
        environment.setMolecule(mol, org.getId(), cell1);
        environment.setMolecule(mol, org.getId(), cell2);
        
        // Verify ownership and marker before death
        assertThat(environment.getOwnerId(cell1)).isEqualTo(org.getId());
        assertThat(environment.getOwnerId(cell2)).isEqualTo(org.getId());
        assertThat(environment.getMolecule(cell1).marker()).isEqualTo(5);
        assertThat(environment.getMolecule(cell2).marker()).isEqualTo(5);
        
        // Clear ownership (simulates what happens when organism dies in tick)
        environment.clearOwnershipFor(org.getId());
        
        // Verify ownership and marker are cleared
        assertThat(environment.getOwnerId(cell1)).isEqualTo(0);
        assertThat(environment.getOwnerId(cell2)).isEqualTo(0);
        assertThat(environment.getMolecule(cell1).marker()).isEqualTo(0);
        assertThat(environment.getMolecule(cell2).marker()).isEqualTo(0);
        
        // Molecule value and type should remain unchanged
        assertThat(environment.getMolecule(cell1).value()).isEqualTo(42);
        assertThat(environment.getMolecule(cell1).type()).isEqualTo(Config.TYPE_DATA);
    }

    // ==================== Stall Recovery Tests ====================

    /**
     * Verifies that when skipNopCells exceeds max-skips with an empty call stack,
     * the IP recovers to the organism's initial position (birth position).
     */
    @Test
    @Tag("unit")
    void testMaxSkipRecoveryToInitialPosition() {
        int[] initialPos = new int[]{50, 50};
        Organism org = Organism.create(sim, initialPos, 100, sim.getLogger());
        sim.addOrganism(org);

        // Move IP away from initial position into empty space
        org.setIp(new int[]{80, 50});

        org.skipNopCells(environment);

        assertThat(org.getIp()).isEqualTo(initialPos);
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Max skips exceeded");
    }

    /**
     * Verifies that when skipNopCells exceeds max-skips with a non-empty call stack,
     * the top frame is popped and the IP is set to the frame's return address.
     */
    @Test
    @Tag("unit")
    void testMaxSkipRecoveryUnwindsCallStack() {
        int[] initialPos = new int[]{50, 50};
        Organism org = Organism.create(sim, initialPos, 100, sim.getLogger());
        sim.addOrganism(org);

        int[] returnAddr = new int[]{60, 50};
        Object[] savedPrs = org.getPrs().toArray(new Object[0]);
        Object[] savedFprs = org.getFprs().toArray(new Object[0]);
        org.getCallStack().push(new Organism.ProcFrame(
                "PROC", returnAddr, new int[]{55, 50},
                savedPrs, savedFprs, java.util.Collections.emptyMap()));

        // Move IP into empty space
        org.setIp(new int[]{80, 50});

        org.skipNopCells(environment);

        assertThat(org.getIp()).isEqualTo(returnAddr);
        assertThat(org.getCallStack()).isEmpty();
        assertThat(org.isInstructionFailed()).isTrue();
    }

    /**
     * Verifies that PRs are restored from the popped call frame during stall recovery,
     * matching the RET instruction's semantics.
     */
    @Test
    @Tag("unit")
    void testMaxSkipRecoveryRestoresPrs() {
        int[] initialPos = new int[]{50, 50};
        Organism org = Organism.create(sim, initialPos, 100, sim.getLogger());
        sim.addOrganism(org);

        // Set known PR values and capture snapshot (caller's state)
        org.setPr(0, 42);
        org.setPr(1, 99);
        Object[] callerPrs = org.getPrs().toArray(new Object[0]);
        Object[] callerFprs = org.getFprs().toArray(new Object[0]);

        // Simulate what a CALL does: push frame with caller's PRs, then change PRs
        org.getCallStack().push(new Organism.ProcFrame(
                "PROC", new int[]{60, 50}, new int[]{55, 50},
                callerPrs, callerFprs, java.util.Collections.emptyMap()));
        org.setPr(0, 777);
        org.setPr(1, 888);

        // Move IP into empty space and trigger recovery
        org.setIp(new int[]{80, 50});
        org.skipNopCells(environment);

        // PRs should be restored to caller's saved values
        assertThat(org.getPr(0)).isEqualTo(42);
        assertThat(org.getPr(1)).isEqualTo(99);
    }

    /**
     * Verifies that the error-penalty-cost is applied when max-skip is exceeded.
     * Uses sim.tick() to exercise the full execution path through Simulation.java
     * where the post-skip penalty check lives.
     */
    @Test
    @Tag("unit")
    void testMaxSkipAppliesErrorPenalty() {
        int startEnergy = 100;
        Organism org = Organism.create(sim, new int[]{50, 50}, startEnergy, sim.getLogger());
        sim.addOrganism(org);
        // Environment is empty (all CODE:0 = NOP), so after NOP execution
        // the IP advances and skipNopCells will hit max-skip.

        sim.tick();

        // Energy should decrease by: base-energy (1) + error-penalty-cost (10)
        assertThat(org.getEr()).isEqualTo(startEnergy - 1 - 10);
        // instructionFailed is still set from max-skip (cleared by resetTickState at next tick)
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Max skips exceeded");
    }

    /**
     * Verifies progressive recovery: with a deep call stack, consecutive max-skip
     * events unwind one frame per tick, eventually falling back to initial position.
     */
    @Test
    @Tag("unit")
    void testMaxSkipProgressiveRecovery() {
        int[] initialPos = new int[]{50, 50};
        Organism org = Organism.create(sim, initialPos, 100, sim.getLogger());
        sim.addOrganism(org);

        // Push two frames (frame 2 on top, frame 1 below)
        int[] returnAddr1 = new int[]{60, 50};
        int[] returnAddr2 = new int[]{70, 50};
        Object[] prs = org.getPrs().toArray(new Object[0]);
        Object[] fprs = org.getFprs().toArray(new Object[0]);

        org.getCallStack().push(new Organism.ProcFrame(
                "PROC1", returnAddr1, new int[]{55, 50},
                prs, fprs, java.util.Collections.emptyMap()));
        org.getCallStack().push(new Organism.ProcFrame(
                "PROC2", returnAddr2, new int[]{65, 50},
                prs, fprs, java.util.Collections.emptyMap()));

        // First max-skip: pops PROC2, IP → returnAddr2
        org.setIp(new int[]{80, 50});
        org.skipNopCells(environment);
        assertThat(org.getIp()).isEqualTo(returnAddr2);
        assertThat(org.getCallStack()).hasSize(1);

        // Second max-skip: pops PROC1, IP → returnAddr1
        org.resetTickState();
        org.skipNopCells(environment);
        assertThat(org.getIp()).isEqualTo(returnAddr1);
        assertThat(org.getCallStack()).isEmpty();

        // Third max-skip: empty stack, IP → initial position
        org.resetTickState();
        org.skipNopCells(environment);
        assertThat(org.getIp()).isEqualTo(initialPos);
    }
}