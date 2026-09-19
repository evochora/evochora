package org.evochora.runtime.label;

import com.typesafe.config.ConfigFactory;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.OrganismRandom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The matching rule of {@link HammingLabelMatchingStrategy}: stages of Hamming distance that are
 * never mixed, own labels before foreign ones, the reach of a foreign reference, ties, topology,
 * and the mask a newborn gets.
 */
@Tag("unit")
class HammingLabelMatchingStrategyTest {

    private static final int VALUE = 0b1011_0110_0101_1001_1010;
    private static final int ONE_BIT_OFF = VALUE ^ 0b1;
    private static final int TWO_BITS_OFF = VALUE ^ 0b11;
    private static final int THREE_BITS_OFF = VALUE ^ 0b111;

    /** An organism with an even ID resolves a tie towards the lowest flat index. */
    private static final int SELF = 2;
    /** An organism with an odd ID resolves a tie towards the highest flat index. */
    private static final int ODD_SELF = 3;
    private static final int OTHER = 7;

    /** A world wide enough for a reach of 250 cells, bounded so that no distance wraps. */
    private final Environment wide = new Environment(new EnvironmentProperties(new int[]{1024, 32}, false));
    private final Environment torus = new Environment(new EnvironmentProperties(new int[]{64, 64}, true));
    private final Environment bounded = new Environment(new EnvironmentProperties(new int[]{64, 64}, false));

    private static OrganismRandom randomOf(int organismId) {
        OrganismRandom random = new OrganismRandom(organismId);
        random.beginTick(42L);
        return random;
    }

    private static int at(Environment environment, int x, int y) {
        return environment.getProperties().toFlatIndex(new int[]{x, y});
    }

    private static HammingLabelMatchingStrategy withSpread(int selectionSpread) {
        return new HammingLabelMatchingStrategy(HammingLabelMatchingStrategy.DEFAULT_TOLERANCE, selectionSpread,
                HammingLabelMatchingStrategy.DEFAULT_FOREIGN_REACH,
                HammingLabelMatchingStrategy.DEFAULT_FOREIGN_REACH_DEDUCTION_PER_BIT, 0.0,
                HammingLabelMatchingStrategy.DEFAULT_NAMESPACE_BITS);
    }

    private static HammingLabelMatchingStrategy withReach(int foreignReach) {
        return new HammingLabelMatchingStrategy(HammingLabelMatchingStrategy.DEFAULT_TOLERANCE, 0, foreignReach,
                HammingLabelMatchingStrategy.DEFAULT_FOREIGN_REACH_DEDUCTION_PER_BIT, 0.0,
                HammingLabelMatchingStrategy.DEFAULT_NAMESPACE_BITS);
    }

    // ==================== Own labels ====================

