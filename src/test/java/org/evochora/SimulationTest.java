package org.evochora;

import org.evochora.Config;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SimulationTest {

    private World world;
    private Simulation sim;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        world = new World(new int[]{50, 50}, true);
        sim = new Simulation(world);
    }

    // Helper to place a single instruction with its integer DATA args along +X from org's IP
    private void placeInstruction(Organism org, String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        int[] pos = org.getIp();
        world.setSymbol(new Symbol(Config.TYPE_CODE, opcode), pos);
        int[] cur = pos;
        for (int arg : args) {
            cur = org.getNextInstructionPosition(cur, world, org.getDv());
            world.setSymbol(new Symbol(Config.TYPE_DATA, arg), cur);
        }
    }

    // Compute absolute target for a DP-relative vector
    private int[] targetFromDp(Organism org, int[] vec) {
        return org.getTargetCoordinate(org.getDp(), vec, world);
    }

    @Test
    void testConflictResolutionSameTargetLowerIdWins() {
        // Two organisms, both POKI to the same DP-adjacent cell [0,1]
        Organism orgLow = Organism.create(sim, new int[]{0, 0}, 2000, sim.getLogger());
        orgLow.setDv(new int[]{1, 0});
        orgLow.setDp(new int[]{0, 0});
        int payloadLow = new Symbol(Config.TYPE_DATA, 11).toInt();
        orgLow.setDr(0, payloadLow);

        Organism orgHigh = Organism.create(sim, new int[]{10, 0}, 2000, sim.getLogger());
        orgHigh.setDv(new int[]{1, 0});
        orgHigh.setDp(new int[]{0, 0});
        int payloadHigh = new Symbol(Config.TYPE_DATA, 22).toInt();
        orgHigh.setDr(0, payloadHigh);

        sim.addOrganism(orgLow);
        sim.addOrganism(orgHigh);

        // POKI expects: regId, then vector components
        placeInstruction(orgLow, "POKI", 0, 0, 1);
        placeInstruction(orgHigh, "POKI", 0, 0, 1);

        // Both target DP + [0,1]
        int[] target = targetFromDp(orgLow, new int[]{0, 1});

        sim.tick();

        // Lower ID organism should win and write its payload
        assertThat(world.getSymbol(target).toInt()).isEqualTo(payloadLow);
        // Loser should not be executed (no base cost nor payload energy taken)
        assertThat(orgHigh.getEr()).isEqualTo(2000);
        // Winner's energy decreased by at least payload + base cost
        assertThat(orgLow.getEr()).isLessThanOrEqualTo(2000 - 11 - 1);
        // No failures expected
        assertThat(orgLow.isInstructionFailed()).as("Winner failed: " + orgLow.getFailureReason()).isFalse();
        assertThat(orgHigh.isInstructionFailed()).as("Loser failed: " + orgHigh.getFailureReason()).isFalse();
    }

    @Test
    void testNoConflictDifferentTargetsBothExecute() {
        Organism o1 = Organism.create(sim, new int[]{0, 0}, 2000, sim.getLogger());
        o1.setDv(new int[]{1, 0});
        o1.setDp(new int[]{0, 0});
        int v1 = new Symbol(Config.TYPE_DATA, 5).toInt();
        o1.setDr(0, v1);

        Organism o2 = Organism.create(sim, new int[]{10, 0}, 2000, sim.getLogger());
        o2.setDv(new int[]{1, 0});
        o2.setDp(new int[]{1, 0}); // Different DP to avoid same target
        int v2 = new Symbol(Config.TYPE_DATA, 7).toInt();
        o2.setDr(0, v2);

        sim.addOrganism(o1);
        sim.addOrganism(o2);

        placeInstruction(o1, "POKI", 0, 0, 1); // target [0,1]
        placeInstruction(o2, "POKI", 0, 0, 1); // target [1,1]

        int[] t1 = targetFromDp(o1, new int[]{0, 1});
        int[] t2 = targetFromDp(o2, new int[]{0, 1});

        sim.tick();

        assertThat(world.getSymbol(t1).toInt()).isEqualTo(v1);
        assertThat(world.getSymbol(t2).toInt()).isEqualTo(v2);
        assertThat(o1.isInstructionFailed()).as("o1 failed: " + o1.getFailureReason()).isFalse();
        assertThat(o2.isInstructionFailed()).as("o2 failed: " + o2.getFailureReason()).isFalse();
    }

    @Test
    void testSingleOrganismNoTargetStillExecutes() {
        // POKS requires a value and a vector on the stack; provide none so getTargetCoordinates is empty
        Organism org = Organism.create(sim, new int[]{0, 0}, 2000, sim.getLogger());
        org.setDv(new int[]{1, 0});
        org.setDp(new int[]{0, 0});
        sim.addOrganism(org);

        placeInstruction(org, "POKS");

        // With only one organism, Simulation should allow execution attempt
        sim.tick();

        // The instruction will fail due to missing operands, proving it was executed
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("world interaction");
    }
}
