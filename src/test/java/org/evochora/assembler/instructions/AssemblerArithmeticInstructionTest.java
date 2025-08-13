package org.evochora.assembler.instructions;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.compiler.internal.legacy.AssemblyProgram;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AssemblerArithmeticInstructionTest {

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

    private Environment environment;
    private Simulation sim;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{100, 100}, true);
        sim = new Simulation(environment);
    }

    private Organism runAssembly(List<String> code, Organism org, int cycles) {
        TestProgram program = new TestProgram(code);
        Map<int[], Integer> machineCode = program.assemble();

        for (Map.Entry<int[], Integer> entry : machineCode.entrySet()) {
            environment.setMolecule(Molecule.fromInt(entry.getValue()), entry.getKey());
        }

        if (org == null) {
            org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        }
        sim.addOrganism(org);

        for(int i=0; i<cycles; i++) {
           sim.tick();
        }
        // Ensure no instruction failed during this run
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        return org;
    }

    // ADD
    @Test
    void testAddi() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        Organism res = runAssembly(List.of("ADDI %DR0 DATA:5"), org, 1);
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 15).toInt());
    }

    @Test
    void testAddr() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 3).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 4).toInt());
        Organism res = runAssembly(List.of("ADDR %DR0 %DR1"), org, 1);
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    @Test
    void testAdds() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 3).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 4).toInt());
        Organism res = runAssembly(List.of("ADDS"), org, 1);
        assertThat(res.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    // SUB
    @Test
    void testSubi() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        Organism res = runAssembly(List.of("SUBI %DR0 DATA:3"), org, 1);
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    @Test
    void testSubr() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());
        Organism res = runAssembly(List.of("SUBR %DR0 %DR1"), org, 1);
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 4).toInt());
    }

    @Test
    void testSubs() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        // Stack top is operand1, next is operand2 -> push 3 first, then 10 to compute 10 - 3
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 3).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 10).toInt());
        Organism res = runAssembly(List.of("SUBS"), org, 1);
        assertThat(res.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    // MUL
    @Test
    void testMuli() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 7).toInt());
        Organism res = runAssembly(List.of("MULI %DR0 DATA:6"), org, 1);
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    @Test
    void testMulr() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 7).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());
        Organism res = runAssembly(List.of("MULR %DR0 %DR1"), org, 1);
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    @Test
    void testMuls() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 7).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 6).toInt());
        Organism res = runAssembly(List.of("MULS"), org, 1);
        assertThat(res.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    // DIV
    @Test
    void testDivi() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 42).toInt());
        Organism res = runAssembly(List.of("DIVI %DR0 DATA:6"), org, 1);
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    @Test
    void testDivr() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 42).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());
        Organism res = runAssembly(List.of("DIVR %DR0 %DR1"), org, 1);
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    @Test
    void testDivs() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        // Stack top is operand1 (dividend), next is operand2 (divisor) -> push 6 then 42 to compute 42 / 6
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 6).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 42).toInt());
        Organism res = runAssembly(List.of("DIVS"), org, 1);
        assertThat(res.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 7).toInt());
    }

    // MOD
    @Test
    void testModi() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 43).toInt());
        Organism res = runAssembly(List.of("MODI %DR0 DATA:6"), org, 1);
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testModr() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 43).toInt());
        org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());
        Organism res = runAssembly(List.of("MODR %DR0 %DR1"), org, 1);
        assertThat(res.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testMods() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        // Stack top is operand1 (dividend), next is operand2 (divisor) -> push 6 then 43 to compute 43 % 6
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 6).toInt());
        org.getDataStack().push(new Molecule(Config.TYPE_DATA, 43).toInt());
        Organism res = runAssembly(List.of("MODS"), org, 1);
        assertThat(res.getDataStack().pop()).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    // Vector arithmetic (register variants only)
    @Test
    void testAddr_Vector() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        int[] v1 = new int[]{1, 2};
        int[] v2 = new int[]{3, 4};
        org.setDr(0, v1);
        org.setDr(1, v2);
        Organism res = runAssembly(List.of("ADDR %DR0 %DR1"), org, 1);
        Object r0 = res.getDr(0);
        assertThat(r0).isInstanceOf(int[].class);
        assertThat((int[]) r0).containsExactly(4, 6);
    }

    @Test
    void testSubr_Vector() {
        Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
        int[] v1 = new int[]{5, 7};
        int[] v2 = new int[]{2, 3};
        org.setDr(0, v1);
        org.setDr(1, v2);
        Organism res = runAssembly(List.of("SUBR %DR0 %DR1"), org, 1);
        Object r0 = res.getDr(0);
        assertThat(r0).isInstanceOf(int[].class);
        assertThat((int[]) r0).containsExactly(3, 4);
    }
}
