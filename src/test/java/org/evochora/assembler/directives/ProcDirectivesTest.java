package org.evochora.assembler.directives;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblyProgram;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ProcDirectivesTest {

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
            world.setSymbol(Symbol.fromInt(entry.getValue()), entry.getKey());
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
    void testProc() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
        org.setDr(1, new Symbol(Config.TYPE_DATA, 100).toInt());

        List<String> code = List.of(
            ".PROC MY_PROC WITH A",
            ".EXPORT MY_PROC",
            "  ADDI A DATA:1",
            "  RET",
            ".ENDP",
            ".IMPORT MY_PROC AS P",
            "CALL P .WITH %DR1"
        );

        Organism finalOrg = runAssembly(code, org, 4);

        // DR1 was bound to EPR0; PROC increments A, copy-back on RET updates DR1 to 101.
        assertThat(finalOrg.getDr(1)).isEqualTo(new Symbol(Config.TYPE_DATA, 101).toInt());
    }
}
