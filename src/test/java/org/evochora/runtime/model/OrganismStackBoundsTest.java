package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the depth limits the organism enforces on its three stacks: a push below the
 * limit is taken, a push at the limit fails the current instruction and leaves the stack as it was.
 * These tests run on an in-memory simulation and do not require external resources.
 */
public class OrganismStackBoundsTest {

    private Organism org;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        Environment environment = new Environment(new int[]{32, 32}, true);
        Simulation sim = SimulationTestUtils.createSimulation(environment);
        org = Organism.create(sim, new int[]{0, 0}, 1000);
        sim.addOrganism(org);
    }

    private void fillDataStack(int count) {
        int filler = new Molecule(Config.TYPE_DATA, 1).toInt();
        for (int i = 0; i < count; i++) {
            org.getDataStack().push(filler);
        }
    }

    private void fillLocationStack(int count) {
        for (int i = 0; i < count; i++) {
            org.getLocationStack().push(new int[]{i, i});
        }
    }

    private void fillCallStack(int count) {
        for (int i = 0; i < count; i++) {
            org.getCallStack().push(new Organism.ProcFrame(i, new int[]{0, 0}, new int[]{0, 0}, null));
        }
    }

    /**
     * Verifies that a data stack push one below the depth limit is taken and reported as taken.
     */
    @Test
    @Tag("unit")
    void testPushDataOneBelowLimitSucceeds() {
        fillDataStack(Config.DS_MAX_DEPTH - 1);

        assertThat(org.pushData(new Molecule(Config.TYPE_DATA, 7).toInt())).isTrue();
        assertThat(org.getDataStack()).hasSize(Config.DS_MAX_DEPTH);
        assertThat(org.isInstructionFailed()).isFalse();
    }

    /**
     * Verifies that a data stack push at the depth limit is refused, fails the current instruction
     * and leaves the stack depth unchanged.
     */
    @Test
    @Tag("unit")
    void testPushDataAtLimitFails() {
        fillDataStack(Config.DS_MAX_DEPTH);

        assertThat(org.pushData(new Molecule(Config.TYPE_DATA, 7).toInt())).isFalse();
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Data stack overflow");
        assertThat(org.getDataStack()).hasSize(Config.DS_MAX_DEPTH);
    }

    /**
     * Verifies that a location stack push one below the depth limit is taken and reported as taken.
     */
    @Test
    @Tag("unit")
    void testPushLocationOneBelowLimitSucceeds() {
        fillLocationStack(Config.LOCATION_STACK_MAX_DEPTH - 1);

        assertThat(org.pushLocation(new int[]{7, 7})).isTrue();
        assertThat(org.getLocationStack()).hasSize(Config.LOCATION_STACK_MAX_DEPTH);
        assertThat(org.isInstructionFailed()).isFalse();
    }

    /**
     * Verifies that a location stack push at the depth limit is refused, fails the current
     * instruction and leaves the stack depth unchanged.
     */
    @Test
    @Tag("unit")
    void testPushLocationAtLimitFails() {
        fillLocationStack(Config.LOCATION_STACK_MAX_DEPTH);

        assertThat(org.pushLocation(new int[]{7, 7})).isFalse();
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Location stack overflow");
        assertThat(org.getLocationStack()).hasSize(Config.LOCATION_STACK_MAX_DEPTH);
    }

    /**
     * Verifies that a call stack push one below the depth limit is taken and reported as taken.
     */
    @Test
    @Tag("unit")
    void testPushCallFrameOneBelowLimitSucceeds() {
        fillCallStack(Config.CALL_STACK_MAX_DEPTH - 1);

        assertThat(org.pushCallFrame(new Organism.ProcFrame(1, new int[]{0, 0}, new int[]{0, 0}, null))).isTrue();
        assertThat(org.getCallStack()).hasSize(Config.CALL_STACK_MAX_DEPTH);
        assertThat(org.isInstructionFailed()).isFalse();
    }

    /**
     * Verifies that a call stack push at the depth limit is refused, fails the current instruction
     * and leaves the stack depth unchanged.
     */
    @Test
    @Tag("unit")
    void testPushCallFrameAtLimitFails() {
        fillCallStack(Config.CALL_STACK_MAX_DEPTH);

        assertThat(org.pushCallFrame(new Organism.ProcFrame(1, new int[]{0, 0}, new int[]{0, 0}, null))).isFalse();
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Call stack overflow");
        assertThat(org.getCallStack()).hasSize(Config.CALL_STACK_MAX_DEPTH);
    }
}
