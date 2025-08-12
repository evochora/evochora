package org.evochora.organism.instructions;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Symbol;
import org.evochora.runtime.model.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class VMArithmeticInstructionTest {

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
        org = Organism.create(sim, startPos, 1000, sim.getLogger());
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

    private void placeInstruction(String name, int reg, int immediateValue) {
        placeInstruction(name);
        int[] currentPos = org.getIp();
        currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
        world.setSymbol(new Symbol(Config.TYPE_DATA, reg), currentPos);
        currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
        world.setSymbol(new Symbol(Config.TYPE_DATA, immediateValue), currentPos);
    }

    // --- ADD ---
    @Test
    void testAddi() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());
        placeInstruction("ADDI", 0, 5);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 15).toInt());
    }

    @Test
    void testAddr() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 3).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 4).toInt());
        placeInstruction("ADDR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 7).toInt());
    }

    @Test
    void testAdds() {
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 3).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 4).toInt());
        placeInstruction("ADDS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Symbol(Config.TYPE_DATA, 7).toInt());
    }

    // --- SUB ---
    @Test
    void testSubi() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());
        placeInstruction("SUBI", 0, 3);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 7).toInt());
    }

    @Test
    void testSubr() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 6).toInt());
        placeInstruction("SUBR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 4).toInt());
    }

    @Test
    void testSubs() {
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 3).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 10).toInt());
        placeInstruction("SUBS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Symbol(Config.TYPE_DATA, 7).toInt());
    }

    // --- MUL ---
    @Test
    void testMuli() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 7).toInt());
        placeInstruction("MULI", 0, 6);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 42).toInt());
    }

    @Test
    void testMulr() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 7).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 6).toInt());
        placeInstruction("MULR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 42).toInt());
    }

    @Test
    void testMuls() {
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 7).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 6).toInt());
        placeInstruction("MULS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Symbol(Config.TYPE_DATA, 42).toInt());
    }

    // --- DIV ---
    @Test
    void testDivi() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 42).toInt());
        placeInstruction("DIVI", 0, 6);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 7).toInt());
    }

    @Test
    void testDivr() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 42).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 6).toInt());
        placeInstruction("DIVR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 7).toInt());
    }

    @Test
    void testDivs() {
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 6).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 42).toInt());
        placeInstruction("DIVS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Symbol(Config.TYPE_DATA, 7).toInt());
    }

    // --- MOD ---
    @Test
    void testModi() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 43).toInt());
        placeInstruction("MODI", 0, 6);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testModr() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 43).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 6).toInt());
        placeInstruction("MODR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testMods() {
        // Top is operand1 (dividend), next is operand2 (divisor): compute 43 % 6
        // Top is operand1 (dividend), next is operand2 (divisor): compute 43 % 6
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 6).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 43).toInt());
        placeInstruction("MODS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }

    // --- Vector arithmetic (register variants only) ---
    @Test
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
