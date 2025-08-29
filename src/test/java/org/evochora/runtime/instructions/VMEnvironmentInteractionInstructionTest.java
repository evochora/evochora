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
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains low-level unit tests for the execution of environment interaction instructions (PEEK, POKE)
 * by the virtual machine. Each test sets up a specific state, executes a single instruction,
 * and verifies the precise outcome on the organism's state and the environment.
 * These tests operate on an in-memory simulation and do not require external resources.
 */
public class VMEnvironmentInteractionInstructionTest {

    private Environment environment;
    private Organism org;
    private Simulation sim;
    private final int[] startPos = new int[]{5, 5};

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{100, 100}, true);
        sim = new Simulation(environment);
        org = Organism.create(sim, startPos, 2000, sim.getLogger());
        sim.addOrganism(org);
    }

    private void placeInstruction(String name) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
    }

    private void placeInstruction(String name, Integer... args) {
        placeInstruction(name);
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment); // CORRECTED
            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), currentPos);
        }
    }

    private void placeInstructionWithVector(String name, int reg, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment); // CORRECTED
        environment.setMolecule(new Molecule(Config.TYPE_DATA, reg), currentPos);
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment); // CORRECTED
            environment.setMolecule(new Molecule(Config.TYPE_DATA, val), currentPos);
        }
    }

    /**
     * Tests the POKE instruction (write from register to location specified by register vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPoke() {
        int[] vec = new int[]{0, 1};
        int payload = new Molecule(Config.TYPE_DATA, 77).toInt();
        org.setDr(0, payload);
        org.setDr(1, vec);

        placeInstruction("POKE", 0, 1);
        int[] targetPos = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED

        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        assertThat(environment.getMolecule(targetPos).toInt()).isEqualTo(payload);
        // POKE(DATA) costs base 1 + 5
        assertThat(org.getEr()).isLessThanOrEqualTo(2000 - 1 - 5);
    }

    /**
     * Tests the POKI instruction (write from register to location specified by immediate vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPoki() {
        int payload = new Molecule(Config.TYPE_DATA, 88).toInt();
        org.setDr(0, payload);

        placeInstruction("POKI", 0, 0, 1);
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED

        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        assertThat(environment.getMolecule(target).toInt()).isEqualTo(payload);
        // POKI(DATA) costs base 1 + 5
        assertThat(org.getEr()).isLessThanOrEqualTo(2000 - 1 - 5);
    }

    /**
     * Tests the POKS instruction (write from stack to location specified by stack vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPoks() {
        int payload = new Molecule(Config.TYPE_DATA, 33).toInt();
        int[] vec = new int[]{0, 1};
        org.getDataStack().push(vec);
        org.getDataStack().push(payload);

        placeInstruction("POKS");
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED

        sim.tick();

        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        assertThat(environment.getMolecule(target).toInt()).isEqualTo(payload);
        // POKS(DATA) costs base 1 + 5
        assertThat(org.getEr()).isLessThanOrEqualTo(2000 - 1 - 5);
    }

    /**
     * Tests the PEEK instruction (read to register from location specified by register vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPeek() {
        org.setDp(0, org.getIp()); // CORRECTED
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
        int payload = new Molecule(Config.TYPE_DATA, 7).toInt();
        environment.setMolecule(Molecule.fromInt(payload), target);

        org.setDr(1, vec);
        placeInstruction("PEEK", 0, 1);
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(payload);
        assertThat(environment.getMolecule(target).isEmpty()).isTrue();
    }

    /**
     * Tests the PEKI instruction (read to register from location specified by immediate vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPeki() {
        org.setDp(0, org.getIp()); // CORRECTED
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
        int payload = new Molecule(Config.TYPE_DATA, 11).toInt();
        environment.setMolecule(Molecule.fromInt(payload), target);

        placeInstructionWithVector("PEKI", 0, vec);
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(payload);
        assertThat(environment.getMolecule(target).isEmpty()).isTrue();
    }

    /**
     * Tests the PEKS instruction (read to stack from location specified by stack vector).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPeks() {
        org.setDp(0, org.getIp()); // CORRECTED
        int[] vec = new int[]{-1, 0};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
        int payload = new Molecule(Config.TYPE_DATA, 9).toInt();
        environment.setMolecule(Molecule.fromInt(payload), target);

        org.getDataStack().push(vec);
        placeInstruction("PEKS");
        sim.tick();

        assertThat(org.getDataStack().pop()).isEqualTo(payload);
        assertThat(environment.getMolecule(target).isEmpty()).isTrue();
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}