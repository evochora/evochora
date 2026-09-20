package org.evochora.runtime.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IRandomProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * A mutation operator must choose the same site for a newborn whatever came before it, in two
 * respects.
 * <p>
 * <strong>The order in which the newborn's cells were written.</strong> Over a run a genome is
 * written cell by cell; a resumed run rebuilds every organism's cell set from a snapshot in
 * flat-index order. If the choice depended on that order, the first birth after a resume would
 * mutate differently and the run would diverge from its uninterrupted twin.
 * <p>
 * <strong>What the operator instance processed before.</strong> An operator lives as long as the
 * run: in an uninterrupted run it has seen every newborn since the start, in a resumed or forked
 * run it is a fresh instance. Anything it keeps from one birth to the next - a reused map whose
 * iteration order follows the size it once grew to, say - must not reach the choice, or the run
 * diverges from the recorded one at the first birth where it does.
 */
@Tag("unit")
class MutationHistoryIndependenceTest {

    private static final long SEED = 42L;
    private static final int ROWS = 40;
    /** Side of the world that holds {@link #ROWS} rows. */
    private static final int WORLD_SIDE = 64;
    /** Rows of the body an operator processes before the compared birth, ten times as many scan lines. */
    private static final int LARGE_ROWS = 400;
    /** Side of the world that holds {@link #LARGE_ROWS} rows. */
    private static final int LARGE_WORLD_SIDE = 512;

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

    @Test
    void geneDuplication_isIndependentOfCellInsertionOrder() {
        assertSameOutcome(MutationHistoryIndependenceTest::duplication);
    }

    @Test
    void geneDeletion_isIndependentOfCellInsertionOrder() {
        assertSameOutcome(MutationHistoryIndependenceTest::deletion);
    }

    @Test
    void instructionInsertion_isIndependentOfCellInsertionOrder() {
        assertSameOutcome(MutationHistoryIndependenceTest::instructionInsertion);
    }

    @Test
    void labelInsertion_isIndependentOfCellInsertionOrder() {
        assertSameOutcome(MutationHistoryIndependenceTest::labelInsertion);
    }

    @Test
    void geneSubstitution_isIndependentOfCellInsertionOrder() {
        assertSameOutcome(MutationHistoryIndependenceTest::substitution);
    }

    @Test
    void geneDuplication_isIndependentOfWhatTheInstanceProcessedBefore() {
        assertSameOutcomeAfterHistory(MutationHistoryIndependenceTest::duplication);
    }

    @Test
    void geneDeletion_isIndependentOfWhatTheInstanceProcessedBefore() {
        assertSameOutcomeAfterHistory(MutationHistoryIndependenceTest::deletion);
    }

    @Test
    void instructionInsertion_isIndependentOfWhatTheInstanceProcessedBefore() {
        assertSameOutcomeAfterHistory(MutationHistoryIndependenceTest::instructionInsertion);
    }

    @Test
    void labelInsertion_isIndependentOfWhatTheInstanceProcessedBefore() {
        assertSameOutcomeAfterHistory(MutationHistoryIndependenceTest::labelInsertion);
    }

    @Test
    void geneSubstitution_isIndependentOfWhatTheInstanceProcessedBefore() {
        assertSameOutcomeAfterHistory(MutationHistoryIndependenceTest::substitution);
    }

    private static IBirthHandler duplication(IRandomProvider rng) {
        return new GeneDuplicationPlugin(rng, ConfigFactory.parseMap(Map.of(
                "duplicationRate", 1.0, "minNopSize", 8)));
    }

    private static IBirthHandler deletion(IRandomProvider rng) {
        // Every label of this body is a candidate, so the operator finds a site although almost
        // all of the label values here occur only once.
        return new GeneDeletionPlugin(rng, ConfigFactory.parseMap(Map.of(
                "deletionRate", 1.0, "countExponent", 2.0, "minLabelCount", 1)));
    }

