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

public class ControlFlowInstructionCompilerTest {

	private static class RunResult { final Simulation sim; final Environment env; final Organism org; RunResult(Simulation s, Environment e, Organism o){sim=s;env=e;org=o;} }

	private RunResult compileAndRun(String source, int ticks) throws Exception {
		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(Arrays.asList(source.split("\\r?\\n")), "ctrl_auto.s");
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
	void testJMPI_JMPR_JMPS_CALL_RET() throws Exception {
		String program = String.join("\n",
				"SETV %DR0 1|0",     // DR0 holds a jump vector to label L1 (will be resolved to actual delta by compiler when used as label)
				"JMPI L1",
				"SETI %DR1 DATA:111", // should be skipped by jump
				"L1:",
				"SETI %DR1 DATA:5",
				"JMPR %DR0",         // jump relative by vector in DR0
				"SETI %DR1 DATA:222", // should be skipped
				"L2:",
				"SETV %DR4 0|1",
				"PUSH %DR4",
				"JMPS",                // jump by vector from stack
				"SETI %DR1 DATA:333", // skipped
				"L3:",
				"CALL PROC1",
				"SETI %DR2 DATA:7",
				"NOP",
				".PROC PROC1 EXPORT",
				"  SETI %DR3 DATA:9",
				"  RET",
				".ENDP"
		);
		RunResult r = compileAndRun(program, 120);
		Object d1 = r.org.readOperand(1);
		Object d2 = r.org.readOperand(2);
		Object d3 = r.org.readOperand(3);
		assertThat(d1).isInstanceOf(Integer.class);
		assertThat(d2).isInstanceOf(Integer.class);
		assertThat(d3).isInstanceOf(Integer.class);
	}
}


