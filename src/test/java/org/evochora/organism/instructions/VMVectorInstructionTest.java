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

public class VMVectorInstructionTest {

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
        org = Organism.create(sim, startPos, 2000, sim.getLogger());
        sim.addOrganism(org);
    }

    // Hilfsmethoden zum Platzieren von Instruktionen...
    private void placeInstruction(String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);
            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), currentPos);
        }
    }

    // VGET Tests
    @Test
    void testVgti() {
        org.setDr(1, new int[]{10, 20});
        placeInstruction("VGTI", 0, 1, new Molecule(Config.TYPE_DATA, 1).toInt()); // VGTI %DR0 %DR1 DATA:1
        sim.tick();
        assertThat(Molecule.fromInt((Integer)org.getDr(0)).toScalarValue()).isEqualTo(20);
    }

    // VSET Tests
    @Test
    void testVsti() {
        org.setDr(0, new int[]{10, 20});
        placeInstruction("VSTI", 0, new Molecule(Config.TYPE_DATA, 0).toInt(), new Molecule(Config.TYPE_DATA, 99).toInt()); // VSTI %DR0 DATA:0 DATA:99
        sim.tick();
        assertThat((int[])org.getDr(0)).containsExactly(99, 20);
    }

    // VBLD Tests
    @Test
    void testVbld() {
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 50).toInt()); // Y-Komponente
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 40).toInt()); // X-Komponente
        placeInstruction("VBLD", 0); // VBLD %DR0
        sim.tick();
        assertThat((int[])org.getDr(0)).containsExactly(40, 50);
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}