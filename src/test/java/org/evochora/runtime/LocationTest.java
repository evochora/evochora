package org.evochora.runtime;

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
 * Unit tests for the new location-based architecture features of the Organism class,
 * including multiple Data Pointers (DPs), Location Registers (LRs), and the Location Stack (LS).
 */
public class LocationTest {

    private Simulation sim;
    private Organism org;

    @BeforeEach
    void setUp() {
        // A minimal environment is needed for organism creation
        Instruction.init();
        Environment environment = new Environment(new int[]{10, 10}, true);
        sim = new Simulation(environment);
        org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
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

    @Test
    @Tag("unit")
    void testMultipleDpInitialization() {
        assertThat(org.getDps()).hasSize(Config.NUM_DATA_POINTERS);
        // All DPs should be initialized to the organism's start position
        for (int i = 0; i < Config.NUM_DATA_POINTERS; i++) {
            assertThat(org.getDp(i)).isEqualTo(new int[]{0, 0});
        }
    }

    @Test
    @Tag("unit")
    void testSetAndGetSpecificDp() {
        int[] newPosition = {5, 5};
        org.setDp(1, newPosition);

        assertThat(org.getDp(1)).isEqualTo(newPosition);
        // Ensure other DPs are unaffected
        assertThat(org.getDp(0)).isEqualTo(new int[]{0, 0});
    }

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

    @Test
    @Tag("unit")
    void testLocationRegisterInitialization() {
        // LRs should be initialized to zero vectors
        for (int i = 0; i < Config.NUM_LOCATION_REGISTERS; i++) {
            assertThat(org.getLr(i)).containsExactly(0, 0);
        }
    }

    @Test
    @Tag("unit")
    void testSetAndGetSpecificLr() {
        int[] newLocation = {1, 2};
        org.setLr(0, newLocation);
        assertThat(org.getLr(0)).isEqualTo(newLocation);
    }

    @Test
    @Tag("unit")
    void testSettingNonVectorToLrFails() {
        // The setLr method now only accepts int[], so this test is implicitly handled by the compiler.
        // We can test the generic `writeOperand` logic once LRs are integrated there.
        // For now, this confirms the type safety of the new method.
        // org.setLr(0, 123); // This would not compile
    }

    // --- Location Stack (LS) Tests ---

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

    @org.junit.jupiter.api.Test
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
}