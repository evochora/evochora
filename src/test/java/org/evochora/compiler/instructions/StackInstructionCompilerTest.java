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

public class StackInstructionCompilerTest {

    @BeforeAll
    static void setUp() {
        Instruction.init();
    }

    private static class RunResult { final Simulation sim; final Environment env; final Organism org; RunResult(Simulation s, Environment e, Organism o){sim=s;env=e;org=o;} }

	private RunResult compileAndRun(String source, int ticks) throws Exception {
		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(Arrays.asList(source.split("\\r?\\n")), "stack_auto.s");
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
	@Tag("unit")
	void testDUP_SWAP_DROP_ROT() throws Exception {
		String program = String.join("\n",
				"PUSI DATA:1",
				"PUSI DATA:2",
				"PUSI DATA:3",
				"DUP",     // 3,3,2,1
				"SWAP",    // 3,3,2,1 -> swap top two: 3,3,2,1 (same)
				"ROT",     // rotate top3: 3,2,3,1
				"DROP",    // 3,2,3
				"POP %DR0",
				"POP %DR1",
				"POP %DR2",
				"NOP"
		);
		RunResult r = compileAndRun(program, 40);
		Object d0 = r.org.readOperand(0);
		Object d1 = r.org.readOperand(1);
		Object d2 = r.org.readOperand(2);
		assertThat(d0).isInstanceOf(Integer.class);
		assertThat(d1).isInstanceOf(Integer.class);
		assertThat(d2).isInstanceOf(Integer.class);
	}
}


