package org.evochora.organism.instructions;

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

public class VMStateInstructionTest {

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
        org = Organism.create(sim, startPos, 1000, sim.getLogger());
        sim.addOrganism(org);
    }

    private void placeInstruction(String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
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

    private void placeInstructionWithVectorOnly(String name, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment); // CORRECTED
            environment.setMolecule(new Molecule(Config.TYPE_DATA, val), currentPos);
        }
    }

    @Test
    void testTurn() {
        int[] vec = new int[]{1, 0};
        org.setDr(0, vec);
        placeInstruction("TURN", 0);
        sim.tick();
        assertThat(org.getDv()).isEqualTo(vec);
    }

    @Test
    void testSync() {
        int[] expected = org.getIp();
        placeInstruction("SYNC");
        sim.tick();
        assertThat(org.getDp(0)).isEqualTo(expected); // CORRECTED
    }

    @Test
    void testNrg() {
        placeInstruction("NRG", 0);
        sim.tick();
        int er = org.getEr();
        int regVal = (Integer) org.getDr(0);
        assertThat(Molecule.fromInt(regVal).toScalarValue()).isEqualTo(er);
    }

    @Test
    void testNrgs() {
        placeInstruction("NRGS");
        sim.tick();
        int val = (Integer) org.getDataStack().pop();
        assertThat(Molecule.fromInt(val).toScalarValue()).isEqualTo(org.getEr());
    }

    @Test
    void testDiff() {
        org.setDp(0, org.getIp()); // CORRECTED
        placeInstruction("DIFF", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new int[]{0, 0});
    }

    @Test
    void testPos() {
        placeInstruction("POS", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new int[]{0,0});
    }

    @Test
    void testRand() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        placeInstruction("RAND", 0);
        sim.tick();
        int val = Molecule.fromInt((Integer) org.getDr(0)).toScalarValue();
        assertThat(val).isGreaterThanOrEqualTo(0).isLessThan(10);
    }

    @Test
    void testSeek() {
        org.setDp(0, org.getIp()); // CORRECTED
        int[] vec = new int[]{0, 1};
        org.setDr(0, vec);
        int[] expected = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
        placeInstruction("SEEK", 0);
        sim.tick();
        assertThat(org.getDp(0)).isEqualTo(expected); // CORRECTED
    }

    @Test
    void testSeki() {
        org.setDp(0, org.getIp()); // CORRECTED
        int[] vec = new int[]{0, 1};
        int[] expected = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
        placeInstructionWithVectorOnly("SEKI", vec);
        sim.tick();
        assertThat(org.getDp(0)).isEqualTo(expected); // CORRECTED
    }

    @Test
    void testSeks() {
        org.setDp(0, org.getIp()); // CORRECTED
        int[] vec = new int[]{-1, 0};
        int[] expected = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
        org.getDataStack().push(vec);
        placeInstruction("SEKS");
        sim.tick();
        assertThat(org.getDp(0)).isEqualTo(expected); // CORRECTED
    }

    @Test
    void testScan() {
        org.setDp(0, org.getIp()); // CORRECTED
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
        int payload = new Molecule(Config.TYPE_STRUCTURE, 3).toInt();
        environment.setMolecule(Molecule.fromInt(payload), target);

        org.setDr(1, vec);
        placeInstruction("SCAN", 0, 1);
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(payload);
        assertThat(environment.getMolecule(target).toInt()).isEqualTo(payload);
    }

    @Test
    void testScni() {
        org.setDp(0, org.getIp()); // CORRECTED
        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
        int payload = new Molecule(Config.TYPE_CODE, 42).toInt();
        environment.setMolecule(Molecule.fromInt(payload), target);

        placeInstructionWithVector("SCNI", 0, vec);
        sim.tick();

        assertThat(org.getDr(0)).isEqualTo(payload);
        assertThat(environment.getMolecule(target).toInt()).isEqualTo(payload);
    }

    @Test
    void testScns() {
        org.setDp(0, org.getIp()); // CORRECTED
        int[] vec = new int[]{-1, 0};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
        int payload = new Molecule(Config.TYPE_ENERGY, 5).toInt();
        environment.setMolecule(Molecule.fromInt(payload), target);

        org.getDataStack().push(vec);
        placeInstruction("SCNS");
        sim.tick();

        assertThat(org.getDataStack().pop()).isEqualTo(payload);
        assertThat(environment.getMolecule(target).toInt()).isEqualTo(payload);
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}