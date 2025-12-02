package org.evochora.compiler;

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

    /**
     * Compiles the given source code and loads the resulting program into the simulation environment.
     * This creates a new organism and places its molecules (both code and initial objects)
     * into the world.
     *
     * @param simulation The simulation instance.
     * @param sourceLines The lines of source code to compile.
     * @param programName The name of the program, used for diagnostics.
     * @param startPos The starting position (coordinates) for the new organism.
     * @return The newly created organism.
     * @throws CompilationException if any errors occur during compilation.
     */
    public static Organism loadIntoEnvironment(Simulation simulation, List<String> sourceLines, String programName, int[] startPos) throws CompilationException {
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(sourceLines, programName);

        Environment environment = simulation.getEnvironment();
        
        // First, create the organism so we have the ownerId
        Organism org = Organism.create(simulation, startPos, 1000, simulation.getLogger());
        org.setProgramId(artifact.programId());
        simulation.addOrganism(org);
        
        // Place code with ownerId
        for (Map.Entry<int[], Integer> e : artifact.machineCodeLayout().entrySet()) {
            int[] rel = e.getKey();
            int[] abs = new int[startPos.length];
            for (int i = 0; i < startPos.length; i++) abs[i] = startPos[i] + rel[i];
            environment.setMolecule(Molecule.fromInt(e.getValue()), org.getId(), abs);
        }
        // Place initial world objects with ownerId
        for (Map.Entry<int[], org.evochora.compiler.api.PlacedMolecule> e : artifact.initialWorldObjects().entrySet()) {
            int[] rel = e.getKey();
            int[] abs = new int[startPos.length];
            for (int i = 0; i < startPos.length; i++) abs[i] = startPos[i] + rel[i];
            org.evochora.compiler.api.PlacedMolecule pm = e.getValue();
            environment.setMolecule(new Molecule(pm.type(), pm.value()), org.getId(), abs);
        }

        // NEW: Register the call site bindings
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

        return org;
    }
}