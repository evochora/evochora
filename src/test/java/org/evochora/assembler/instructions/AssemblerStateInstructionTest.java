package org.evochora.assembler.instructions;

import org.evochora.Simulation;
import org.evochora.assembler.AssemblyProgram;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AssemblerStateInstructionTest {

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
    private final int[] startPos = new int[]{0, 0};

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
            org = Organism.create(sim, startPos, 1000, sim.getLogger());
        }
        sim.addOrganism(org);
        for(int i=0; i<cycles; i++) {
            sim.tick();
        }
        // Ensure no instruction failed during this run
        assertThat(org.isInstructionFailed()).as("Instruction failed: " + org.getFailureReason()).isFalse();
        return org;
    }

    @Test
    void testPos() {
        List<String> code = List.of("POS %DR0");
        Organism finalOrg = runAssembly(code, null, 1);
        assertThat(finalOrg.getDr(0)).isEqualTo(new int[]{0,0});
    }
}
