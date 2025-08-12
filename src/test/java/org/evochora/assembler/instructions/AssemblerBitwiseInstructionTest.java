package org.evochora.assembler.instructions;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.compiler.internal.legacy.AssemblyProgram;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AssemblerBitwiseInstructionTest {

    private static class TestProgram extends AssemblyProgram {
        private final String code;
        public TestProgram(List<String> codeLines) {
            super("TestProgram.s");
            this.code = String.join("\n", codeLines);
        }
        @Override
        public String getProgramCode() {
            return code;
        }
    }

    private World world;
    private Simulation sim;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        world = new World(new int[]{100, 100}, true);
        sim = new Simulation(world);
    }

    private Organism runAssembly(List<String> code, Organism org, int cycles) {
        TestProgram program = new TestProgram(code);
        Map<int[], Integer> machineCode = program.assemble();

        for (Map.Entry<int[], Integer> entry : machineCode.entrySet()) {
            world.setMolecule(Molecule.fromInt(entry.getValue()), entry.getKey());
        }

        if (org == null) {
            org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        }
        sim.addOrganism(org);
        for (int i=0; i<cycles; i++) {
            sim.tick();
        }
        // Ensure no instruction failed during this run
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        return org;
    }

    // Immediate variants
    @Test
    void testAndi() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        List<String> code = List.of("ANDI %DR0 DATA:0b1100");
        Organism finalOrg = runAssembly(code, org, 1);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1000).toInt());
    }

    @Test
    void testOri() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        List<String> code = List.of("ORI %DR0 DATA:0b0101");
        Organism finalOrg = runAssembly(code, org, 1);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1111).toInt());
    }

    @Test
    void testXori() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        List<String> code = List.of("XORI %DR0 DATA:0b0110");
        Organism finalOrg = runAssembly(code, org, 1);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
    }

    // Register variants
    @Test
    void testAndr() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0b1100).toInt());
        List<String> code = List.of("ANDR %DR0 %DR1");
        Organism finalOrg = runAssembly(code, org, 1);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1000).toInt());
    }

    @Test
    void testOrr() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0b0101).toInt());
        List<String> code = List.of("ORR %DR0 %DR1");
        Organism finalOrg = runAssembly(code, org, 1);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1111).toInt());
    }

    @Test
    void testXorr() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 0b0110).toInt());
        List<String> code = List.of("XORR %DR0 %DR1");
        Organism finalOrg = runAssembly(code, org, 1);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
    }

    // Stack variants
    @Test
    void testAnds() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
        List<String> code = List.of("ANDS");
        Organism finalOrg = runAssembly(code, org, 1);
        assertThat(finalOrg.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1000).toInt());
    }

    @Test
    void testOrs() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
        List<String> code = List.of("ORS");
        Organism finalOrg = runAssembly(code, org, 1);
        assertThat(finalOrg.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 0b1110).toInt());
    }

    @Test
    void testXors() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1100).toInt());
        List<String> code = List.of("XORS");
        Organism finalOrg = runAssembly(code, org, 1);
        assertThat(finalOrg.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 0b0110).toInt());
    }

    // NOT
    @Test
    void testNot() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        List<String> code = List.of("NOT %DR0");
        Organism finalOrg = runAssembly(code, org, 1);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, ~0b1010).toInt());
    }

    @Test
    void testNots() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 0b1010).toInt());
        List<String> code = List.of("NOTS");
        Organism finalOrg = runAssembly(code, org, 1);
        assertThat(finalOrg.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, ~0b1010).toInt());
    }
}
