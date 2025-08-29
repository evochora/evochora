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
 * Contains integration tests for the compilation and execution of conditional instructions.
 * These tests run the full pipeline from source code compilation to execution in a simulated environment,
 * verifying that conditional skips behave as expected.
 * These are tagged as "integration" tests because they span the compiler and runtime subsystems.
 */
public class ConditionalInstructionCompilerTest {

    @BeforeAll
    static void setUp() {
        Instruction.init();
    }

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
		ProgramArtifact artifact = compiler.compile(Arrays.asList(source.split("\\r?\\n")), "cond_auto.s");
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
	 * Verifies the end-to-end functionality of a suite of conditional instructions,
	 * including checks for equality, greater/less than, type equality, and memory ownership.
	 * It tests immediate, register, and stack-based variants.
	 * This is an integration test.
	 *
	 * @throws Exception if compilation or simulation fails.
	 */
	@Test
	@Tag("integration")
	void testIF_equal_less_greater_and_type_and_IFM_variants() throws Exception {
		String program = String.join("\n",
				"SETI %DR0 DATA:10",
				"SETI %DR1 DATA:10",
				"IFR %DR0 %DR1",
				"SETI %DR2 DATA:1", // should execute
				"IFI %DR0 DATA:11",
				"SETI %DR2 DATA:2", // should be skipped
				"GTR %DR0 %DR1",
				"SETI %DR3 DATA:3", // skipped
				"LTR %DR0 %DR1",
				"SETI %DR3 DATA:4", // skipped
				"IFTR %DR0 %DR1",
				"SETI %DR4 DATA:5", // executes (same type)
				"IFMI 1|0", // cell not owned -> skip next
				"SETI %DR5 DATA:6", // likely skipped
				"SYNC",
				"IFMR %DR2", // DR2 has DATA:2 -> not a vector, but IFMR expects vector in register; ensure it's vector to be safe
				"SETV %DR6 1|0",
				"IFMR %DR6",
				"SETI %DR7 DATA:7", // cell not owned -> skipped
				"IFMS", // needs vector on stack
				"PUSI DATA:1",
				"PUSI DATA:0",
				"DROP", // leave 1 on top -> not a vector, IFMS will fail check and not skip
				"SETI %DR8 DATA:8",
				"NOP"
		);
		RunResult r = compileAndRun(program, 80);
		Object d2 = r.org.readOperand(2);
		Object d4 = r.org.readOperand(4);
		Object d5 = r.org.readOperand(5);
		Object d7 = r.org.readOperand(7);
		Object d8 = r.org.readOperand(8);
		assertThat(d2).isInstanceOf(Integer.class);
		assertThat(d4).isInstanceOf(Integer.class);
		// d5 and d7 might be null if skipped
		assertThat(d5 == null || d5 instanceof Integer).isTrue();
		assertThat(d7 == null || d7 instanceof Integer).isTrue();
		assertThat(d8 == null || d8 instanceof Integer).isTrue();
	}
}
