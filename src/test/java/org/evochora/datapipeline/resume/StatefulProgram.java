package org.evochora.datapipeline.resume;

import java.util.List;
import java.util.Map;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

/**
 * The program itself, plus the placement helper both this test and the neutrality test use.
 */
final class StatefulProgram {

    /** Instructions in one loop pass — one instruction is executed per tick. */
    static final int TICKS_PER_PASS = 28;

    /** The cell the program writes to, relative to the organism's start. */
    static final int[] SCRATCH_CELL = {1, 1};

    private StatefulProgram() {}

    static List<String> source() {
        return List.of(
            ".ORG 0|0",
            "START:",
            "  SETI %DR0 DATA:1",
            "  SETI %DR1 DATA:2",
            "  SEKI 1|0",
            "  DPLR %LR0",
            "  DPLR %LR1",
            "  PUSH %DR0",
            "  PUSI DATA:9",
            "  PUSL %LR0",
            "  DPLS",
            "  SMRI DATA:5",
            "  ADPI DATA:1",
            "  ADPI DATA:0",
            "  SETI %DR4 DATA:77",
            "  PPKI %DR4 0|1",
            "  CALL WORKER VAL %DR1 LREF %LR1",
            "  POPL %LR2",
            "  POPL %LR3",
            "  POP %DR2",
            "  POP %DR3",
            "  SEKI -1|0",
            "  JMPI START",
            "",
            ".ORG 0|4",
            ".PROC WORKER VAL V LREF L",
            "  SETI %PDR0 DATA:11",
            "  DPLR %PLR0",
            "  SETI %SDR0 DATA:22",
            "  DPLR %SLR0",
            "  SETI V DATA:33",
            "  CRLR L",
            "  RET",
            ".ENDP"
        );
    }

    /**
     * Compiles the program, places it at {@code at} and creates the organism that runs it.
     *
     * @param simulation the simulation to add the organism to
     * @param environment the environment to place the code in
     * @param at the organism's start position
     * @param energy the organism's starting energy
     * @return the organism running the program
     */
    static Organism place(Simulation simulation, Environment environment, int[] at, int energy) {
        ProgramArtifact artifact;
        try {
            artifact = new Compiler().compile(
                    source(), "stateful.s",
                    new EnvironmentProperties(environment.getShape(), true));
        } catch (CompilationException e) {
            throw new IllegalStateException("the test program must compile", e);
        }

        Organism organism = Organism.create(simulation, at, energy);
        organism.setProgramId("stateful-program");
        simulation.addOrganism(organism);

        for (Map.Entry<int[], Integer> entry : artifact.machineCodeLayout().entrySet()) {
            int[] relative = entry.getKey();
            int[] absolute = new int[relative.length];
            for (int d = 0; d < relative.length; d++) {
                absolute[d] = Math.floorMod(at[d] + relative[d], environment.getShape()[d]);
            }
            environment.setMolecule(Molecule.fromInt(entry.getValue()), organism.getId(), absolute);
        }
        return organism;
    }
}
