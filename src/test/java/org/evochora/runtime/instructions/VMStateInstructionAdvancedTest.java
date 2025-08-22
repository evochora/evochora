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

import static org.assertj.core.api.Assertions.assertThat;

public class VMStateInstructionAdvancedTest {

    private Environment environment;
    private Simulation sim;
    private Organism org;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{50, 50}, true);
        sim = new Simulation(environment);
        org = Organism.create(sim, new int[]{10, 10}, 1000, sim.getLogger());
        sim.addOrganism(org);
    }

    private void placeInstruction(String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);
            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), currentPos);
        }
    }

    // RBIT tests
    @Test
    void testRbirSelectsSingleBitSubsetOfSource() {
        int sourceMask = new Molecule(Config.TYPE_DATA, (1 << 0) | (1 << 2) | (1 << 3)).toInt();
        org.setDr(1, sourceMask);
        placeInstruction("RBIR", 0, 1); // %DR0, %DR1
        sim.tick();
        int resultPacked = (Integer) org.getDr(0);
        Molecule result = Molecule.fromInt(resultPacked);
        int val = result.toScalarValue();
        // Must be power of two and subset of source
        assertThat(Integer.bitCount(val)).isEqualTo(val == 0 ? 0 : 1);
        assertThat((((1 << 0) | (1 << 2) | (1 << 3)) & val)).isEqualTo(val);
    }

    // SPNP (Scan Passable Neighbors) tests
    @Test
    void testSpnrAllBlockedReturnsZero() {
        // Place non-empty, foreign-owned molecules around DP to block
        int[] dp = org.getActiveDp();
        // +X
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 1), new int[]{dp[0] + 1, dp[1]});
        environment.setOwnerId(9999, dp[0] + 1, dp[1]);
        // -X
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 1), new int[]{dp[0] - 1, dp[1]});
        environment.setOwnerId(9999, dp[0] - 1, dp[1]);
        // +Y
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 1), new int[]{dp[0], dp[1] + 1});
        environment.setOwnerId(9999, dp[0], dp[1] + 1);
        // -Y
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 1), new int[]{dp[0], dp[1] - 1});
        environment.setOwnerId(9999, dp[0], dp[1] - 1);

        placeInstruction("SPNR", 0);
        sim.tick();
        int resultPacked = (Integer) org.getDr(0);
        assertThat(Molecule.fromInt(resultPacked).toScalarValue()).isEqualTo(0);
    }

    @Test
    void testSpnsDetectsEmptyNeighbors() {
        // Ensure +X and -Y are empty, others blocked
        int[] dp = org.getActiveDp();
        // Block -X and +Y with foreign owners
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 1), new int[]{dp[0] - 1, dp[1]});
        environment.setOwnerId(9999, dp[0] - 1, dp[1]);
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 1), new int[]{dp[0], dp[1] + 1});
        environment.setOwnerId(9999, dp[0], dp[1] + 1);
        // +X empty (no set), -Y empty

        placeInstruction("SPNS");
        sim.tick();
        Object top = org.getDataStack().pop();
        int mask = Molecule.fromInt((Integer) top).toScalarValue();
        // Expect bits: +X => bit0, -Y => bit3
        int expected = (1 << 0) | (1 << 3);
        assertThat(mask & expected).isEqualTo(expected);
    }

    @Test
    void testRbiiZeroMaskProducesZero() {
        int zero = new Molecule(Config.TYPE_DATA, 0).toInt();
        placeInstruction("RBII", 0, zero); // %DR0, DATA:0
        sim.tick();
        int resultPacked = (Integer) org.getDr(0);
        Molecule result = Molecule.fromInt(resultPacked);
        assertThat(result.toScalarValue()).isEqualTo(0);
    }

    @Test
    void testRbisStackVariant() {
        int maskVal = new Molecule(Config.TYPE_DATA, (1 << 1) | (1 << 4)).toInt();
        org.getDataStack().push(maskVal);
        placeInstruction("RBIS");
        sim.tick();
        Object top = org.getDataStack().pop();
        Molecule m = Molecule.fromInt((Integer) top);
        int v = m.toScalarValue();
        assertThat(Integer.bitCount(v)).isIn(0, 1);
        assertThat((((1 << 1) | (1 << 4)) & v)).isEqualTo(v);
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}