    @Test
    void aSingleOwnLabelIsTheTargetAndDrawsNoRandomNumber() {
        HammingLabelMatchingStrategy strategy = withSpread(50);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 40, 0), SELF);
        OrganismRandom used = randomOf(SELF);
        OrganismRandom untouched = randomOf(SELF);

        int target = strategy.findTarget(VALUE, SELF, new int[]{0, 0}, wide, used);

        assertThat(target).isEqualTo(at(wide, 40, 0));
        assertThat(used.nextLong()).as("the lookup drew nothing").isEqualTo(untouched.nextLong());
    }

    @Test
    void anOwnLabelIsFoundUpToTheToleranceAndNotBeyond() {
        HammingLabelMatchingStrategy strategy = withReach(-1);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 40, 0), SELF);

        assertThat(strategy.findTarget(ONE_BIT_OFF, SELF, new int[]{0, 0}, wide, randomOf(SELF))).isEqualTo(at(wide, 40, 0));
        assertThat(strategy.findTarget(TWO_BITS_OFF, SELF, new int[]{0, 0}, wide, randomOf(SELF))).isEqualTo(at(wide, 40, 0));
        assertThat(strategy.findTarget(THREE_BITS_OFF, SELF, new int[]{0, 0}, wide, randomOf(SELF))).isEqualTo(-1);
    }

    @Test
    void aWiderToleranceReachesTheThirdStage() {
        HammingLabelMatchingStrategy tolerant = new HammingLabelMatchingStrategy(3, 0, -1, 50, 0.0, 8);
        tolerant.initialize(wide.getProperties());
        tolerant.addLabel(VALUE, at(wide, 20, 0), SELF);
        tolerant.addLabel(VALUE, at(wide, 5, 0), SELF);

        assertThat(tolerant.findTarget(THREE_BITS_OFF, SELF, new int[]{0, 0}, wide, randomOf(SELF)))
                .as("both labels stand on stage 3; without a lottery the nearer one is the target")
                .isEqualTo(at(wide, 5, 0));
    }

    @Test
    void stagesAreNeverMixed_anExactLabelFarAwayBeatsANearOneABitOff() {
        HammingLabelMatchingStrategy strategy = withSpread(50);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 900, 0), SELF);
        strategy.addLabel(ONE_BIT_OFF, at(wide, 1, 0), SELF);

        for (long tick = 0; tick < 50; tick++) {
            OrganismRandom random = new OrganismRandom(SELF);
            random.beginTick(tick);
            assertThat(strategy.findTarget(VALUE, SELF, new int[]{0, 0}, wide, random)).isEqualTo(at(wide, 900, 0));
        }
    }

    @Test
    void anOwnLabelWithinToleranceBeatsANearerExactForeignOne() {
        HammingLabelMatchingStrategy strategy = withSpread(0);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(TWO_BITS_OFF, at(wide, 900, 0), SELF);
        strategy.addLabel(VALUE, at(wide, 1, 0), OTHER);

        assertThat(strategy.findTarget(VALUE, SELF, new int[]{0, 0}, wide, randomOf(SELF))).isEqualTo(at(wide, 900, 0));
    }

    @Test
    void duplicatesOnTheBestStageShareTheJumpsByLottery_aWorseStageTakesNoPart() {
        HammingLabelMatchingStrategy strategy = withSpread(50);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 10, 0), SELF);
        strategy.addLabel(VALUE, at(wide, 30, 0), SELF);
        strategy.addLabel(ONE_BIT_OFF, at(wide, 2, 0), SELF);

        Set<Integer> targets = new HashSet<>();
        for (long tick = 0; tick < 200; tick++) {
            OrganismRandom random = new OrganismRandom(SELF);
            random.beginTick(tick);
            targets.add(strategy.findTarget(VALUE, SELF, new int[]{0, 0}, wide, random));
        }

        assertThat(targets).containsExactlyInAnyOrder(at(wide, 10, 0), at(wide, 30, 0));
    }

    @Test
    void aReferenceThatMutatesByOneBitKeepsTheDistributionAmongDuplicates() {
        HammingLabelMatchingStrategy strategy = withSpread(50);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 10, 0), SELF);
        strategy.addLabel(VALUE, at(wide, 30, 0), SELF);

        for (long tick = 0; tick < 200; tick++) {
            OrganismRandom exact = new OrganismRandom(SELF);
            exact.beginTick(tick);
            OrganismRandom mutated = new OrganismRandom(SELF);
            mutated.beginTick(tick);

            assertThat(strategy.findTarget(ONE_BIT_OFF, SELF, new int[]{0, 0}, wide, mutated))
                    .as("tick %d: both duplicates move to stage 1 together", tick)
                    .isEqualTo(strategy.findTarget(VALUE, SELF, new int[]{0, 0}, wide, exact));
        }
    }

    @Test
    void withoutALotteryTheNearestDuplicateIsTheTarget_andATieFollowsTheParityOfTheOrganismId() {
        HammingLabelMatchingStrategy strategy = withSpread(0);
        strategy.initialize(wide.getProperties());
        for (int owner : new int[]{SELF, ODD_SELF}) {
            strategy.addLabel(VALUE, at(wide, 90, 0), owner);
            strategy.addLabel(VALUE, at(wide, 110, 0), owner);
            strategy.addLabel(VALUE, at(wide, 400, 0), owner);
        }
        int[] between = {100, 0};

        assertThat(strategy.findTarget(VALUE, SELF, new int[]{95, 0}, wide, randomOf(SELF))).isEqualTo(at(wide, 90, 0));
        assertThat(strategy.findTarget(VALUE, SELF, between, wide, randomOf(SELF)))
                .as("an even ID takes the lowest flat index of a tie").isEqualTo(at(wide, 90, 0));
        assertThat(strategy.findTarget(VALUE, ODD_SELF, between, wide, randomOf(ODD_SELF)))
                .as("an odd ID takes the highest flat index of a tie").isEqualTo(at(wide, 110, 0));
    }

    // ==================== Foreign labels ====================

    @Test
    void aReferenceWithoutAnOwnMatchReachesAForeignLabelWithinItsReach() {
        HammingLabelMatchingStrategy strategy = withReach(250);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 750, 0), OTHER);

        assertThat(strategy.findTarget(VALUE, SELF, new int[]{500, 0}, wide, randomOf(SELF))).isEqualTo(at(wide, 750, 0));
        assertThat(strategy.findTarget(VALUE, SELF, new int[]{499, 0}, wide, randomOf(SELF)))
                .as("one cell beyond the reach").isEqualTo(-1);
    }

    @Test
    void everyDifferingBitDeductsFromTheReach() {
        HammingLabelMatchingStrategy strategy = withReach(250);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 700, 0), OTHER);

        assertThat(strategy.findTarget(ONE_BIT_OFF, SELF, new int[]{500, 0}, wide, randomOf(SELF))).isEqualTo(at(wide, 700, 0));
        assertThat(strategy.findTarget(ONE_BIT_OFF, SELF, new int[]{499, 0}, wide, randomOf(SELF))).isEqualTo(-1);
        assertThat(strategy.findTarget(TWO_BITS_OFF, SELF, new int[]{550, 0}, wide, randomOf(SELF))).isEqualTo(at(wide, 700, 0));
        assertThat(strategy.findTarget(TWO_BITS_OFF, SELF, new int[]{549, 0}, wide, randomOf(SELF))).isEqualTo(-1);
    }

    @Test
    void aReachTooShortForADeductionLeavesThatStageOut() {
        HammingLabelMatchingStrategy strategy = withReach(40);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 501, 0), OTHER);

        assertThat(strategy.findTarget(VALUE, SELF, new int[]{500, 0}, wide, randomOf(SELF))).isEqualTo(at(wide, 501, 0));
        assertThat(strategy.findTarget(ONE_BIT_OFF, SELF, new int[]{500, 0}, wide, randomOf(SELF)))
                .as("a reach of 40 cannot pay the 50 a differing bit deducts").isEqualTo(-1);
    }

    @Test
    void aNegativeReachIsolatesAnOrganism() {
        HammingLabelMatchingStrategy strategy = withReach(-1);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 501, 0), OTHER);

        assertThat(strategy.findTarget(VALUE, SELF, new int[]{500, 0}, wide, randomOf(SELF))).isEqualTo(-1);
    }

    @Test
    void amongForeignLabelsTheBestStageCounts_andOnItTheNearest() {
        HammingLabelMatchingStrategy strategy = withReach(250);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(ONE_BIT_OFF, at(wide, 501, 0), OTHER);
        strategy.addLabel(VALUE, at(wide, 700, 0), OTHER);
        strategy.addLabel(VALUE, at(wide, 650, 1), OTHER + 1);

        assertThat(strategy.findTarget(VALUE, SELF, new int[]{500, 0}, wide, randomOf(SELF)))
                .as("the exact labels beat the near one a bit off; of the two, the nearer wins")
                .isEqualTo(at(wide, 650, 1));
    }

    @Test
    void aForeignTieFollowsTheParityOfTheOrganismIdAcrossDifferentValues() {
        HammingLabelMatchingStrategy strategy = withReach(250);
        strategy.initialize(wide.getProperties());
        // Two labels one bit off, carrying different values, at the same distance on either side
        strategy.addLabel(VALUE ^ 0b10, at(wide, 480, 0), OTHER);
        strategy.addLabel(VALUE ^ 0b01, at(wide, 520, 0), OTHER);

        assertThat(strategy.findTarget(VALUE, SELF, new int[]{500, 0}, wide, randomOf(SELF))).isEqualTo(at(wide, 480, 0));
        assertThat(strategy.findTarget(VALUE, ODD_SELF, new int[]{500, 0}, wide, randomOf(ODD_SELF))).isEqualTo(at(wide, 520, 0));
    }

    @Test
    void anUnownedLabelIsForeignToEverybody() {
        HammingLabelMatchingStrategy strategy = withReach(250);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 510, 0), 0);

        assertThat(strategy.findTarget(VALUE, SELF, new int[]{500, 0}, wide, randomOf(SELF))).isEqualTo(at(wide, 510, 0));
    }

    @Test
    void theForeignSearchDrawsNoRandomNumber() {
        HammingLabelMatchingStrategy strategy = new HammingLabelMatchingStrategy(2, 50, 250, 50, 0.0, 8);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 510, 0), OTHER);
        strategy.addLabel(VALUE, at(wide, 490, 0), OTHER);
        OrganismRandom used = randomOf(SELF);
        OrganismRandom untouched = randomOf(SELF);

        strategy.findTarget(VALUE, SELF, new int[]{500, 0}, wide, used);

        assertThat(used.nextLong()).isEqualTo(untouched.nextLong());
    }

    // ==================== Topology ====================

    @Test
    void distanceWrapsAroundATorusAndNotAroundABoundedWorld() {
        for (Environment world : new Environment[]{torus, bounded}) {
            HammingLabelMatchingStrategy strategy = withReach(10);
            strategy.initialize(world.getProperties());
            strategy.addLabel(VALUE, at(world, 62, 5), OTHER);
            int target = strategy.findTarget(VALUE, SELF, new int[]{2, 5}, world, randomOf(SELF));

            if (world == torus) {
                assertThat(target).as("four cells across the seam").isEqualTo(at(torus, 62, 5));
            } else {
                assertThat(target).as("sixty cells in a bounded world").isEqualTo(-1);
            }
        }
    }

    @Test
    void theSearchFindsLabelsOnBothSidesOfTheSeam() {
        HammingLabelMatchingStrategy strategy = withReach(10);
        strategy.initialize(torus.getProperties());
        strategy.addLabel(VALUE, at(torus, 60, 5), OTHER);
        strategy.addLabel(VALUE, at(torus, 5, 5), OTHER + 1);

        assertThat(strategy.findTarget(VALUE, SELF, new int[]{63, 5}, torus, randomOf(SELF))).isEqualTo(at(torus, 60, 5));
        assertThat(strategy.findTarget(VALUE, SELF, new int[]{1, 5}, torus, randomOf(SELF))).isEqualTo(at(torus, 5, 5));
        assertThat(strategy.findTarget(VALUE, SELF, new int[]{62, 5}, torus, randomOf(SELF)))
                .as("two cells to the one, seven across the seam to the other").isEqualTo(at(torus, 60, 5));
    }

    @Test
    void aReachBeyondTheWorldCoversAllOfIt() {
        HammingLabelMatchingStrategy strategy = withReach(1_000_000);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 1023, 7), OTHER);

        assertThat(strategy.findTarget(VALUE, SELF, new int[]{0, 0}, wide, randomOf(SELF))).isEqualTo(at(wide, 1023, 7));
    }

    // ==================== Index maintenance ====================

    @Test
    void aLabelThatChangesHandsBecomesItsNewOwnersOwn() {
        HammingLabelMatchingStrategy strategy = withReach(-1);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 10, 0), OTHER);
        assertThat(strategy.findTarget(VALUE, SELF, new int[]{0, 0}, wide, randomOf(SELF))).isEqualTo(-1);

        strategy.changeOwner(VALUE, at(wide, 10, 0), OTHER, SELF);

        assertThat(strategy.ownerOf(VALUE, at(wide, 10, 0))).isEqualTo(SELF);
        assertThat(strategy.findTarget(VALUE, SELF, new int[]{0, 0}, wide, randomOf(SELF))).isEqualTo(at(wide, 10, 0));
        assertThat(strategy.findTarget(VALUE, OTHER, new int[]{0, 0}, wide, randomOf(OTHER))).isEqualTo(-1);
    }

    @Test
    void aRemovedLabelIsGoneFromBothPaths() {
        HammingLabelMatchingStrategy strategy = withReach(250);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 10, 0), SELF);
        strategy.addLabel(VALUE, at(wide, 20, 0), SELF);

        strategy.removeLabel(VALUE, at(wide, 10, 0), SELF);

        assertThat(strategy.ownerOf(VALUE, at(wide, 10, 0))).isEqualTo(-1);
        assertThat(strategy.findTarget(VALUE, SELF, new int[]{0, 0}, wide, randomOf(SELF))).isEqualTo(at(wide, 20, 0));
        assertThat(strategy.findTarget(VALUE, OTHER, new int[]{0, 0}, wide, randomOf(OTHER))).isEqualTo(at(wide, 20, 0));

        strategy.removeLabel(VALUE, at(wide, 20, 0), SELF);
        assertThat(strategy.findTarget(VALUE, OTHER, new int[]{0, 0}, wide, randomOf(OTHER))).isEqualTo(-1);
    }

    @Test
    void theResultDoesNotDependOnTheOrderLabelsWereAddedIn() {
        int[][] labels = {{10, 0}, {30, 0}, {20, 3}, {40, 1}};
        HammingLabelMatchingStrategy forward = withSpread(50);
        forward.initialize(wide.getProperties());
        HammingLabelMatchingStrategy backward = withSpread(50);
        backward.initialize(wide.getProperties());
        for (int i = 0; i < labels.length; i++) {
            forward.addLabel(VALUE, at(wide, labels[i][0], labels[i][1]), SELF);
            int j = labels.length - 1 - i;
            backward.addLabel(VALUE, at(wide, labels[j][0], labels[j][1]), SELF);
        }

        for (long tick = 0; tick < 100; tick++) {
            OrganismRandom a = new OrganismRandom(SELF);
            a.beginTick(tick);
            OrganismRandom b = new OrganismRandom(SELF);
            b.beginTick(tick);
            assertThat(forward.findTarget(VALUE, SELF, new int[]{0, 0}, wide, a))
                    .isEqualTo(backward.findTarget(VALUE, SELF, new int[]{0, 0}, wide, b));
        }
    }

    @Test
    void valuesMatchWithinTheTolerance() {
        HammingLabelMatchingStrategy strategy = new HammingLabelMatchingStrategy();

        assertThat(strategy.valuesMatch(VALUE, VALUE)).isTrue();
        assertThat(strategy.valuesMatch(VALUE, TWO_BITS_OFF)).isTrue();
        assertThat(strategy.valuesMatch(VALUE, THREE_BITS_OFF)).isFalse();
    }

    // ==================== Birth mask ====================

    @Test
    void aFlipRateOfZeroDrawsNothingFromTheRootProvider() {
        HammingLabelMatchingStrategy strategy = new HammingLabelMatchingStrategy(2, 0, 250, 50, 0.0, 8);
        SeededRandomProvider provider = new SeededRandomProvider(42L);
        byte[] before = provider.saveState();

        assertThat(strategy.birthMask(provider)).isZero();

        assertThat(provider.saveState()).isEqualTo(before);
    }

    @Test
    void aFlipSetsExactlyOneOfTheUppermostNamespaceBits() {
        HammingLabelMatchingStrategy strategy = new HammingLabelMatchingStrategy(2, 0, 250, 50, 1.0, 8);
        SeededRandomProvider provider = new SeededRandomProvider(42L);
        int lowestNamespaceBit = Config.VALUE_BITS - 8;

        Set<Integer> bits = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            int mask = strategy.birthMask(provider);
            assertThat(Integer.bitCount(mask)).isEqualTo(1);
            bits.add(Integer.numberOfTrailingZeros(mask));
        }

        assertThat(bits).containsExactlyInAnyOrder(
                Arrays.stream(new int[]{0, 1, 2, 3, 4, 5, 6, 7}).map(i -> lowestNamespaceBit + i).boxed().toArray(Integer[]::new));
    }

    @Test
    void aFlipRateBelowOneFlipsSomeNewbornsAndNotOthers() {
        HammingLabelMatchingStrategy strategy = new HammingLabelMatchingStrategy(2, 0, 250, 50, 0.5, 8);
        SeededRandomProvider provider = new SeededRandomProvider(42L);

        int flips = 0;
        for (int i = 0; i < 400; i++) {
            if (strategy.birthMask(provider) != 0) {
                flips++;
            }
        }

        assertThat(flips).isBetween(120, 280);
    }

    // ==================== Configuration ====================

    @Test
    void readsEveryOptionFromItsConfiguration() {
        HammingLabelMatchingStrategy strategy = new HammingLabelMatchingStrategy(ConfigFactory.parseString(
                "tolerance = 3, selectionSpread = 7, foreignReach = -1, foreignReachDeductionPerBit = 9,"
                        + " namespaceFlipRate = 0.25, namespaceBits = 12"));

        assertThat(strategy.getTolerance()).isEqualTo(3);
        assertThat(strategy.getSelectionSpread()).isEqualTo(7);
        assertThat(strategy.getForeignReach()).isEqualTo(-1);
        assertThat(strategy.getForeignReachDeductionPerBit()).isEqualTo(9);
        assertThat(strategy.getNamespaceFlipRate()).isEqualTo(0.25);
        assertThat(strategy.getNamespaceBits()).isEqualTo(12);
    }

    @Test
    void anEmptyConfigurationGivesTheDefaults() {
        HammingLabelMatchingStrategy strategy = new HammingLabelMatchingStrategy(ConfigFactory.empty());

        assertThat(strategy.getTolerance()).isEqualTo(HammingLabelMatchingStrategy.DEFAULT_TOLERANCE);
        assertThat(strategy.getSelectionSpread()).isEqualTo(HammingLabelMatchingStrategy.DEFAULT_SELECTION_SPREAD);
        assertThat(strategy.getForeignReach()).isEqualTo(HammingLabelMatchingStrategy.DEFAULT_FOREIGN_REACH);
        assertThat(strategy.getNamespaceFlipRate()).isEqualTo(HammingLabelMatchingStrategy.DEFAULT_NAMESPACE_FLIP_RATE);
    }

    @Test
    void rejectsAnOptionItDoesNotKnowAndNamesTheReplacements() {
        assertThatThrownBy(() -> new HammingLabelMatchingStrategy(ConfigFactory.parseString("foreignPenalty = 100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("foreignPenalty")
                .hasMessageContaining("foreignReach")
                .hasMessageContaining("foreignReachDeductionPerBit");
    }

    @Test
    void rejectsASettingOutsideItsRange() {
        assertThatThrownBy(() -> new HammingLabelMatchingStrategy(-1, 0, 250, 50, 0.0, 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tolerance");
        assertThatThrownBy(() -> new HammingLabelMatchingStrategy(2, 0, 250, 50, 1.5, 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("namespaceFlipRate");
        assertThatThrownBy(() -> new HammingLabelMatchingStrategy(2, 0, 250, 50, 0.1, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("namespaceBits");
        assertThatThrownBy(() -> new HammingLabelMatchingStrategy(2, 0, 250, -1, 0.1, 8))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("foreignReachDeductionPerBit");
    }

    // ==================== The world's shape ====================

    @Test
    void aLabelReportedBeforeTheWorldsShapeIsKnownIsRejected() {
        HammingLabelMatchingStrategy strategy = new HammingLabelMatchingStrategy();

        assertThatThrownBy(() -> strategy.addLabel(VALUE, 5, SELF)).isInstanceOf(IllegalStateException.class);
        assertThat(strategy.findTarget(VALUE, SELF, new int[]{0, 0}, wide, randomOf(SELF)))
                .as("a strategy that knows no world holds no label").isEqualTo(-1);
    }

    @Test
    void aStrategyHoldingLabelsCannotBeGivenAnotherWorld() {
        HammingLabelMatchingStrategy strategy = new HammingLabelMatchingStrategy();
        strategy.initialize(wide.getProperties());
        strategy.initialize(torus.getProperties());
        strategy.addLabel(VALUE, at(torus, 5, 5), SELF);

        assertThatThrownBy(() -> strategy.initialize(wide.getProperties())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void labelsInUnownedCellsAreForeignLabelsToEveryone() {
        HammingLabelMatchingStrategy strategy = withReach(250);
        strategy.initialize(wide.getProperties());
        strategy.addLabel(VALUE, at(wide, 30, 0), SELF);
        strategy.changeOwner(VALUE, at(wide, 30, 0), SELF, 0);

        assertThat(strategy.ownerOf(VALUE, at(wide, 30, 0))).isZero();
        assertThat(strategy.findTarget(VALUE, SELF, new int[]{0, 0}, wide, randomOf(SELF)))
                .as("its former owner reaches it like anybody else").isEqualTo(at(wide, 30, 0));
        assertThat(strategy.findTarget(VALUE, OTHER, new int[]{0, 0}, wide, randomOf(OTHER))).isEqualTo(at(wide, 30, 0));

        strategy.changeOwner(VALUE, at(wide, 30, 0), 0, OTHER);
        assertThat(strategy.findTarget(VALUE, OTHER, new int[]{600, 0}, wide, randomOf(OTHER)))
                .as("an own label has no reach").isEqualTo(at(wide, 30, 0));
    }
}
