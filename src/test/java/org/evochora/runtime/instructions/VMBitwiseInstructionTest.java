package org.evochora.runtime.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains low-level unit tests for the execution of bitwise instructions by the virtual machine.
 * Each test sets up a specific state, executes a single instruction, and verifies the precise bitwise outcome.
 * These tests operate on an in-memory simulation and do not require external resources.
 */
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
        org = Organism.create(sim, startPos, 10000, sim.getLogger());
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

    /**
     * Tests the ANDS (Bitwise AND Stack) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testAnds() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
        placeInstruction("ANDS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1000).toInt());
    }

    /**
     * Tests the ANDI (Bitwise AND Immediate) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testAndi() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("ANDI", 0, 0b1100);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1000).toInt());
    }

    /**
     * Tests the ANDR (Bitwise AND Register) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testAndr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0b1100).toInt());
        placeInstruction("ANDR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1000).toInt());
    }

    /**
     * Tests the ORS (Bitwise OR Stack) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testOrs() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
        placeInstruction("ORS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1110).toInt());
    }

    /**
     * Tests the ORI (Bitwise OR Immediate) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testOri() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("ORI", 0, 0b0101);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1111).toInt());
    }

    /**
     * Tests the ORR (Bitwise OR Register) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testOrr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0b0101).toInt());
        placeInstruction("ORR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1111).toInt());
    }

    /**
     * Tests the XORS (Bitwise XOR Stack) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testXors() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
        placeInstruction("XORS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 0b0110).toInt());
    }

    /**
     * Tests the XORI (Bitwise XOR Immediate) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testXori() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("XORI", 0, 0b0110);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
    }

    /**
     * Tests the XORR (Bitwise XOR Register) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testXorr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0b0110).toInt());
        placeInstruction("XORR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
    }

    /**
     * Tests the NOT (Bitwise NOT Register) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testNot() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("NOT", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, ~0b1010).toInt());
    }

    /**
     * Tests the NOTS (Bitwise NOT Stack) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testNots() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("NOTS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, ~0b1010).toInt());
    }

    /**
     * Tests the SHLR (Shift Left Register) and SHRR (Shift Right Register) instructions.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
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

    /**
     * Tests the bitwise rotation instructions (ROTR, ROTI, ROTS).
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testRotateRegisterAndImmediateAndStack() {
        // ROTR: rotate left by positive amount in register; negative rotates right
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b0001_0011).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 2).toInt());
        placeInstruction("ROTR", 0, 1);
        sim.tick();
        int rotr = Molecule.fromInt((Integer) org.getDr(0)).toScalarValue() & ((1 << Config.VALUE_BITS) - 1);
        assertThat(rotr).isEqualTo(((0b0001_0011 << 2) | (0b0001_0011 >>> (Config.VALUE_BITS - 2))) & ((1 << Config.VALUE_BITS) - 1));

        // ROTI: negative amount rotates right
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1000_0001).toInt());
        placeInstruction("ROTI", 0, -1);
        sim.tick();
        int roti = Molecule.fromInt((Integer) org.getDr(0)).toScalarValue() & ((1 << Config.VALUE_BITS) - 1);
        assertThat(roti).isEqualTo(((0b1000_0001 >>> 1) | (0b1000_0001 << (Config.VALUE_BITS - 1))) & ((1 << Config.VALUE_BITS) - 1));

        // ROTS: amount on top, then value; rotates by 0 and by full bit width
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010_0001).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0).toInt());
        placeInstruction("ROTS");
        sim.tick();
        int rot0 = Molecule.fromInt((Integer) org.getDataStack().pop()).toScalarValue() & ((1 << Config.VALUE_BITS) - 1);
        assertThat(rot0).isEqualTo(0b1010_0001);

        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010_0001).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, Config.VALUE_BITS).toInt());
        placeInstruction("ROTS");
        sim.tick();
        int rotW = Molecule.fromInt((Integer) org.getDataStack().pop()).toScalarValue() & ((1 << Config.VALUE_BITS) - 1);
        assertThat(rotW).isEqualTo(0b1010_0001);
    }

    /**
     * Tests the population count instructions (PCNR, PCNS), which count set bits.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testPopulationCountRegisterAndStack() {
        // PCNR
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0b1111_0001).toInt());
        placeInstruction("PCNR", 0, 1);
        sim.tick();
        int cnt = Molecule.fromInt((Integer) org.getDr(0)).toScalarValue();
        assertThat(cnt).isEqualTo(5);

        // PCNS
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010_1010).toInt());
        placeInstruction("PCNS");
        sim.tick();
        int cntS = Molecule.fromInt((Integer) org.getDataStack().pop()).toScalarValue();
        assertThat(cntS).isEqualTo(4);
    }

    /**
     * Tests the success cases for the Bit Scan Nth instructions (BSNR, BSNI, BSNS),
     * which find the Nth set bit from the right or left.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testBitScanNthSuccessAndEdges() {
        // BSNR with N=1 (first set bit from right)
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0b1010_1000).toInt());
        org.setDr(2, new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("BSNR", 0, 1, 2);
        sim.tick();
        int mask1 = Molecule.fromInt((Integer) org.getDr(0)).toScalarValue();
        assertThat(mask1).isEqualTo(0b0000_1000);

        // BSNI with N=-1 (first set bit from left)
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0b0010_1000).toInt());
        placeInstruction("BSNI", 0, 1, -1);
        sim.tick();
        int maskL = Molecule.fromInt((Integer) org.getDr(0)).toScalarValue();
        assertThat(maskL).isEqualTo(0b0010_0000);

        // BSNS with N popped first, then Src; test N=1 and N=-1
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b0000_1010).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("BSNS");
        sim.tick();
        int maskS1 = Molecule.fromInt((Integer) org.getDataStack().pop()).toScalarValue();
        assertThat(maskS1).isEqualTo(0b0000_0010);

        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b0100_0001).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, -1).toInt());
        placeInstruction("BSNS");
        sim.tick();
        int maskS2 = Molecule.fromInt((Integer) org.getDataStack().pop()).toScalarValue();
        assertThat(maskS2).isEqualTo(0b0100_0000);
    }

    /**
     * Tests the failure cases for the Bit Scan Nth instructions, such as when N is zero
     * or out of bounds.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testBitScanNthFailure() {
        // N=0 -> failure, dest set to 0
        org.setDr(0, new Molecule(Config.TYPE_DATA, 123).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("BSNI", 0, 1, 0);
        sim.tick();
        assertThat(Molecule.fromInt((Integer) org.getDr(0)).toScalarValue()).isEqualTo(0);

        // N out of bounds -> failure
        org.setDr(0, new Molecule(Config.TYPE_DATA, 999).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0b0011).toInt());
        placeInstruction("BSNR", 0, 1, 2); // but set N register below
        org.setDr(2, new Molecule(Config.TYPE_DATA, 3).toInt());
        sim.tick();
        assertThat(Molecule.fromInt((Integer) org.getDr(0)).toScalarValue()).isEqualTo(0);

        // Stack variant failure: push 0
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b0011).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 4).toInt());
        placeInstruction("BSNS");
        sim.tick();
        assertThat(Molecule.fromInt((Integer) org.getDataStack().pop()).toScalarValue()).isEqualTo(0);
    }
}