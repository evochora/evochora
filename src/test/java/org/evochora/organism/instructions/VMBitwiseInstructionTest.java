package org.evochora.organism.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class VMBitwiseInstructionTest {

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

    private void placeInstruction(String name, int reg, int immediateValue) {
        placeInstruction(name);
        int[] currentPos = org.getIp();
        currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment); // CORRECTED
        environment.setMolecule(new Molecule(Config.TYPE_DATA, reg), currentPos);
        currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment); // CORRECTED
        environment.setMolecule(Molecule.fromInt(immediateValue), currentPos);
    }

    @Test
    void testAnds() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
        placeInstruction("ANDS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1000).toInt());
    }

    @Test
    void testAndi() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("ANDI", 0, 0b1100);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1000).toInt());
    }

    @Test
    void testAndr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0b1100).toInt());
        placeInstruction("ANDR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1000).toInt());
    }

    @Test
    void testOrs() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
        placeInstruction("ORS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1110).toInt());
    }

    @Test
    void testOri() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("ORI", 0, 0b0101);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1111).toInt());
    }

    @Test
    void testOrr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0b0101).toInt());
        placeInstruction("ORR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1111).toInt());
    }

    @Test
    void testXors() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
        placeInstruction("XORS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 0b0110).toInt());
    }
    @Test
    void testXori() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("XORI", 0, 0b0110);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
    }

    @Test
    void testXorr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0b0110).toInt());
        placeInstruction("XORR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
    }

    @Test
    void testNot() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("NOT", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, ~0b1010).toInt());
    }

    @Test
    void testNots() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("NOTS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, ~0b1010).toInt());
    }

    @Test
    void testShiftRegisterVariants() {
        // SHLR / SHRR
        org.setDr(0, new Molecule(Config.TYPE_DATA, 1).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 3).toInt());
        placeInstruction("SHLR", 0, 1);
        sim.tick();
        int valL = Molecule.fromInt((Integer) org.getDr(0)).toScalarValue();
        assertThat(valL).isEqualTo(1 << 3);

        org.setDr(0, new Molecule(Config.TYPE_DATA, 8).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("SHRR", 0, 1);
        sim.tick();
        int valR = Molecule.fromInt((Integer) org.getDr(0)).toScalarValue();
        assertThat(valR).isEqualTo(8 >> 2);
    }
}