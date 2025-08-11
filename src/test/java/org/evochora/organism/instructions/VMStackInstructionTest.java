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

public class VMStackInstructionTest {

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

    @Test
    void testDup() {
        int value = new Symbol(Config.TYPE_DATA, 123).toInt();
        org.getDataStack().push(value);
        placeInstruction("DUP");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(value);
        assertThat(org.getDataStack().pop()).isEqualTo(value);
        assertThat(org.getDataStack().isEmpty()).isTrue();
    }

    @Test
    void testSwap() {
        int a = new Symbol(Config.TYPE_DATA, 1).toInt();
        int b = new Symbol(Config.TYPE_DATA, 2).toInt();
        org.getDataStack().push(a);
        org.getDataStack().push(b);
        placeInstruction("SWAP");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(a);
        assertThat(org.getDataStack().pop()).isEqualTo(b);
        assertThat(org.getDataStack().isEmpty()).isTrue();
    }

    @Test
    void testDrop() {
        int a = new Symbol(Config.TYPE_DATA, 1).toInt();
        int b = new Symbol(Config.TYPE_DATA, 2).toInt();
        org.getDataStack().push(a);
        org.getDataStack().push(b);
        placeInstruction("DROP");
        sim.tick();
        // b wurde gedroppt, a bleibt oben
        assertThat(org.getDataStack().pop()).isEqualTo(a);
        assertThat(org.getDataStack().isEmpty()).isTrue();
    }

    @Test
    void testRot() {
        int a = new Symbol(Config.TYPE_DATA, 1).toInt();
        int b = new Symbol(Config.TYPE_DATA, 2).toInt();
        int c = new Symbol(Config.TYPE_DATA, 3).toInt();
        org.getDataStack().push(a);
        org.getDataStack().push(b);
        org.getDataStack().push(c);
        placeInstruction("ROT");
        sim.tick();
        // Erwartete Reihenfolge (unten->oben): b, c, a
        assertThat(org.getDataStack().pop()).isEqualTo(a);
        assertThat(org.getDataStack().pop()).isEqualTo(c);
        assertThat(org.getDataStack().pop()).isEqualTo(b);
        assertThat(org.getDataStack().isEmpty()).isTrue();
    }
}
