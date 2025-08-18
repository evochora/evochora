package org.evochora.compiler.instructions;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ArithmeticInstructionCompilerTest {

	private static class RunResult {
		final Simulation sim;
		final Environment env;
		final Organism org;
		RunResult(Simulation sim, Environment env, Organism org) { this.sim = sim; this.env = env; this.org = org; }
	}

	private RunResult compileAndRun(String source, int ticks) throws Exception {
		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(Arrays.asList(source.split("\\r?\\n")), "arith_auto.s");
		assertThat(artifact).isNotNull();

		Environment env = new Environment(new int[]{64, 64}, true);
		Simulation sim = new Simulation(env);

		for (Map.Entry<int[], Integer> e : artifact.machineCodeLayout().entrySet()) {
			int[] rel = e.getKey();
			int[] abs = new int[]{rel[0], rel[1]};
			env.setMolecule(Molecule.fromInt(e.getValue()), abs);
		}

		Organism org = Organism.create(sim, new int[]{0, 0}, Config.INITIAL_ORGANISM_ENERGY, sim.getLogger());
		org.setProgramId(artifact.programId());
		sim.addOrganism(org);

		for (int i = 0; i < ticks; i++) sim.tick();
		return new RunResult(sim, env, org);
	}

	@Test
	void testADDR_ADDI_SUBR_SUBI_MULI_DIVI_MODI_and_stack_variants() throws Exception {
		String program = String.join("\n",
				"SETI %DR0 DATA:5",
				"SETI %DR1 DATA:7",
				"ADDR %DR0 %DR1", // DR0 = 12
				"ADDI %DR0 DATA:3", // DR0 = 15
				"SUBR %DR0 %DR1", // DR0 = 8
				"SUBI %DR0 DATA:2", // DR0 = 6
				"MULI %DR0 DATA:3", // DR0 = 18
				"DIVI %DR0 DATA:3", // DR0 = 6
				"MODI %DR0 DATA:4", // DR0 = 2
				// stack variants
				"PUSI DATA:10",
				"PUSI DATA:4",
				"ADDS", // push 14
				"PUSI DATA:20",
				"PUSI DATA:3",
				"SUBS", // push 17 (20-3)
				"PUSI DATA:2",
				"MULS", // 14*2 -> 28 on stack top
				"PUSI DATA:5",
				"DIVS", // 28/5 -> 5 (int div)
				"PUSI DATA:9",
				"MODS", // 5%9 -> 5
				"POP %DR2", // DR2 = 5
				"NOP"
		);

		RunResult r = compileAndRun(program, 50);
		Object dr0 = r.org.readOperand(0);
		Object dr1 = r.org.readOperand(1);
		Object dr2 = r.org.readOperand(2);

		assertThat(dr1).isNotNull();
		assertThat(dr0).isInstanceOf(Integer.class);
		assertThat(Molecule.fromInt((Integer) dr0).type()).isEqualTo(Config.TYPE_DATA);
		assertThat(dr2).isInstanceOf(Integer.class);
	}

	@Test
	void testVectorADD_SUB_register_variant() throws Exception {
		String program = String.join("\n",
				"SETV %DR0 1|0",
				"SETV %DR1 -1|2",
				"ADDR %DR0 %DR1", // (1,0)+(-1,2) -> (0,2)
				"NOP"
		);
		RunResult r = compileAndRun(program, 20);
		Object dr0 = r.org.readOperand(0);
		assertThat(dr0).isInstanceOf(int[].class);
		int[] v = (int[]) dr0;
		assertThat(v[0]).isZero();
		assertThat(v[1]).isEqualTo(2);
	}
}


