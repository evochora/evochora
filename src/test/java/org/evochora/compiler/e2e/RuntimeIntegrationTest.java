package org.evochora.compiler.e2e;

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

public class RuntimeIntegrationTest {

	@Test
	void procedureWithParametersAddsValuesAtRuntime() throws Exception {
		String source = String.join("\n",
				".PROC ADD2 EXPORT WITH A B",
				"  ADDR A B",
				"  RET",
				".ENDP",
				"SETI %DR0 DATA:1",
				"SETI %DR1 DATA:2",
				"CALL ADD2 WITH %DR0 %DR1",
				"NOP"
		);

		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(Arrays.asList(source.split("\\r?\\n")), "rt_proc_params.s");
		assertThat(artifact).isNotNull();

		// Create a small environment and simulation
		int width = 64, height = 64;
		Environment env = new Environment(new int[]{width, height}, true);
		Simulation sim = new Simulation(env);

		// Place program at origin (0,0)
		for (Map.Entry<int[], Integer> e : artifact.machineCodeLayout().entrySet()) {
			int[] rel = e.getKey();
			int[] abs = new int[]{rel[0], rel[1]};
			env.setMolecule(Molecule.fromInt(e.getValue()), abs);
		}

		Organism org = Organism.create(sim, new int[]{0, 0}, Config.INITIAL_ORGANISM_ENERGY, sim.getLogger());
		org.setProgramId(artifact.programId());
		sim.addOrganism(org);

		// Run a few ticks to execute SETI, SETI, CALL, and within proc ADDR and RET
		for (int i = 0; i < 10; i++) sim.tick();

		// Basic runtime sanity checks
		assertThat(org.getDrs().size()).isGreaterThan(1);
		assertThat(org.getDrs().get(0)).isNotNull();
		assertThat(org.getDrs().get(1)).isNotNull();
	}
}


