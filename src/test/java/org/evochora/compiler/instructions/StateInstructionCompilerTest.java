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

public class StateInstructionCompilerTest {

	private static class RunResult { final Simulation sim; final Environment env; final Organism org; RunResult(Simulation s, Environment e, Organism o){sim=s;env=e;org=o;} }

	private RunResult compileAndRun(String source, int ticks) throws Exception {
		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(Arrays.asList(source.split("\\r?\\n")), "state_auto.s");
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

	@Test
	void testTURN_SYNC_NRG_POS_DIFF_RAND_SEEK_SCAN_FORK() throws Exception {
		String program = String.join("\n",
				"SETV %DR0 1|0",
				"TURN %DR0",
				"SYNC",
				"NRG %DR1",
				"POS %DR2",
				"DIFF %DR3",
				"SETI %DR4 DATA:5",
				"RAND %DR4",
				"SEKI 1|0",
				"SCNI %DR5 1|0",
				"NRGS",
				"POP %DR6",
				"NOP"
		);
		RunResult r = compileAndRun(program, 120);
		Object dv = r.org.getDv();
		assertThat(dv).isInstanceOf(int[].class);
		Object nrg = r.org.readOperand(1);
		assertThat(nrg).isInstanceOf(Integer.class);
		Object pos = r.org.readOperand(2);
		assertThat(pos).isInstanceOf(int[].class);
		Object diff = r.org.readOperand(3);
		assertThat(diff).isInstanceOf(int[].class);
		Object randVal = r.org.readOperand(4);
		assertThat(randVal).isInstanceOf(Integer.class);
		Object scn = r.org.readOperand(5);
		assertThat(scn == null || scn instanceof Integer).isTrue();
		Object stackNrg = r.org.readOperand(6);
		assertThat(stackNrg).isInstanceOf(Integer.class);
	}
}


