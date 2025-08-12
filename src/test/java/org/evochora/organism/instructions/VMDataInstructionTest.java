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

public class VMDataInstructionTest {

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

    private void placeInstruction(String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        world.setSymbol(new Symbol(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
            world.setSymbol(Symbol.fromInt(arg), currentPos);
        }
    }

    // Helper für Instruktionen mit Vektor-Argument (z. B. SETV)
    private void placeInstructionWithVector(String name, int reg, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        world.setSymbol(new Symbol(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        // Ziel-Register
        currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
        world.setSymbol(new Symbol(Config.TYPE_DATA, reg), currentPos);
        // Vektor-Komponenten
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
            world.setSymbol(new Symbol(Config.TYPE_DATA, val), currentPos);
        }
    }

    @Test
    void testSeti() {
        int immediateValue = new Symbol(Config.TYPE_DATA, 123).toInt();
        placeInstruction("SETI", 0, immediateValue);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(immediateValue);
    }

    @Test
    void testSetr() {
        int srcValue = new Symbol(Config.TYPE_DATA, 456).toInt();
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
        int value = new Symbol(Config.TYPE_DATA, 789).toInt();
        org.setDr(0, value);
        placeInstruction("PUSH", 0);
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(value);
    }

    @Test
    void testPop() {
        int value = new Symbol(Config.TYPE_DATA, 321).toInt();
        org.getDataStack().push(value);
        placeInstruction("POP", 0);
        sim.tick();
        assertThat(org.getDr(0)).isEqualTo(value);
    }

    @Test
    void testPusi() {
        int literal = new Symbol(Config.TYPE_DATA, 42).toInt();
        placeInstruction("PUSI", literal);
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(literal);
    }
}
