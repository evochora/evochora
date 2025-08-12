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

public class AssemblerDataInstructionTest {

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

    @Test
    void testSeti() {
        List<String> code = List.of("SETI %DR0 DATA:123");
        Organism finalOrg = runAssembly(code, null, 1);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 123).toInt());
    }

    @Test
    void testSetr() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        int v = new Molecule(Config.TYPE_DATA, 456).toInt();
        org.setDr(1, v);
        List<String> code = List.of("SETR %DR0 %DR1");
        Organism res = runAssembly(code, org, 1);
        assertThat(res.getDr(0)).isEqualTo(v);
    }

    @Test
    void testSetv() {
        List<String> code = List.of("SETV %DR0 3|4");
        Organism res = runAssembly(code, null, 1);
        Object r0 = res.getDr(0);
        assertThat(r0).isInstanceOf(int[].class);
        assertThat((int[]) r0).containsExactly(3, 4);
    }

    @Test
    void testPush() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        int v = new Molecule(Config.TYPE_DATA, 789).toInt();
        org.setDr(0, v);
        List<String> code = List.of("PUSH %DR0");
        Organism res = runAssembly(code, org, 1);
        assertThat(res.getDataStack().pop()).isEqualTo(v);
    }

    @Test
    void testPop() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        int v = new Molecule(Config.TYPE_DATA, 321).toInt();
        org.getDataStack().push(v);
        List<String> code = List.of("POP %DR0");
        Organism res = runAssembly(code, org, 1);
        assertThat(res.getDr(0)).isEqualTo(v);
    }

    @Test
    void testPusi() {
        List<String> code = List.of("PUSI DATA:42");
        Organism res = runAssembly(code, null, 1);
        assertThat(res.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }
}
