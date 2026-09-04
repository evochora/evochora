package org.evochora.runtime.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * A mutation operator must choose the same site whether the newborn's cells were written one by
 * one over its life or rebuilt from a snapshot in flat-index order. A resumed run rebuilds every
 * organism's cell set in that order; if the choice depended on it, the first birth after a resume
 * would mutate differently and the run would diverge from its uninterrupted twin.
 */
@Tag("unit")
class MutationInsertionOrderIndependenceTest {

    private static final long SEED = 42L;
    private static final int ROWS = 40;
    private static final int COLUMNS = 50;
    private static final int CHILD_ID = 2;

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

    @Test
    void geneDuplication_isIndependentOfCellInsertionOrder() {
        assertSameOutcome(rng -> new GeneDuplicationPlugin(rng, ConfigFactory.parseMap(Map.of(
                "duplicationRate", 1.0, "minNopSize", 8))));
    }

    @Test
    void geneDeletion_isIndependentOfCellInsertionOrder() {
        assertSameOutcome(rng -> new GeneDeletionPlugin(rng, ConfigFactory.parseMap(Map.of(
                "deletionRate", 1.0, "countExponent", 2.0))));
    }

    @Test
    void geneInsertion_isIndependentOfCellInsertionOrder() {
        assertSameOutcome(rng -> new GeneInsertionPlugin(rng, ConfigFactory.parseString("""
                mutationRate = 1.0
                entries = [
                  { instructions = "*", weight = 3,
                    args { REGISTER { range = [0, 7] }, LOCATION_REGISTER { range = [0, 3] },
                           DATA { min = 0, max = 255 }, LABELREF = "existing", VECTOR = "unit" } }
                  { type = "label", weight = 1, bitflips = 2 }
                ]
                """)));
    }

    @Test
    void geneSubstitution_isIndependentOfCellInsertionOrder() {
        assertSameOutcome(rng -> new GeneSubstitutionPlugin(rng, ConfigFactory.parseString("""
                substitutionRate = 1.0
                CODE { weight = 1.0, operationFlipWeight = 0.7, familyFlipWeight = 0.2, variantFlipWeight = 0.1 }
                REGISTER { weight = 1.0 }
                DATA { weight = 1.0, exponent = 0.7 }
                LABEL { weight = 1.0, bitflips = 1 }
                LABELREF { weight = 1.0, bitflips = 1 }
                """)));
    }

    /**
     * Runs the same freshly seeded operator on two worlds that hold the same genome, written in
     * two different orders, and requires identical results — checked for several seeds so that
     * an operator whose outcome only occasionally depends on the order is caught too.
     */
    private static void assertSameOutcome(Function<IRandomProvider, IBirthHandler> operator) {
        int mutated = 0;
        for (long seed = SEED; seed < SEED + 8; seed++) {
            World ascending = new World(0L);
            World permuted = new World(seed);
            List<String> before = ascending.cells();

            operator.apply(new SeededRandomProvider(seed)).onBirth(ascending.child, ascending.env);
            operator.apply(new SeededRandomProvider(seed)).onBirth(permuted.child, permuted.env);

            List<String> after = ascending.cells();
            if (!after.equals(before)) mutated++;
            assertThat(permuted.cells())
                    .as("seed %d: mutation outcome must not depend on the order in which the genome's cells were written", seed)
                    .isEqualTo(after);
        }
        assertThat(mutated).as("the operator must actually have mutated the genome, or the comparison proves nothing").isPositive();
    }

    /**
     * A child organism owning a 40×50 block of cells — label-dense code, data, registers and
     * label references in the left part of each row and an owned empty stretch on the right that
     * duplication and insertion can target.
     */
    private static final class World {
        final Environment env;
        final Organism child;

        /** @param permutationSeed 0 writes the genome in ascending index order, any other value in a seeded random order */
        World(long permutationSeed) {
            env = new Environment(new EnvironmentProperties(new int[]{64, 64}, true));
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
            for (int y = 0; y < ROWS; y++) {
                for (int x = 0; x < COLUMNS; x++) {
                    positions.add(new int[]{x, y});
                }
            }
            if (permutationSeed != 0L) {
                Collections.shuffle(positions, new Random(permutationSeed));
            }
            int seti = Instruction.getInstructionIdByName("SETI");
            for (int[] p : positions) {
                env.setMolecule(moleculeAt(p[0], p[1], seti), CHILD_ID, p);
            }
        }

        /** The genome content is a pure function of the position, so both orders yield one genome. */
        private static Molecule moleculeAt(int x, int y, int seti) {
            int slot = x % 10;
            int hash = (0x3A5 * (y + 1) + 17 * x) & Config.VALUE_MASK;
            // The right fifth of every row is an owned, empty stretch (CODE:0) — the target area
            // that duplication and insertion look for. The rest is label-dense on purpose, so that
            // operators choosing among labels see the iteration order change.
            if (x >= 40) {
                return new Molecule(Config.TYPE_CODE, 0);
            }
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
        List<String> cells() {
            List<String> out = new ArrayList<>();
            env.forEachOccupiedCellInFlatIndexOrder((index, molecule, owner) -> out.add(index + ":" + molecule + ":" + owner));
            return out;
        }
    }
}
