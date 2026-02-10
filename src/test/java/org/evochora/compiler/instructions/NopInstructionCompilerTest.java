package org.evochora.compiler.instructions;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.CompilerTestBase;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.isa.Instruction;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Tag("integration")
class NopInstructionCompilerTest extends CompilerTestBase {

    private Compiler compiler;

    @BeforeEach
    void setUp() {
        Instruction.init();
        compiler = new Compiler();
    }

    @Test
    void testNOP_executes() {
        String source = "NOP";
        List<String> lines = List.of(source);
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "nop_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();

            Environment env = new Environment(testEnvProps);
            for (Map.Entry<int[], Integer> entry : artifact.machineCodeLayout().entrySet()) {
                env.setMolecule(Molecule.fromInt(entry.getValue()), entry.getKey());
            }

            // Place WAIT after NOP to stop instant-skip (NOP alone triggers max-skip recovery)
            int waitId = Instruction.getInstructionIdByName("WAIT");
            env.setMolecule(new Molecule(Config.TYPE_CODE, waitId), new int[]{1, 0});

            Simulation sim = SimulationTestUtils.createSimulation(env);
            Organism org = Organism.create(sim, new int[]{0, 0}, 1000, sim.getLogger());
            sim.addOrganism(org);

            sim.tick();
            assertThat(org.getIp()).isEqualTo(new int[]{1, 0});
        });
    }
}
