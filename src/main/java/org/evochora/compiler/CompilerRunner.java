package org.evochora.compiler;

import org.evochora.app.setup.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.app.Simulation;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.CompilationException;

import java.util.List;
import java.util.Map;

/**
 * Helper to compile a source and inject the resulting program into the runtime environment.
 */
public final class CompilerRunner {

	private CompilerRunner() {}

	public static Organism loadIntoEnvironment(Simulation simulation, List<String> sourceLines, String programName, int[] startPos) throws CompilationException {
		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(sourceLines, programName);

		Environment environment = simulation.getEnvironment();
		// Place code
		for (Map.Entry<int[], Integer> e : artifact.machineCodeLayout().entrySet()) {
			int[] rel = e.getKey();
			int[] abs = new int[startPos.length];
			for (int i = 0; i < startPos.length; i++) abs[i] = startPos[i] + rel[i];
			environment.setMolecule(Molecule.fromInt(e.getValue()), abs);
		}
		// Place initial world objects
		for (Map.Entry<int[], org.evochora.compiler.api.PlacedMolecule> e : artifact.initialWorldObjects().entrySet()) {
			int[] rel = e.getKey();
			int[] abs = new int[startPos.length];
			for (int i = 0; i < startPos.length; i++) abs[i] = startPos[i] + rel[i];
			org.evochora.compiler.api.PlacedMolecule pm = e.getValue();
			environment.setMolecule(new Molecule(pm.type(), pm.value()), abs);
		}

		Organism org = Organism.create(simulation, startPos, Config.INITIAL_ORGANISM_ENERGY, simulation.getLogger());
		simulation.addOrganism(org);
		return org;
	}
}


