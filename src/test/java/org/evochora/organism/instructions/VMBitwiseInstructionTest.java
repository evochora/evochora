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

public class VMBitwiseInstructionTest {

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
        world.setSymbol(Symbol.fromInt(immediateValue), currentPos);
    }

    @Test
    void testAnds() {
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 0b1010).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 0b1100).toInt());
        placeInstruction("ANDS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Symbol(Config.TYPE_DATA, 0b1000).toInt());
    }

    @Test
    void testAndi() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("ANDI", 0, 0b1100);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 0b1000).toInt());
    }

    @Test
    void testAndr() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0b1010).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 0b1100).toInt());
        placeInstruction("ANDR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 0b1000).toInt());
    }

    @Test
    void testOrs() {
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 0b1010).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 0b1100).toInt());
        placeInstruction("ORS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Symbol(Config.TYPE_DATA, 0b1110).toInt());
    }

    @Test
    void testOri() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("ORI", 0, 0b0101);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 0b1111).toInt());
    }

    @Test
    void testOrr() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0b1010).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 0b0101).toInt());
        placeInstruction("ORR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 0b1111).toInt());
    }

    @Test
    void testXors() {
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 0b1010).toInt());
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 0b1100).toInt());
        placeInstruction("XORS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Symbol(Config.TYPE_DATA, 0b0110).toInt());
    }
    @Test
    void testXori() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("XORI", 0, 0b0110);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 0b1100).toInt());
    }

    @Test
    void testXorr() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0b1010).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 0b0110).toInt());
        placeInstruction("XORR", 0, 1);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 0b1100).toInt());
    }

    @Test
    void testNot() {
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("NOT", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, ~0b1010).toInt());
    }

    @Test
    void testNots() {
        org.getDataStack().push(new Symbol(Config.TYPE_DATA, 0b1010).toInt());
        placeInstruction("NOTS");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(new Symbol(Config.TYPE_DATA, ~0b1010).toInt());
    }
}
