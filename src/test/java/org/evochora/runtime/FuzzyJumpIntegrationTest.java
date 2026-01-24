package org.evochora.runtime;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for fuzzy jump functionality.
 * <p>
 * These tests verify that the fuzzy label matching works end-to-end,
 * including the critical case of jumps succeeding despite bit mutations
 * in the hash value (within Hamming distance tolerance).
 */
@Tag("integration")
class FuzzyJumpIntegrationTest {

    private Environment environment;
    private Organism org;
    private Simulation sim;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        EnvironmentProperties props = new EnvironmentProperties(new int[]{100}, true);
        environment = new Environment(props);
        sim = SimulationTestUtils.createSimulation(environment);
        org = Organism.create(sim, new int[]{0}, 1000, sim.getLogger());
        sim.addOrganism(org);
    }

    /**
     * Places an instruction at the organism's current IP and advances.
     */
    private void placeInstruction(String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);
            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), currentPos);
        }
    }

    /**
     * Tests that a jump succeeds when the hash matches exactly.
     * This is the baseline case.
     */
    @Test
    void jumpSucceedsWithExactHashMatch() {
        int labelHash = 12345 & Config.VALUE_MASK;
        int[] labelPos = new int[]{50};
        int[] expectedIp = new int[]{51}; // After the LABEL

        // Place LABEL at target position
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash), labelPos);

        // Place NOP after label (so we can verify we landed there)
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("NOP")), expectedIp);

        // Place JMPI with exact hash
        placeInstruction("JMPI", labelHash);

        sim.tick();

        assertThat(org.getIp()).isEqualTo(expectedIp);
        assertThat(org.isInstructionFailed()).isFalse();
    }

    /**
     * Tests that a jump succeeds when the hash has 1 bit flipped.
     * This is the KEY TEST for mutation robustness.
     */
    @Test
    void jumpSucceedsWithSingleBitMutation() {
        int originalHash = 0b10101010101010101010 & Config.VALUE_MASK; // 20-bit pattern
        int mutatedHash = originalHash ^ 1; // Flip bit 0 (Hamming distance = 1)
        int[] labelPos = new int[]{50};
        int[] expectedIp = new int[]{51};

        // Place LABEL with ORIGINAL hash
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, originalHash), labelPos);

        // Place NOP after label
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("NOP")), expectedIp);

        // Place JMPI with MUTATED hash (1 bit different)
        placeInstruction("JMPI", mutatedHash);

        sim.tick();

        // Should still find the label despite 1-bit difference
        assertThat(org.getIp())
                .as("Jump should succeed with Hamming distance 1")
                .isEqualTo(expectedIp);
        assertThat(org.isInstructionFailed()).isFalse();
    }

    /**
     * Tests that a jump succeeds when the hash has 2 bits flipped.
     * The default tolerance is 2, so this should still work.
     */
    @Test
    void jumpSucceedsWithTwoBitMutation() {
        int originalHash = 0b11110000111100001111 & Config.VALUE_MASK;
        int mutatedHash = originalHash ^ 0b11; // Flip bits 0 and 1 (Hamming distance = 2)
        int[] labelPos = new int[]{50};
        int[] expectedIp = new int[]{51};

        // Place LABEL with ORIGINAL hash
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, originalHash), labelPos);

        // Place NOP after label
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("NOP")), expectedIp);

        // Place JMPI with MUTATED hash (2 bits different)
        placeInstruction("JMPI", mutatedHash);

        sim.tick();

        // Should still find the label despite 2-bit difference
        assertThat(org.getIp())
                .as("Jump should succeed with Hamming distance 2")
                .isEqualTo(expectedIp);
        assertThat(org.isInstructionFailed()).isFalse();
    }

    /**
     * Tests that a jump FAILS when the hash has 3 bits flipped.
     * This exceeds the default tolerance of 2.
     */
    @Test
    void jumpFailsWithThreeBitMutation() {
        int originalHash = 0b11110000111100001111 & Config.VALUE_MASK;
        int mutatedHash = originalHash ^ 0b111; // Flip bits 0, 1, 2 (Hamming distance = 3)
        int[] labelPos = new int[]{50};

        // Place LABEL with ORIGINAL hash
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, originalHash), labelPos);

        // Place JMPI with MUTATED hash (3 bits different - beyond tolerance)
        placeInstruction("JMPI", mutatedHash);

        sim.tick();

        // Should fail - no matching label within tolerance
        assertThat(org.isInstructionFailed())
                .as("Jump should fail with Hamming distance 3 (beyond tolerance)")
                .isTrue();
        assertThat(org.getFailureReason()).contains("No matching label");
    }

    /**
     * Tests that own labels are preferred over foreign labels at the same Hamming distance.
     */
    @Test
    void ownLabelPreferredOverForeignWithSameMutation() {
        int labelHash = 54321 & Config.VALUE_MASK;
        int[] ownLabelPos = new int[]{60};
        int[] foreignLabelPos = new int[]{50};
        int[] expectedIp = new int[]{61}; // After OWN label

        int ownerId = org.getId();
        int foreignId = ownerId + 1;

        // Place FOREIGN label first (closer position)
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash), foreignId, foreignLabelPos);

        // Place OWN label (further position, but owned by organism)
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash), ownerId, ownLabelPos);

        // Place NOP after own label
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("NOP")), expectedIp);

        // Place JMPI with exact hash
        placeInstruction("JMPI", labelHash);

        sim.tick();

        // Should jump to OWN label, not foreign (even though foreign is closer)
        assertThat(org.getIp())
                .as("Should prefer own label over foreign label")
                .isEqualTo(expectedIp);
        assertThat(org.isInstructionFailed()).isFalse();
    }

    /**
     * Tests that skipNextInstruction correctly calculates JMPI length in 3D.
     * <p>
     * This is a regression test: JMPI was registered with LABEL operand (implying dims slots)
     * but actually only uses a single IMMEDIATE slot for the hash. When a conditional skips
     * over JMPI in 3D, it incorrectly skips 4 slots (1+3) instead of 2 (1+1).
     */
    @Test
    void conditionalSkipsJmpiCorrectly() {
        // Create a 3D environment to expose the bug
        EnvironmentProperties props3D = new EnvironmentProperties(new int[]{20, 20, 20}, true);
        Environment env3D = new Environment(props3D);
        Simulation sim3D = SimulationTestUtils.createSimulation(env3D);
        Organism org3D = Organism.create(sim3D, new int[]{0, 0, 0}, 1000, sim3D.getLogger());
        sim3D.addOrganism(org3D);

        int labelHash = 99999 & Config.VALUE_MASK;
        int nopOpcode = Instruction.getInstructionIdByName("NOP");

        // Layout in 3D environment along x-axis (y=0, z=0):
        // [0,0,0] IFI r0, 999   <- condition FALSE, skip next instruction
        // [1,0,0] (register 0)
        // [2,0,0] (immediate 999)
        // [3,0,0] JMPI hash     <- should be SKIPPED (length = 2: opcode + hash)
        // [4,0,0] (hash value)
        // [5,0,0] NOP           <- IP should land HERE
        // [6,0,0] NOP           <- if we land here, length was wrong
        // [7,0,0] NOP           <- bug lands here (skipped 4 slots instead of 2)

        int[] pos0 = {0, 0, 0};
        int[] pos1 = {1, 0, 0};
        int[] pos2 = {2, 0, 0};
        int[] pos3 = {3, 0, 0};
        int[] pos4 = {4, 0, 0};
        int[] pos5 = {5, 0, 0};
        int[] pos6 = {6, 0, 0};
        int[] pos7 = {7, 0, 0};

        // Place IFI (If Equal with Immediate)
        int ifiOpcode = Instruction.getInstructionIdByName("IFI");
        env3D.setMolecule(new Molecule(Config.TYPE_CODE, ifiOpcode), pos0);
        env3D.setMolecule(new Molecule(Config.TYPE_DATA, 0), pos1);   // register index 0
        env3D.setMolecule(new Molecule(Config.TYPE_DATA, 999), pos2); // immediate value 999

        // Place JMPI with label hash
        int jmpiOpcode = Instruction.getInstructionIdByName("JMPI");
        env3D.setMolecule(new Molecule(Config.TYPE_CODE, jmpiOpcode), pos3);
        env3D.setMolecule(new Molecule(Config.TYPE_DATA, labelHash), pos4);

        // Place NOPs to detect where IP lands
        env3D.setMolecule(new Molecule(Config.TYPE_CODE, nopOpcode), pos5);
        env3D.setMolecule(new Molecule(Config.TYPE_CODE, nopOpcode), pos6);
        env3D.setMolecule(new Molecule(Config.TYPE_CODE, nopOpcode), pos7);

        org3D.setIp(pos0);

        sim3D.tick();

        // Expected: pos5 (skipped IFI[3] + JMPI[2])
        // Bug: pos7 (skipped IFI[3] + JMPI[4] due to LABEL=3 dims)
        assertThat(org3D.getIp())
                .as("After skipping JMPI in 3D (should be 2 slots, not 4)")
                .isEqualTo(pos5);
    }

    /**
     * Tests that closer labels win when ownership is the same.
     */
    @Test
    void closerLabelWinsWithSameOwnership() {
        int labelHash = 11111 & Config.VALUE_MASK;
        int[] nearLabelPos = new int[]{10};
        int[] farLabelPos = new int[]{80};
        int[] expectedIp = new int[]{11}; // After NEAR label

        // Place both labels with same owner (0 = no specific owner)
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash), nearLabelPos);
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, labelHash), farLabelPos);

        // Place NOP after near label
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("NOP")), expectedIp);

        // Place JMPI at position 0
        placeInstruction("JMPI", labelHash);

        sim.tick();

        // Should jump to CLOSER label
        assertThat(org.getIp())
                .as("Should jump to closer label when ownership is same")
                .isEqualTo(expectedIp);
        assertThat(org.isInstructionFailed()).isFalse();
    }
}
