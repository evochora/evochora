package org.evochora.compiler;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.Simulation;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.CompilationException;
import org.evochora.runtime.internal.services.CallBindingRegistry;

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

        // NEU: Registriere die Call-Site-Bindungen
        CallBindingRegistry registry = CallBindingRegistry.getInstance();
        for (Map.Entry<Integer, int[]> binding : artifact.callSiteBindings().entrySet()) {
            int linearAddress = binding.getKey();
            int[] relativeCoord = artifact.linearAddressToCoord().get(linearAddress);
            if (relativeCoord != null) {
                int[] absoluteCoord = new int[startPos.length];
                for (int i = 0; i < startPos.length; i++) {
                    absoluteCoord[i] = startPos[i] + relativeCoord[i];
                }
                registry.registerBindingForAbsoluteCoord(absoluteCoord, binding.getValue());
            }
        }

        Organism org = Organism.create(simulation, startPos, 1000, simulation.getLogger());
        org.setProgramId(artifact.programId());
        simulation.addOrganism(org);
        return org;
    }
}