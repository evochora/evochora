package org.evochora.datapipeline.resume;

import java.util.List;
import java.util.Map;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

/**
 * An organism that reproduces once, so that birth and mutation happen inside a neutrality run.
 * <p>
 * The mutation plugins only act on cells the newborn owns, and a newborn owns exactly those of its
 * parent's cells whose marker matches the parent's marker register at the moment of the fork. This
 * class therefore lays out a second block of cells beside the program — the genome the child
 * inherits — and marks it. It holds what each plugin needs: labels for deletion and duplication,
 * and code and data molecules for substitution.
 * <p>
 * The region the plugins write into is not inherited. Empty cells have no owner and never appear
 * among a child's cells, so the plugins look for the gap <em>between</em> inherited molecules: they
 * walk each scan line from the child's first to its last owned cell and treat everything empty in
 * between as writable. The layout therefore leaves a run of untouched cells in the middle.
 */
final class ForkProgram {

    /** The marker that decides which cells the child inherits. */
    static final int GENOME_MARKER = 5;

    /** Energy handed to the child. */
    static final int CHILD_ENERGY = 2000;

    /** The tick the fork happens on, counted from the first tick of the run. */
    static final int FORK_TICK = 10;

    /**
     * Row holding the inherited genome. It is the row the child is born into, so the child starts on
     * the cells it inherits rather than on empty space.
     */
    private static final int GENOME_ROW_OFFSET = 1;

    /** Contiguous empty cells in the genome — the duplication plugin needs at least eight. */
    private static final int EMPTY_RUN = 12;

    private ForkProgram() {}

    /**
     * The program: arm the marker, idle until the fork tick, reproduce once, then idle.
     * <p>
     * The idling matters. The fork has to fall between two pauses of the neutrality run so that one
     * snapshot is taken before the birth and one after it; and the parent must not reproduce again,
     * because each further birth would add organisms without adding anything to what is proven.
     */
    static List<String> source() {
        return List.of(
            ".ORG 0|0",
            "START:",
            "  SMRI DATA:" + GENOME_MARKER,
            // Idling has to be spent on real instructions: a NOP compiles to opcode zero, which is
            // indistinguishable from an empty cell, and the organism skips over it looking for code.
            "  SETI %DR7 DATA:1^" + (FORK_TICK - 2),
            "  FRKI 0|1 DATA:" + CHILD_ENERGY + " 1|0",
            "IDLE:",
            "  SETI %DR7 DATA:2",
            "  JMPI IDLE"
        );
    }

    /**
     * Compiles and places the program, lays out the inheritable genome and creates the parent.
     *
     * @param simulation the simulation to add the organism to
     * @param environment the environment to place code and genome in
     * @param at the parent's start position
     * @param energy the parent's starting energy
     * @return the parent organism
     */
    static Organism place(Simulation simulation, Environment environment, int[] at, int energy) {
        ProgramArtifact artifact;
        try {
            artifact = new Compiler().compile(
                    source(), "fork.s", new EnvironmentProperties(environment.getShape(), true));
        } catch (CompilationException e) {
            throw new IllegalStateException("the fork program must compile", e);
        }

        Organism parent = Organism.create(simulation, at, energy);
        parent.setProgramId("fork-program");
        simulation.addOrganism(parent);

        for (Map.Entry<int[], Integer> entry : artifact.machineCodeLayout().entrySet()) {
            environment.setMolecule(Molecule.fromInt(entry.getValue()), parent.getId(),
                    absolute(at, entry.getKey(), environment));
        }
        placeInheritableGenome(environment, parent, at);
        return parent;
    }

    /**
     * Lays out the cells the child inherits, along the row it is born into.
     * <p>
     * The layout follows what the mutation plugins look for. They group the child's cells into scan
     * lines perpendicular to its direction vector, walk each line from its first to its last owned
     * cell, and treat every empty cell in between as writable space — empty cells have no owner and
     * never appear among the child's own cells, so the target region is the <em>gap</em> between
     * inherited molecules, not inherited empty cells. The layout therefore places molecules at both
     * ends and leaves a gap of untouched cells in the middle.
     * <p>
     * Two labels share a hash because deletion weights labels by how often their hash occurs, and
     * a label reference is included so substitution has one of each type it knows.
     */
    private static void placeInheritableGenome(Environment environment, Organism parent, int[] at) {
        int labelHash = 0b1011_0110_0101_1001_1010 & Config.VALUE_MASK;
        int row = at[1] + GENOME_ROW_OFFSET;
        int turn = Instruction.getInstructionIdByName("TRNI");
        int column = at[0];

        mark(environment, parent, Config.TYPE_LABEL, labelHash, column++, row);
        mark(environment, parent, Config.TYPE_CODE, turn, column++, row);
        mark(environment, parent, Config.TYPE_DATA, 42, column++, row);
        mark(environment, parent, Config.TYPE_LABELREF, labelHash, column++, row);
        mark(environment, parent, Config.TYPE_LABEL, labelHash, column++, row);

        // The gap: left untouched, so these cells stay empty and unowned. Duplication and insertion
        // write here, which is why it must be wider than the duplication plugin's minimum.
        column += EMPTY_RUN;

        // A molecule on the far side, so the scan line's walk range spans the gap.
        mark(environment, parent, Config.TYPE_CODE, turn, column++, row);
        mark(environment, parent, Config.TYPE_DATA, 7, column, row);
    }

    private static void mark(Environment environment, Organism owner, int type, int value, int x, int y) {
        environment.setMolecule(new Molecule(type, value, GENOME_MARKER), owner.getId(),
                absolute(new int[]{0, 0}, new int[]{x, y}, environment));
    }

    private static int[] absolute(int[] origin, int[] relative, Environment environment) {
        int[] shape = environment.getShape();
        int[] result = new int[relative.length];
        for (int d = 0; d < relative.length; d++) {
            result[d] = Math.floorMod(origin[d] + relative[d], shape[d]);
        }
        return result;
    }
}
