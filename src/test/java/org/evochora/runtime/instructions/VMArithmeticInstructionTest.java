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

public class VMArithmeticInstructionTest {

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
        environment.setMolecule(new Molecule(Config.TYPE_DATA, immediateValue), currentPos);
    }

    // --- ADD ---
    @Test
    @Tag("unit")
    void testAddi() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        placeInstruction("ADDI", 0, 5);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 15).toInt());
    }

    @Test
    @Tag("unit")
    void testAddr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 3).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 4).toInt());
        placeInstruction("ADDR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    @Test
    @Tag("unit")
    void testAdds() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 3).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 4).toInt());
        placeInstruction("ADDS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    // --- SUB ---
    @Test
    @Tag("unit")
    void testSubi() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        placeInstruction("SUBI", 0, 3);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    @Test
    @Tag("unit")
    void testSubr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());
        placeInstruction("SUBR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 4).toInt());
    }

    @Test
    @Tag("unit")
    void testSubs() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 3).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 10).toInt());
        placeInstruction("SUBS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    // --- MUL ---
    @Test
    @Tag("unit")
    void testMuli() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 7).toInt());
        placeInstruction("MULI", 0, 6);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    @Test
    @Tag("unit")
    void testMulr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 7).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());
        placeInstruction("MULR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    @Test
    @Tag("unit")
    void testMuls() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 7).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 6).toInt());
        placeInstruction("MULS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    // --- DIV ---
    @Test
    @Tag("unit")
    void testDivi() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 42).toInt());
        placeInstruction("DIVI", 0, 6);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    @Test
    @Tag("unit")
    void testDivr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 42).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());
        placeInstruction("DIVR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    @Test
    @Tag("unit")
    void testDivs() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 6).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 42).toInt());
        placeInstruction("DIVS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    // --- MOD ---
    @Test
    @Tag("unit")
    void testModi() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 43).toInt());
        placeInstruction("MODI", 0, 6);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    @Tag("unit")
    void testModr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 43).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());
        placeInstruction("MODR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    @Tag("unit")
    void testMods() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 6).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 43).toInt());
        placeInstruction("MODS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    // --- Vector arithmetic (register variants only) ---
    @Test
    @Tag("unit")
    void testAddrVector() {
        int[] v1 = new int[]{1, 2};
        int[] v2 = new int[]{3, 4};
        org.setDr(0, v1);
        org.setDr(1, v2);
        placeInstruction("ADDR", 0, 1);
        sim.tick();
        Object r0 = org.getDr(0);
        assertThat(r0).isInstanceOf(int[].class);
        assertThat((int[]) r0).containsExactly(4, 6);
    }

    @Test
    @Tag("unit")
    void testDotAndCrossProducts() {
        // Prepare vectors
        int[] v1 = new int[]{2, 3};
        int[] v2 = new int[]{4, -1};
        // DOTR
        org.setDr(0, 0); // dest
        org.setDr(1, v1);
        org.setDr(2, v2);
        placeInstruction("DOTR", 0, 1, 2);
        sim.tick();
        int dot = Molecule.fromInt((Integer) org.getDr(0)).toScalarValue();
        assertThat(dot).isEqualTo(2*4 + 3*(-1));
        // CRSR
        org.setDr(0, 0);
        placeInstruction("CRSR", 0, 1, 2);
        sim.tick();
        int crs = Molecule.fromInt((Integer) org.getDr(0)).toScalarValue();
        assertThat(crs).isEqualTo(2*(-1) - 3*4);
        // DOTS / CRSS via stack
        org.getDataStack().push(v1);
        org.getDataStack().push(v2);
        placeInstruction("DOTS");
        sim.tick();
        int dotS = Molecule.fromInt((Integer) org.getDataStack().pop()).toScalarValue();
        assertThat(dotS).isEqualTo(2*4 + 3*(-1));
        org.getDataStack().push(v1);
        org.getDataStack().push(v2);
        placeInstruction("CRSS");
        sim.tick();
        int crsS = Molecule.fromInt((Integer) org.getDataStack().pop()).toScalarValue();
        assertThat(crsS).isEqualTo(2*(-1) - 3*4);
    }

    @Test
    @Tag("unit")
    void testSubrVector() {
        int[] v1 = new int[]{5, 7};
        int[] v2 = new int[]{2, 3};
        org.setDr(0, v1);
        org.setDr(1, v2);
        placeInstruction("SUBR", 0, 1);
        sim.tick();
        Object r0 = org.getDr(0);
        assertThat(r0).isInstanceOf(int[].class);
        assertThat((int[]) r0).containsExactly(3, 4);
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}