package org.evochora.runtime.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.test.utils.SimulationTestUtils;
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
 * Contains low-level unit tests for the execution of arithmetic instructions by the virtual machine.
 * Each test sets up a specific state, executes a single instruction, and verifies the precise outcome.
 * These tests operate on an in-memory simulation and do not require external resources.
 */
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
        sim = SimulationTestUtils.createSimulation(environment);
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
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), currentPos);
        }
    }

    private void placeInstruction(String name, int reg, int immediateValue) {
        placeInstruction(name);
        int[] currentPos = org.getIp();
        currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);        environment.setMolecule(new Molecule(Config.TYPE_DATA, reg), currentPos);
        currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);        environment.setMolecule(new Molecule(Config.TYPE_DATA, immediateValue), currentPos);
    }

    // --- ADD ---
    /**
     * Tests the ADDI (Add Immediate) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testAddi() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        placeInstruction("ADDI", 0, 5);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 15).toInt());
    }

    /**
     * Tests the ADDR (Add Register) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testAddr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 3).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 4).toInt());
        placeInstruction("ADDR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    /**
     * Tests the ADDS (Add Stack) instruction.
     * This is a unit test for the VM's instruction logic.
     */
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
    /**
     * Tests the SUBI (Subtract Immediate) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testSubi() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        placeInstruction("SUBI", 0, 3);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    /**
     * Tests the SUBR (Subtract Register) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testSubr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());
        placeInstruction("SUBR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 4).toInt());
    }

    /**
     * Tests the SUBS (Subtract Stack) instruction.
     * This is a unit test for the VM's instruction logic.
     */
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
    /**
     * Tests the MULI (Multiply Immediate) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testMuli() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 7).toInt());
        placeInstruction("MULI", 0, 6);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    /**
     * Tests the MULR (Multiply Register) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testMulr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 7).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());
        placeInstruction("MULR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    /**
     * Tests the MULS (Multiply Stack) instruction.
     * This is a unit test for the VM's instruction logic.
     */
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
    /**
     * Tests the DIVI (Divide Immediate) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testDivi() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 42).toInt());
        placeInstruction("DIVI", 0, 6);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    /**
     * Tests the DIVR (Divide Register) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testDivr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 42).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());
        placeInstruction("DIVR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    /**
     * Tests the DIVS (Divide Stack) instruction.
     * This is a unit test for the VM's instruction logic.
     */
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
    /**
     * Tests the MODI (Modulo Immediate) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testModi() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 43).toInt());
        placeInstruction("MODI", 0, 6);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    /**
     * Tests the MODR (Modulo Register) instruction.
     * This is a unit test for the VM's instruction logic.
     */
    @Test
    @Tag("unit")
    void testModr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 43).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());
        placeInstruction("MODR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    /**
     * Tests the MODS (Modulo Stack) instruction.
     * This is a unit test for the VM's instruction logic.
     */
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
    /**
     * Tests the ADDR instruction with vector operands.
     * This is a unit test for the VM's vector arithmetic logic.
     */
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

    /**
     * Tests the vector dot product (DOTR, DOTS) and cross product (CRSR, CRSS) instructions.
     * This is a unit test for the VM's vector arithmetic logic.
     */
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

    /**
     * Tests the SUBR instruction with vector operands.
     * This is a unit test for the VM's vector arithmetic logic.
     */
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

    // --- NEG ---
    /**
     * Tests the NEGR (Negate Register) instruction.
     */
    @Test
    @Tag("unit")
    void testNegr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 42).toInt());
        placeInstruction("NEGR", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, -42).toInt());
    }

    /**
     * Tests the NEGS (Negate Stack) instruction.
     */
    @Test
    @Tag("unit")
    void testNegs() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 42).toInt());
        placeInstruction("NEGS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, -42).toInt());
    }

    // --- ABS ---
    /**
     * Tests the ABSR (Absolute Value Register) instruction.
     */
    @Test
    @Tag("unit")
    void testAbsr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, -42).toInt());
        placeInstruction("ABSR", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    /**
     * Tests the ABSS (Absolute Value Stack) instruction.
     */
    @Test
    @Tag("unit")
    void testAbss() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, -42).toInt());
        placeInstruction("ABSS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    // --- INC ---
    /**
     * Tests the INCR (Increment Register) instruction.
     */
    @Test
    @Tag("unit")
    void testIncr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 41).toInt());
        placeInstruction("INCR", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    /**
     * Tests the INCS (Increment Stack) instruction.
     */
    @Test
    @Tag("unit")
    void testIncs() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 41).toInt());
        placeInstruction("INCS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    // --- DEC ---
    /**
     * Tests the DECR (Decrement Register) instruction.
     */
    @Test
    @Tag("unit")
    void testDecr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 43).toInt());
        placeInstruction("DECR", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    /**
     * Tests the DECS (Decrement Stack) instruction.
     */
    @Test
    @Tag("unit")
    void testDecs() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 43).toInt());
        placeInstruction("DECS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    // --- MIN ---
    /**
     * Tests the MINR (Minimum Register) instruction.
     */
    @Test
    @Tag("unit")
    void testMinr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 5).toInt());
        placeInstruction("MINR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 5).toInt());
    }

    /**
     * Tests the MINI (Minimum Immediate) instruction.
     */
    @Test
    @Tag("unit")
    void testMini() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        placeInstruction("MINI", 0, 5);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 5).toInt());
    }

    /**
     * Tests the MINS (Minimum Stack) instruction.
     */
    @Test
    @Tag("unit")
    void testMins() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 5).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 10).toInt());
        placeInstruction("MINS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 5).toInt());
    }

    // --- MAX ---
    /**
     * Tests the MAXR (Maximum Register) instruction.
     */
    @Test
    @Tag("unit")
    void testMaxr() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 5).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 10).toInt());
        placeInstruction("MAXR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 10).toInt());
    }

    /**
     * Tests the MAXI (Maximum Immediate) instruction.
     */
    @Test
    @Tag("unit")
    void testMaxi() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 5).toInt());
        placeInstruction("MAXI", 0, 10);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 10).toInt());
    }

    /**
     * Tests the MAXS (Maximum Stack) instruction.
     */
    @Test
    @Tag("unit")
    void testMaxs() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 10).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 5).toInt());
        placeInstruction("MAXS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 10).toInt());
    }

    // --- SGN ---
    /**
     * Tests the SGNR (Sign Register) instruction with positive value.
     */
    @Test
    @Tag("unit")
    void testSgnrPositive() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 42).toInt());
        placeInstruction("SGNR", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    /**
     * Tests the SGNR (Sign Register) instruction with negative value.
     */
    @Test
    @Tag("unit")
    void testSgnrNegative() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, -42).toInt());
        placeInstruction("SGNR", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, -1).toInt());
    }

    /**
     * Tests the SGNR (Sign Register) instruction with zero.
     */
    @Test
    @Tag("unit")
    void testSgnrZero() {
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0).toInt());
        placeInstruction("SGNR", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0).toInt());
    }

    /**
     * Tests the SGNS (Sign Stack) instruction.
     */
    @Test
    @Tag("unit")
    void testSgns() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, -42).toInt());
        placeInstruction("SGNS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, -1).toInt());
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}