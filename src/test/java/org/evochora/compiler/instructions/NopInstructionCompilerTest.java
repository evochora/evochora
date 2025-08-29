package org.evochora.compiler.instructions;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains integration tests for the compilation and execution of the NOP instruction.
 * This test runs the full pipeline from source code compilation to execution in a simulated environment.
 * It is tagged as an "integration" test because it spans the compiler and runtime subsystems.
 */
public class NopInstructionCompilerTest {

    @BeforeAll
    static void setUp() {
        Instruction.init();
    }

	/**
	 * Verifies the end-to-end functionality of the NOP instruction.
	 * It compiles and runs a program with NOPs and asserts that the organism
	 * executes them without error and remains alive.
	 * This is an integration test.
	 *
	 * @throws Exception if compilation or simulation fails.
	 */
	@Test
	@Tag("unit")
	void testNOP_executes() throws Exception {
		String program = String.join("\n",
				"NOP",
				"NOP"
		);
		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(Arrays.asList(program.split("\\r?\\n")), "nop_auto.s");
		assertThat(artifact).isNotNull();
		Environment env = new Environment(new int[]{64,64}, true);
		Simulation sim = new Simulation(env);
		for (Map.Entry<int[], Integer> e : artifact.machineCodeLayout().entrySet()) {
			int[] abs = new int[]{e.getKey()[0], e.getKey()[1]};
			env.setMolecule(Molecule.fromInt(e.getValue()), abs);
		}
		Organism org = Organism.create(sim, new int[]{0,0}, 1000, sim.getLogger());
		org.setProgramId(artifact.programId());
		sim.addOrganism(org);
		for (int i=0;i<10;i++) sim.tick();
		assertThat(org.isDead()).isFalse();
	}
}
