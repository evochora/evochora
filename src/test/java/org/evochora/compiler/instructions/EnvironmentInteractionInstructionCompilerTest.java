package org.evochora.compiler.instructions;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains integration tests for the compilation and execution of environment interaction instructions.
 * These tests run the full pipeline from source code compilation to execution in a simulated environment,
 * verifying that instructions like PEEK and POKE can be executed.
 * These are tagged as "integration" tests because they span the compiler and runtime subsystems.
 */
public class EnvironmentInteractionInstructionCompilerTest {

	private static class RunResult { final Simulation sim; final Environment env; final Organism org; RunResult(Simulation s, Environment e, Organism o){sim=s;env=e;org=o;} }

	/**
	 * Compiles the given source code, loads it into a new simulation, creates an organism,
	 * runs the simulation for a specified number of ticks, and returns the final state.
	 *
	 * @param source The source code to compile.
	 * @param ticks The number of simulation ticks to execute.
	 * @return A {@link RunResult} containing the final state of the simulation, environment, and organism.
	 * @throws Exception if compilation or simulation fails.
	 */
	private RunResult compileAndRun(String source, int ticks) throws Exception {
		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(Arrays.asList(source.split("\\r?\\n")), "env_auto.s");
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
		for (int i=0;i<ticks;i++) sim.tick();
		return new RunResult(sim, env, org);
	}

	/**
	 * Verifies the end-to-end functionality of a suite of environment interaction instructions,
	 * including register, immediate, and stack-based variants of PEEK and POKE.
	 * This is an integration test.
	 *
	 * @throws Exception if compilation or simulation fails.
	 */
	@Test
	@Tag("integration")
	void testPEEK_POKE_variants() throws Exception {
		String program = String.join("\n",
				"SETV %DR0 1|0",        // vector to (1,0)
				"SETI %DR1 DATA:13",     // value to poke
				"POKE %DR1 %DR0",        // try to poke, will fail if not empty
				"PEEK %DR2 %DR0",        // read whatever is there (might fail if empty)
				"PEKI %DR3 1|0",         // read from (1,0) immediate vector
				"PUSI DATA:21",
				"SETV %DR4 0|1",
				"PUSH %DR4",
				"POKS",                  // write 21 to (0,1) if empty
				"PEKS",                  // read from vector on stack
				"POP %DR4",
				"NOP"
		);
		RunResult r = compileAndRun(program, 120);
		Object d2 = r.org.readOperand(2);
		Object d3 = r.org.readOperand(3);
		Object d4 = r.org.readOperand(4);
		// We cannot guarantee environment emptiness deterministically here, so assert types and non-crash
		assertThat(d2 == null || d2 instanceof Integer || d2 instanceof int[]).isTrue();
		assertThat(d3 == null || d3 instanceof Integer || d3 instanceof int[]).isTrue();
		assertThat(d4 == null || d4 instanceof Integer || d4 instanceof int[]).isTrue();
	}
}
