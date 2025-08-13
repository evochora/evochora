package org.evochora.compiler.internal.legacy.directives;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.compiler.internal.legacy.AssemblyProgram;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class MacroDirectiveTest {

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
            org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        }
        sim.addOrganism(org);
        for(int i=0; i<cycles; i++) {
            sim.tick();
        }
        return org;
    }

    @Test
    void testSimpleMacro() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Molecule(Config.TYPE_DATA, 10).toInt());

        List<String> code = List.of(
            ".MACRO INC REG",
            "  ADDI REG DATA:1",
            ".ENDM",
            "INC %DR0"
        );

        Organism finalOrg = runAssembly(code, org, 1);
        assertThat(finalOrg.getDr(0)).isEqualTo(new Molecule(Config.TYPE_DATA, 11).toInt());
    }
}
