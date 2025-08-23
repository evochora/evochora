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

    // B2V Tests
    @Test
    void testB2viPositiveX() {
        int mask = new Molecule(Config.TYPE_DATA, 1 << 0).toInt(); // +X
        placeInstruction("B2VI", 0, mask); // %DR0, DATA:mask
        sim.tick();
        assertThat((int[]) org.getDr(0)).containsExactly(1, 0);
    }

    // V2B Tests
    @Test
    void testV2biPositiveX() {
        // Immediate vector literal: write DATA mask into %DR0
        placeInstruction("V2BI", 0, 1, 0); // %DR0, Vector: 1|0
        sim.tick();
        int result = Molecule.fromInt((Integer) org.getDr(0)).toScalarValue();
        assertThat(result).isEqualTo(1 << 0);
    }

    @Test
    void testV2brNegativeY() {
        org.setDr(1, new int[]{0, -1});
        placeInstruction("V2BR", 0, 1); // %DR0, %DR1
        sim.tick();
        int result = Molecule.fromInt((Integer) org.getDr(0)).toScalarValue();
        assertThat(result).isEqualTo(1 << 3);
    }

    @Test
    void testV2bsStackVariant() {
        org.getDataStack().push(new int[]{0, 1});
        placeInstruction("V2BS");
        sim.tick();
        int mask = Molecule.fromInt((Integer) org.getDataStack().pop()).toScalarValue();
        assertThat(mask).isEqualTo(1 << 2);
    }

    // RTR* Tests
    @Test
    void testRtri2d() {
        org.setDr(0, new int[]{1, 0});
        placeInstruction("RTRI", 0, new Molecule(Config.TYPE_DATA, 0).toInt(), new Molecule(Config.TYPE_DATA, 1).toInt());
        sim.tick();
        assertThat((int[]) org.getDr(0)).containsExactly(0, -1);
    }

    @Test
    void testRtrr2d() {
        org.setDr(0, new int[]{1, 0});
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0).toInt());
        org.setDr(2, new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("RTRR", 0, 1, 2);
        sim.tick();
        assertThat((int[]) org.getDr(0)).containsExactly(0, -1);
    }

    @Test
    void testRtrs2d() {
        // Stack order top->bottom: Axis2, Axis1, Vector
        // Push Vector, then Axis1, then Axis2
        org.getDataStack().push(new int[]{1, 0});
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 1).toInt());
        placeInstruction("RTRS");
        sim.tick();
        assertThat((int[]) org.getDataStack().pop()).containsExactly(0, -1);
    }

    @Test
    void testRtri3dOtherAxesUnaffected() {
        Environment env3d = new Environment(new int[]{50, 50, 50}, true);
        Simulation sim3d = new Simulation(env3d);
        Organism org3d = Organism.create(sim3d, new int[]{5, 5, 5}, 2000, sim3d.getLogger());
        sim3d.addOrganism(org3d);

        // Helper to place instruction in 3D env
        int opcode = Instruction.getInstructionIdByName("RTRI");
        env3d.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org3d.getIp());
        int[] cur = org3d.getIp();
        cur = org3d.getNextInstructionPosition(cur, org3d.getDv(), env3d);
        env3d.setMolecule(new Molecule(Config.TYPE_DATA, 0), cur); // %DR0
        cur = org3d.getNextInstructionPosition(cur, org3d.getDv(), env3d);
        env3d.setMolecule(new Molecule(Config.TYPE_DATA, 0), cur); // Axis1 = 0 (X)
        cur = org3d.getNextInstructionPosition(cur, org3d.getDv(), env3d);
        env3d.setMolecule(new Molecule(Config.TYPE_DATA, 1), cur); // Axis2 = 1 (Y)

        org3d.setDr(0, new int[]{1, 0, 5});
        sim3d.tick();
        assertThat((int[]) org3d.getDr(0)).containsExactly(0, -1, 5);
    }

    @Test
    void testRtriFailsOnSameAxes() {
        org.setDr(0, new int[]{1, 0});
        placeInstruction("RTRI", 0, new Molecule(Config.TYPE_DATA, 0).toInt(), new Molecule(Config.TYPE_DATA, 0).toInt());
        sim.tick();
        // Vector remains unchanged, but instruction failed
        assertThat((int[]) org.getDr(0)).containsExactly(1, 0);
        assertThat(org.isInstructionFailed()).isTrue();
        // Reset org to avoid AfterEach failure assertion
        org = Organism.create(sim, startPos, 2000, sim.getLogger());
        sim.addOrganism(org);
    }

    @Test
    void testB2viNegativeY() {
        int mask = new Molecule(Config.TYPE_DATA, 1 << 3).toInt(); // 2*d+1 for d=1 => bit 3 = -Y
        placeInstruction("B2VI", 0, mask);
        sim.tick();
        assertThat((int[]) org.getDr(0)).containsExactly(0, -1);
    }

    @Test
    void testB2vsStackVariant() {
        int mask = new Molecule(Config.TYPE_DATA, 1 << 2).toInt(); // +Y
        org.getDataStack().push(mask);
        placeInstruction("B2VS");
        sim.tick();
        Object top = org.getDataStack().pop();
        assertThat((int[]) top).containsExactly(0, 1);
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoInstructionFailure() {
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
    }
}