package org.evochora.compiler.internal.legacy.directives;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.compiler.internal.legacy.AssemblyProgram;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Symbol;
import org.evochora.runtime.model.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class RoutineDirectiveTest {

    private static class TestProgram extends AssemblyProgram {
        private final String code;
        public TestProgram(List<String> codeLines) {
            super("RoutineTest.s");
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
            world.setSymbol(Symbol.fromInt(entry.getValue()), entry.getKey());
        }

        if (org == null) {
            org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        }
        // Associate organism with the assembled program so runtime can fetch ProgramMetadata
        program.assignOrganism(org);

        sim.addOrganism(org);
        for (int i = 0; i < cycles; i++) {
            sim.tick();
        }
        return org;
    }

    @Test
    void testIncludeBasic() {
        // Define a routine INC with one param ARG that increments its argument by 1
        List<String> code = List.of(
            ".ROUTINE INC ARG",
            "  ADDI ARG DATA:1",
            ".ENDR",
            ".INCLUDE ROUTINETEST.INC AS I1 WITH %DR0"
        );
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0).toInt());

        Organism res = runAssembly(code, org, 1);
        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();
        assertThat(res.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testIncludeStrictTwice() {
        // INCLUDE_STRICT should expand fresh code for each include; executing both increments twice
        List<String> code = List.of(
            ".ROUTINE INC ARG",
            "  ADDI ARG DATA:1",
            ".ENDR",
            ".INCLUDE_STRICT ROUTINETEST.INC AS I1 WITH %DR0",
            ".INCLUDE_STRICT ROUTINETEST.INC AS I2 WITH %DR0"
        );
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0).toInt());

        Organism res = runAssembly(code, org, 2);
        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();
        assertThat(res.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 2).toInt());
    }

    @Test
    void testIncludeWithDifferentArgs() {
        // INCLUDE same routine with different signatures; each expands and executes for its respective DR
        List<String> code = List.of(
            ".ROUTINE INC ARG",
            "  ADDI ARG DATA:1",
            ".ENDR",
            ".INCLUDE ROUTINETEST.INC AS A0 WITH %DR0",
            ".INCLUDE ROUTINETEST.INC AS A1 WITH %DR1"
        );
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(0, new Symbol(Config.TYPE_DATA, 0).toInt());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 0).toInt());

        Organism res = runAssembly(code, org, 2);
        assertThat(res.isInstructionFailed()).as("Instruction failed: " + res.getFailureReason()).isFalse();
        assertThat(res.getDr(0)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
        assertThat(res.getDr(1)).isEqualTo(new Symbol(Config.TYPE_DATA, 1).toInt());
    }
}
