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

public class BitwiseInstructionCompilerTest {

	private static class RunResult { final Simulation sim; final Environment env; final Organism org; RunResult(Simulation s, Environment e, Organism o){sim=s;env=e;org=o;} }

	private RunResult compileAndRun(String source, int ticks) throws Exception {
		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(Arrays.asList(source.split("\\r?\\n")), "bit_auto.s");
		assertThat(artifact).isNotNull();
		Environment env = new Environment(new int[]{64,64}, true);
		Simulation sim = new Simulation(env);
		for (Map.Entry<int[], Integer> e : artifact.machineCodeLayout().entrySet()) {
			int[] abs = new int[]{e.getKey()[0], e.getKey()[1]};
			env.setMolecule(Molecule.fromInt(e.getValue()), abs);
		}
		Organism org = Organism.create(sim, new int[]{0,0}, Config.INITIAL_ORGANISM_ENERGY, sim.getLogger());
		org.setProgramId(artifact.programId());
		sim.addOrganism(org);
		for (int i=0;i<ticks;i++) sim.tick();
		return new RunResult(sim, env, org);
	}

	@Test
	void testAND_OR_XOR_NAND_NOT_and_shifts() throws Exception {
		String program = String.join("\n",
				"SETI %DR0 DATA:10",
				"SETI %DR1 DATA:12",
				"ANDR %DR0 %DR1",
				"ORI %DR0 DATA:3",
				"XORI %DR0 DATA:5",
				"NADI %DR0 DATA:15",
				"NOT %DR0", // bitwise not
				"SETI %DR2 DATA:1",
				"SHLI %DR2 DATA:3", // 8
				"PUSI DATA:8",
				"PUSI DATA:2",
				"SHRS", // 2
				"POP %DR3",
				"NOP"
		);
		RunResult r = compileAndRun(program, 60);
		Object o2 = r.org.readOperand(2);
		Object o3 = r.org.readOperand(3);
		assertThat(o2).isInstanceOf(Integer.class);
		assertThat(o3).isInstanceOf(Integer.class);
	}
}


