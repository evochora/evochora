package org.evochora;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@link Simulation} class, focusing on conflict resolution and instruction execution.
 * These tests use an in-memory {@link Environment} and do not require any external resources like
 * a database or filesystem.
 */
public class SimulationTest {

    private Environment environment;
    private Simulation sim;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{50, 50}, true);
        sim = SimulationTestUtils.createSimulation(environment);
    }

    private void placeInstruction(Organism org, String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        int[] pos = org.getIp();
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), pos);
        int[] cur = pos;
        for (int arg : args) {
            cur = org.getNextInstructionPosition(cur, org.getDv(), environment);            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), cur);
        }
    }

    private int[] targetFromDp(Organism org, int[] vec) {
        return org.getTargetCoordinate(org.getDp(0), vec, environment);    }

    /**
     * Tests conflict resolution when two organisms target the same location.
     * The test verifies that the organism with the lower ID successfully writes its data,
     * while the other organism's write is ignored.
     * This is a unit test and relies on the in-memory {@link Simulation} and {@link Environment}.
     */
    @Test
    @Tag("unit")
    void testConflictResolutionSameTargetLowerIdWins() {
        Organism orgLow = Organism.create(sim, new int[]{0, 0}, 2000, sim.getLogger());
        orgLow.setDv(new int[]{1, 0});
        orgLow.setDp(0, new int[]{0, 0});        int payloadLow = new Molecule(Config.TYPE_DATA, 11).toInt();
        orgLow.setDr(0, payloadLow);

        Organism orgHigh = Organism.create(sim, new int[]{10, 0}, 2000, sim.getLogger());
        orgHigh.setDv(new int[]{1, 0});
        orgHigh.setDp(0, new int[]{0, 0});        int payloadHigh = new Molecule(Config.TYPE_DATA, 22).toInt();
        orgHigh.setDr(0, payloadHigh);

        sim.addOrganism(orgLow);
        sim.addOrganism(orgHigh);

        placeInstruction(orgLow, "POKI", 0, 0, 1);
        placeInstruction(orgHigh, "POKI", 0, 0, 1);

        int[] target = targetFromDp(orgLow, new int[]{0, 1});

        sim.tick();

        assertThat(environment.getMolecule(target).toInt()).isEqualTo(payloadLow);
        assertThat(orgHigh.getEr()).isEqualTo(2000);
        // POKI(DATA) costs 6 energy (no base cost, all in one)
        assertThat(orgLow.getEr()).isLessThanOrEqualTo(2000 - 6);
        assertThat(orgLow.isInstructionFailed()).as("Winner failed: " + orgLow.getFailureReason()).isFalse();
        assertThat(orgHigh.isInstructionFailed()).as("Loser failed: " + orgHigh.getFailureReason()).isFalse();
    }

    /**
     * Tests that two organisms can execute instructions on different targets without conflict.
     * The test verifies that both organisms successfully write their data to their respective targets.
     * This is a unit test and relies on the in-memory {@link Simulation} and {@link Environment}.
     */
    @Test
    @Tag("unit")
    void testNoConflictDifferentTargetsBothExecute() {
        Organism o1 = Organism.create(sim, new int[]{0, 0}, 2000, sim.getLogger());
        o1.setDv(new int[]{1, 0});
        o1.setDp(0, new int[]{0, 0});        int v1 = new Molecule(Config.TYPE_DATA, 5).toInt();
        o1.setDr(0, v1);

        Organism o2 = Organism.create(sim, new int[]{10, 0}, 2000, sim.getLogger());
        o2.setDv(new int[]{1, 0});
        o2.setDp(0, new int[]{1, 0});        int v2 = new Molecule(Config.TYPE_DATA, 7).toInt();
        o2.setDr(0, v2);

        sim.addOrganism(o1);
        sim.addOrganism(o2);

        placeInstruction(o1, "POKI", 0, 0, 1);
        placeInstruction(o2, "POKI", 0, 0, 1);

        int[] t1 = targetFromDp(o1, new int[]{0, 1});
        int[] t2 = targetFromDp(o2, new int[]{0, 1});

        sim.tick();

        assertThat(environment.getMolecule(t1).toInt()).isEqualTo(v1);
        assertThat(environment.getMolecule(t2).toInt()).isEqualTo(v2);
        assertThat(o1.isInstructionFailed()).as("o1 failed: " + o1.getFailureReason()).isFalse();
        assertThat(o2.isInstructionFailed()).as("o2 failed: " + o2.getFailureReason()).isFalse();
    }

    /**
     * Tests that an instruction fails gracefully when its operands are invalid.
     * In this case, the 'POKS' instruction is executed without valid operands,
     * and the test verifies that the organism's state reflects the failure.
     * This is a unit test and relies on the in-memory {@link Simulation} and {@link Environment}.
     */
    @Test
    @Tag("unit")
    void testSingleOrganismNoTargetStillExecutes() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 2000, sim.getLogger());
        org.setDv(new int[]{1, 0});
        org.setDp(0, new int[]{0, 0});        sim.addOrganism(org);

        placeInstruction(org, "POKS");

        sim.tick();

        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Invalid operands for POKS");
    }
}