package org.evochora.runtime.worldgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;

import com.typesafe.config.ConfigFactory;

/**
 * A world of its own holding one newborn whose body the mutation operators can act on: a block of
 * rows × {@value #COLUMNS} cells. Every row is one scan line for a newborn facing along the x axis
 * and holds three parts:
 * <ul>
 *   <li>label-dense code, data, registers and label references, so that an operator choosing among
 *       labels sees the iteration order change;</li>
 *   <li>a tail the machine reads in one frame only: the operands of the last instruction of the
 *       dense part, a block under a label of its own, a second block, and an unconditional jump
 *       back to the first of the two. That label is a jump target and nothing else, which is what a
 *       label entry of the insertion needs, and every row has one, so that such an entry chooses
 *       among as many candidates as there are rows;</li>
 *   <li>an owned empty stretch behind that jump, which execution does not run on into and which
 *       duplication and insertion can therefore target.</li>
 * </ul>
 * <p>
 * Requires {@link Instruction#init()} to have run.
 */
public final class MutationTestWorld {

    /** Columns of every row of the body. */
    public static final int COLUMNS = 50;

    /** The newborn's organism ID. */
    public static final int CHILD_ID = 2;

    /** The world the newborn lives in. */
    public final Environment env;

    /** The newborn whose cells the body consists of. */
    public final Organism child;

    /**
     * @param rows rows of the newborn's body, one scan line each
     * @param side side of the square world, a multiple of 32 that holds the rows
     * @param permutationSeed 0 writes the genome in ascending index order, any other value in a seeded random order
     */
    public MutationTestWorld(int rows, int side, long permutationSeed) {
        env = new Environment(new EnvironmentProperties(new int[]{side, side}, true));
        Simulation sim = new Simulation(env, new ThermodynamicPolicyManager(ConfigFactory.parseString("""
                default { className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
                          options { base-energy = 1, base-entropy = 1 } }
                overrides { instructions {}, families {} }
                """)), ConfigFactory.parseMap(Map.of("max-energy", 32767, "max-entropy", 8191, "error-penalty-cost", 10)), 1);
        sim.setRandomProvider(new SeededRandomProvider(0L));
        Organism parent = Organism.create(sim, new int[]{0, 0}, 10_000);
        sim.addOrganism(parent);
        child = Organism.restore(CHILD_ID, 9)
                .parentId(parent.getId())
                .ip(new int[]{0, 0})
                .dv(new int[]{1, 0})
                .initialPosition(new int[]{0, 0})
                .energy(5_000)
                .build(sim);
        sim.addOrganism(child);

        List<int[]> positions = new ArrayList<>();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < COLUMNS; x++) {
                positions.add(new int[]{x, y});
            }
        }
        if (permutationSeed != 0L) {
            Collections.shuffle(positions, new Random(permutationSeed));
        }
        int seti = Instruction.getInstructionIdByName("SETI");
        int jmpi = Instruction.getInstructionIdByName("JMPI");
        for (int[] p : positions) {
            env.setMolecule(moleculeAt(p[0], p[1], seti, jmpi), CHILD_ID, p);
        }
    }

    /** First column of the tail the machine reads in one frame only. */
    private static final int TAIL_START = 30;

    /** Column of the label the jump at the end of the tail refers to. */
    private static final int TAIL_LABEL = 32;

    /** First column of the empty stretch. */
    private static final int EMPTY_START = 40;

    /** The value of a label, a pure function of its position. */
    private static int hashAt(int x, int y) {
        return (0x3A5 * (y + 1) + 17 * x) & Config.VALUE_MASK;
    }

    /** The genome content is a pure function of the position, so every writing order yields one genome. */
    private static Molecule moleculeAt(int x, int y, int seti, int jmpi) {
        int slot = x % 10;
        int hash = hashAt(x, y);
        // The right fifth of every row is an owned, empty stretch (CODE:0) — the target area
        // that duplication and insertion look for.
        if (x >= EMPTY_START) {
            return new Molecule(Config.TYPE_CODE, 0);
        }
        if (x >= TAIL_START) {
            return switch (x - TAIL_START) {
                // The register and the immediate of the SETI that ends the dense part
                case 0 -> new Molecule(Config.TYPE_REGISTER, x % 8);
                case 1 -> new Molecule(Config.TYPE_DATA, (x * 7 + y) % 200);
                // Two blocks, each a label and a SETI
                case 2, 6 -> new Molecule(Config.TYPE_LABEL, hash);
                case 3 -> new Molecule(Config.TYPE_CODE, seti);
                case 4 -> new Molecule(Config.TYPE_REGISTER, x % 8);
                case 5, 7 -> new Molecule(Config.TYPE_DATA, (x * 7 + y) % 200);
                // The jump back to the first of them, behind which the empty stretch begins
                case 8 -> new Molecule(Config.TYPE_CODE, jmpi);
                default -> new Molecule(Config.TYPE_LABELREF, hashAt(TAIL_LABEL, y));
            };
        }
        // The rest is label-dense on purpose, so that operators choosing among labels see the
        // iteration order change.
        return switch (slot) {
            case 0, 2, 4, 6, 8 -> new Molecule(Config.TYPE_LABEL, hash);
            case 1 -> new Molecule(Config.TYPE_CODE, seti);
            case 3 -> new Molecule(Config.TYPE_REGISTER, x % 8);
            case 5 -> new Molecule(Config.TYPE_DATA, (x * 7 + y) % 200);
            case 7 -> new Molecule(Config.TYPE_LABELREF, hash);
            default -> new Molecule(Config.TYPE_CODE, seti);
        };
    }

    /** All occupied cells as "index:molecule:owner", in ascending flat-index order. */
    public List<String> cells() {
        List<String> out = new ArrayList<>();
        env.forEachOccupiedCellInFlatIndexOrder((index, molecule, owner) -> out.add(index + ":" + molecule + ":" + owner));
        return out;
    }
}
