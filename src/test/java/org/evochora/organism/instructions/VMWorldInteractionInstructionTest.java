package org.evochora.organism.instructions;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.World;
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
        world.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
    }

    private void placeInstruction(String name, Integer... args) {
        placeInstruction(name);
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
            world.setMolecule(new Molecule(Config.TYPE_DATA, arg), currentPos);
        }
    }

    // Helper für Instruktionen mit Register + Vektor-Argument (z. B. PEKI)
    private void placeInstructionWithVector(String name, int reg, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        world.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
        world.setMolecule(new Molecule(Config.TYPE_DATA, reg), currentPos);
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
            world.setMolecule(new Molecule(Config.TYPE_DATA, val), currentPos);
        }
    }

    @Test
    void testPoke() {
        int[] vec = new int[]{0, 1};
        int payload = new Molecule(Config.TYPE_DATA, 77).toInt();
        org.setDr(0, payload);
        org.setDr(1, vec);

        placeInstruction("POKE", 0, 1);
        int[] targetPos = org.getTargetCoordinate(org.getDp(), vec, world);

        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        assertThat(world.getMolecule(targetPos).toInt()).isEqualTo(payload);
        assertThat(org.getEr()).isLessThanOrEqualTo(2000 - 77 - 1);
    }

    @Test
    void testPoki() {
        int payload = new Molecule(Config.TYPE_DATA, 88).toInt();
        org.setDr(0, payload);

        placeInstruction("POKI", 0, 0, 1);
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);

        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        assertThat(world.getMolecule(target).toInt()).isEqualTo(payload);
        assertThat(org.getEr()).isLessThanOrEqualTo(2000 - 88 - 1);
    }

    @Test
    void testPoks() {
        int payload = new Molecule(Config.TYPE_DATA, 33).toInt();
        int[] vec = new int[]{0, 1};
        org.getDataStack().push(vec);
        org.getDataStack().push(payload);

        placeInstruction("POKS");
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);

        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        assertThat(world.getMolecule(target).toInt()).isEqualTo(payload);
        assertThat(org.getEr()).isLessThanOrEqualTo(2000 - 33 - 1);
    }

    // --- HIER BEGINNEN DIE VERSCHOBENEN TESTS ---

    @Test
    void testPeek() {
        org.setDp(org.getIp());
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
        int payload = new Molecule(Config.TYPE_DATA, 7).toInt();
        world.setMolecule(Molecule.fromInt(payload), target);

        org.setDr(1, vec);
        placeInstruction("PEEK", 0, 1);
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(payload);
        // Zelle sollte geleert sein
        assertThat(world.getMolecule(target).isEmpty()).isTrue();
    }

    @Test
    void testPeki() {
        org.setDp(org.getIp());
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
        int payload = new Molecule(Config.TYPE_DATA, 11).toInt();
        world.setMolecule(Molecule.fromInt(payload), target);

        placeInstructionWithVector("PEKI", 0, vec);
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(payload);
        assertThat(world.getMolecule(target).isEmpty()).isTrue();
    }

    @Test
    void testPeks() {
        org.setDp(org.getIp());
        int[] vec = new int[]{-1, 0};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
        int payload = new Molecule(Config.TYPE_DATA, 9).toInt();
        world.setMolecule(Molecule.fromInt(payload), target);

        org.getDataStack().push(vec);
        placeInstruction("PEKS");
        sim.tick();

        assertThat(org.getDataStack().pop()).isEqualTo(payload);
        assertThat(world.getMolecule(target).isEmpty()).isTrue();
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}