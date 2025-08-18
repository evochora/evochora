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

public class DataInstructionCompilerTest {

	private static class RunResult { final Simulation sim; final Environment env; final Organism org; RunResult(Simulation s, Environment e, Organism o){sim=s;env=e;org=o;} }

	private RunResult compileAndRun(String source, int ticks) throws Exception {
		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(Arrays.asList(source.split("\\r?\\n")), "data_auto.s");
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
	void testSETI_SETR_SETV_PUSH_POP_PUSI() throws Exception {
		String program = String.join("\n",
				"SETI %DR0 DATA:42",
				"SETR %DR1 %DR0",
				"SETV %DR2 1|0",
				"PUSH %DR1",
				"PUSI DATA:7",
				"POP %DR3",
				"NOP"
		);
		RunResult r = compileAndRun(program, 30);
		int v0 = Molecule.fromInt((Integer) r.org.readOperand(0)).toScalarValue();
		int v1 = Molecule.fromInt((Integer) r.org.readOperand(1)).toScalarValue();
		Object v2 = r.org.readOperand(2);
		int v3 = Molecule.fromInt((Integer) r.org.readOperand(3)).toScalarValue();
		assertThat(v0).isEqualTo(42);
		assertThat(v1).isEqualTo(42);
		assertThat(v2).isInstanceOf(int[].class);
		assertThat(v3).isEqualTo(7);
	}
}