    // The two entry types of the insertion are compared one at a time: each walks the body its own
    // way, and a plugin that holds both would pass on the strength of whichever of them placed
    // something.

    private static IBirthHandler instructionInsertion(IRandomProvider rng) {
        return new GeneInsertionPlugin(rng, ConfigFactory.parseString("""
                mutationRate = 1.0
                entries = [
                  { instructions = "*", weight = 1,
                    args { REGISTER { range = [0, 7] }, LOCATION_REGISTER { range = [0, 3] },
                           DATA { min = 0, max = 255 }, LABELREF = "existing", VECTOR = "unit" } }
                ]
                """));
    }

    private static IBirthHandler labelInsertion(IRandomProvider rng) {
        return new GeneInsertionPlugin(rng, ConfigFactory.parseString("""
                mutationRate = 1.0
                entries = [
                  { type = "label", weight = 1, instructions = "*",
                    args { REGISTER { range = [0, 7] }, LOCATION_REGISTER { range = [0, 3] },
                           DATA { min = 0, max = 255 }, LABELREF = "existing", VECTOR = "unit" } }
                ]
                """));
    }

    private static IBirthHandler substitution(IRandomProvider rng) {
        return new GeneSubstitutionPlugin(rng, ConfigFactory.parseString("""
                substitutionRate = 1.0
                CODE { weight = 1.0, operationFlipWeight = 0.7, familyFlipWeight = 0.2, variantFlipWeight = 0.1 }
                REGISTER { weight = 1.0 }
                DATA { weight = 1.0, exponent = 0.7 }
                LABEL { weight = 1.0, bitflips = 1 }
                LABELREF { weight = 1.0, bitflips = 1 }
                operands { scalar = 1.0, vector = 1.0 }
                """));
    }

    /**
     * Runs the same freshly seeded operator on two worlds that hold the same genome, written in
     * two different orders, and requires identical results — checked for several seeds so that
     * an operator whose outcome only occasionally depends on the order is caught too.
     */
    private static void assertSameOutcome(Function<IRandomProvider, IBirthHandler> operator) {
        int mutated = 0;
        for (long seed = SEED; seed < SEED + 8; seed++) {
            MutationTestWorld ascending = new MutationTestWorld(ROWS, WORLD_SIDE, 0L);
            MutationTestWorld permuted = new MutationTestWorld(ROWS, WORLD_SIDE, seed);
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
     * Gives the same newborn to a fresh operator and to one that first processed a body with ten
     * times as many scan lines, both drawing the same random numbers for the compared birth, and
     * requires identical results — checked for several seeds so that an operator whose outcome
     * only occasionally depends on its history is caught too.
     */
    private static void assertSameOutcomeAfterHistory(Function<IRandomProvider, IBirthHandler> operator) {
        int mutated = 0;
        for (long seed = SEED; seed < SEED + 8; seed++) {
            MutationTestWorld fresh = new MutationTestWorld(ROWS, WORLD_SIDE, 0L);
            MutationTestWorld afterHistory = new MutationTestWorld(ROWS, WORLD_SIDE, 0L);
            MutationTestWorld large = new MutationTestWorld(LARGE_ROWS, LARGE_WORLD_SIDE, 0L);
            List<String> before = fresh.cells();

            operator.apply(new SeededRandomProvider(seed)).onBirth(fresh.child, fresh.env);

            SeededRandomProvider rng = new SeededRandomProvider(seed);
            byte[] start = rng.saveState();
            IBirthHandler used = operator.apply(rng);
            used.onBirth(large.child, large.env);
            rng.loadState(start);
            used.onBirth(afterHistory.child, afterHistory.env);

            List<String> after = fresh.cells();
            if (!after.equals(before)) mutated++;
            assertThat(afterHistory.cells())
                    .as("seed %d: mutation outcome must not depend on the bodies the operator instance processed before", seed)
                    .isEqualTo(after);
        }
        assertThat(mutated).as("the operator must actually have mutated the genome, or the comparison proves nothing").isPositive();
    }
}
