package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class VMWorldInteractionInstructionTest {

    private World world;
    private Organism org;
    private Simulation sim;
    private final int[] startPos = new int[]{5, 5};

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        world = new World(new int[]{100, 100}, true);
        sim = new Simulation(world);
        org = Organism.create(sim, startPos, 2000, sim.getLogger());
        sim.addOrganism(org);
    }

    private void placeInstruction(String name) {
        int opcode = Instruction.getInstructionIdByName(name);
        world.setSymbol(new Symbol(Config.TYPE_CODE, opcode), org.getIp());
    }

    private void placeInstruction(String name, Integer... args) {
        placeInstruction(name);
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
            world.setSymbol(new Symbol(Config.TYPE_DATA, arg), currentPos);
        }
    }

    @Test
    void testPoke() {
        int[] vec = new int[]{0, 1};
        int payload = new Symbol(Config.TYPE_DATA, 77).toInt();
        org.setDr(0, payload);
        org.setDr(1, vec);

        // Place single POKE with (value reg, vector reg)
        placeInstruction("POKE", 0, 1);

        // Expected target is DP + vec
        int[] targetPos = org.getTargetCoordinate(org.getDp(), vec, world);

        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();

        assertThat(world.getSymbol(targetPos).toInt()).isEqualTo(payload);
        assertThat(org.getEr()).isLessThanOrEqualTo(2000 - 77 - 1);
    }

    @Test
    void testPoki() {
        int payload = new Symbol(Config.TYPE_DATA, 88).toInt();
        org.setDr(0, payload);

        // Register + unit vector components (0,1)
        placeInstruction("POKI", 0, 0, 1);

        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);

        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();

        assertThat(world.getSymbol(target).toInt()).isEqualTo(payload);
        assertThat(org.getEr()).isLessThanOrEqualTo(2000 - 88 - 1);
    }

    @Test
    void testPoks() {
        int payload = new Symbol(Config.TYPE_DATA, 33).toInt();
        int[] vec = new int[]{0, 1}; // unit vector orthogonal to DIR
        // Push vector first, then value so top is value
        org.getDataStack().push(vec);
        org.getDataStack().push(payload);

        placeInstruction("POKS");

        int[] vecTarget = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vecTarget, world);

        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();

        assertThat(world.getSymbol(target).toInt()).isEqualTo(payload);
        assertThat(org.getEr()).isLessThanOrEqualTo(2000 - 33 - 1);
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}
