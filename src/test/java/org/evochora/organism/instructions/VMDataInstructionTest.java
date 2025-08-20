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

public class VMDataInstructionTest {

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
            environment.setMolecule(Molecule.fromInt(arg), currentPos);
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

    @Test
    void testSeti() {
        int immediateValue = new Molecule(Config.TYPE_DATA, 123).toInt();
        placeInstruction("SETI", 0, immediateValue);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(immediateValue);
    }

    @Test
    void testSetr() {
        int srcValue = new Molecule(Config.TYPE_DATA, 456).toInt();
        org.setDr(1, srcValue);
        placeInstruction("SETR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(srcValue);
    }

    @Test
    void testSetv() {
        int[] vec = new int[]{3, 4};
        placeInstructionWithVector("SETV", 0, vec);
        sim.tick();
        Object reg0 = org.getDr(0);
        assertThat(reg0).isInstanceOf(int[].class);
        assertThat((int[]) reg0).containsExactly(vec);
    }

    @Test
    void testPush() {
        int value = new Molecule(Config.TYPE_DATA, 789).toInt();
        org.setDr(0, value);
        placeInstruction("PUSH", 0);
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(value);
    }

    @Test
    void testPop() {
        int value = new Molecule(Config.TYPE_DATA, 321).toInt();
        org.getDataStack().push(value);
        placeInstruction("POP", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(value);
    }

    @Test
    void testPusi() {
        int literal = new Molecule(Config.TYPE_DATA, 42).toInt();
        placeInstruction("PUSI", literal);
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(literal);
    }
}