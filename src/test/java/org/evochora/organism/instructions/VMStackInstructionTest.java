package org.evochora.organism.instructions;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class VMStackInstructionTest {

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

    @Test
    void testDup() {
        int value = new Molecule(Config.TYPE_DATA, 123).toInt();
        org.getDataStack().push(value);
        placeInstruction("DUP");
        sim.tick();
        assertThat(org.getDataStack().pop()).isEqualTo(value);
        assertThat(org.getDataStack().pop()).isEqualTo(value);
        assertThat(org.getDataStack().isEmpty()).isTrue();
    }

    @Test
    void testSwap() {
        int a = new Molecule(Config.TYPE_DATA, 1).toInt();
        int b = new Molecule(Config.TYPE_DATA, 2).toInt();
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
        int a = new Molecule(Config.TYPE_DATA, 1).toInt();
        int b = new Molecule(Config.TYPE_DATA, 2).toInt();
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
        int a = new Molecule(Config.TYPE_DATA, 1).toInt();
        int b = new Molecule(Config.TYPE_DATA, 2).toInt();
        int c = new Molecule(Config.TYPE_DATA, 3).toInt();
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

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}
