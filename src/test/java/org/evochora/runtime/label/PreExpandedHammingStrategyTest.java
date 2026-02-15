package org.evochora.runtime.label;

import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.spi.IRandomProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PreExpandedHammingStrategy.
 * <p>
 * Tests the Hamming distance matching algorithm, including:
 * <ul>
 *   <li>Exact match (Hamming distance 0)</li>
 *   <li>Fuzzy match (Hamming distance 1 and 2)</li>
 *   <li>Own vs foreign label scoring</li>
 *   <li>Transfer marker handling</li>
 *   <li>Physical distance scoring</li>
 *   <li>Tie-breaking with owner ID</li>
 * </ul>
 */
@Tag("unit")
class PreExpandedHammingStrategyTest {

    private PreExpandedHammingStrategy strategy;
    private Environment environment;
    private int[] callerCoords;

    @BeforeEach
    void setUp() {
        // Use explicit values for test predictability
        strategy = new PreExpandedHammingStrategy(2, 100, 50);
        // Create a small test environment (64x64, toroidal)
        EnvironmentProperties props = new EnvironmentProperties(new int[]{64, 64}, true);
        environment = new Environment(props);
        callerCoords = new int[]{0, 0};
    }

    @Test
    void testDefaults() {
        PreExpandedHammingStrategy defaultStrategy = new PreExpandedHammingStrategy();
        assertThat(defaultStrategy.getTolerance()).isEqualTo(PreExpandedHammingStrategy.DEFAULT_TOLERANCE);
        assertThat(defaultStrategy.getForeignPenalty()).isEqualTo(PreExpandedHammingStrategy.DEFAULT_FOREIGN_PENALTY);
        assertThat(defaultStrategy.getHammingWeight()).isEqualTo(PreExpandedHammingStrategy.DEFAULT_HAMMING_WEIGHT);
    }

    @Test
    void testExactMatch() {
        int labelValue = 0b10101010101010101010; // 20 bits
        int flatIndex = 100;
        int owner = 1;

        LabelEntry entry = new LabelEntry(flatIndex, owner, 0);
        strategy.addLabel(labelValue, entry);

        int result = strategy.findTarget(labelValue, owner, callerCoords, environment);
        assertThat(result).isEqualTo(flatIndex);
    }

    @Test
    void testFuzzyMatchDistance1() {
        int labelValue = 0b10101010101010101010;
        int searchValue = 0b10101010101010101011; // 1 bit different
        int flatIndex = 100;
        int owner = 1;

        LabelEntry entry = new LabelEntry(flatIndex, owner, 0);
        strategy.addLabel(labelValue, entry);

        int result = strategy.findTarget(searchValue, owner, callerCoords, environment);
        assertThat(result).isEqualTo(flatIndex);
    }

    @Test
    void testFuzzyMatchDistance2() {
        int labelValue = 0b10101010101010101010;
        int searchValue = 0b10101010101010101001; // 2 bits different
        int flatIndex = 100;
        int owner = 1;

        LabelEntry entry = new LabelEntry(flatIndex, owner, 0);
        strategy.addLabel(labelValue, entry);

        int result = strategy.findTarget(searchValue, owner, callerCoords, environment);
        assertThat(result).isEqualTo(flatIndex);
    }

    @Test
    void testNoMatchBeyondTolerance() {
        int labelValue = 0b10101010101010101010;
        int searchValue = 0b10101010101010100001; // 3 bits different
        int flatIndex = 100;
        int owner = 1;

        LabelEntry entry = new LabelEntry(flatIndex, owner, 0);
        strategy.addLabel(labelValue, entry);

        int result = strategy.findTarget(searchValue, owner, callerCoords, environment);
        assertThat(result).isEqualTo(-1);
    }

    @Test
    void testOwnLabelPreferredOverForeignAtSameDistance() {
        int labelValue = 12345;
        int ownOwner = 1;
        int foreignOwner = 2;

        // Both labels at same physical distance from caller (flatIndex 64 and 65 are adjacent)
        // flatIndex 64 = (1, 0), flatIndex 65 = (1, 1) in 64x64 grid
        LabelEntry foreignEntry = new LabelEntry(64, foreignOwner, 0);
        strategy.addLabel(labelValue, foreignEntry);

        LabelEntry ownEntry = new LabelEntry(65, ownOwner, 0);
        strategy.addLabel(labelValue, ownEntry);

        // Own label should be preferred (lower score: distance + 0 vs distance + 20)
        int result = strategy.findTarget(labelValue, ownOwner, callerCoords, environment);
        assertThat(result).isEqualTo(65);
    }

    @Test
    void testTransferMarkerMakesLabelForeign() {
        int labelValue = 12345;
        int owner = 1;
        int marker = 1; // Non-zero marker = transfer in progress

        // Add label with transfer marker at position 64
        LabelEntry entryWithMarker = new LabelEntry(64, owner, marker);
        strategy.addLabel(labelValue, entryWithMarker);

        // Add label without marker at position 65
        LabelEntry entryWithoutMarker = new LabelEntry(65, owner, 0);
        strategy.addLabel(labelValue, entryWithoutMarker);

        // Label without marker should be preferred (marker makes first one "foreign")
        int result = strategy.findTarget(labelValue, owner, callerCoords, environment);
        assertThat(result).isEqualTo(65);
    }

    @Test
    void testOwnLabelBeatsExactForeignWithMutation() {
        // With combined score: own label with 1-bit mutation should beat foreign exact match
        // score = (hamming × 50) + distance + (foreign ? 100 : 0)
        int exactValue = 12345;
        int nearValue = 12345 ^ 1; // 1 bit different

        int foreignOwner = 2;
        int ownOwner = 1;

        // Add own label with 1-bit difference at position 64 (≈distance 1)
        LabelEntry ownFuzzy = new LabelEntry(64, ownOwner, 0);
        strategy.addLabel(nearValue, ownFuzzy);

        // Add foreign label with exact match at position 65 (≈distance 1)
        LabelEntry foreignExact = new LabelEntry(65, foreignOwner, 0);
        strategy.addLabel(exactValue, foreignExact);

        // Own fuzzy: score = 50 + ~1 + 0 = ~51
        // Foreign exact: score = 0 + ~1 + 100 = ~101
        // Own wins due to foreignPenalty outweighing hammingWeight
        int result = strategy.findTarget(exactValue, ownOwner, callerCoords, environment);
        assertThat(result).isEqualTo(64); // Own label wins despite 1-bit mutation
    }

    @Test
    void testSameHammingSameOwnershipCloserWins() {
        int labelValue = 12345;
        int owner = 1;

        // Two own labels at different distances
        // flatIndex 1 = (0, 1), distance from (0,0) = 1
        // flatIndex 10 = (0, 10), distance from (0,0) = 10
        LabelEntry farEntry = new LabelEntry(10, owner, 0);
        strategy.addLabel(labelValue, farEntry);

        LabelEntry nearEntry = new LabelEntry(1, owner, 0);
        strategy.addLabel(labelValue, nearEntry);

        // Closer label should win
        int result = strategy.findTarget(labelValue, owner, callerCoords, environment);
        assertThat(result).isEqualTo(1);
    }

    @Test
    void testSameScoreTieBreakByOwner() {
        int labelValue = 12345;
        int owner1 = 3;
        int owner2 = 5;

        // Both labels are foreign (caller is owner 1), same distance
        // Using adjacent flatIndex values so physical distances are similar
        LabelEntry entry1 = new LabelEntry(64, owner1, 0);
        LabelEntry entry2 = new LabelEntry(65, owner2, 0);

        strategy.addLabel(labelValue, entry1);
        strategy.addLabel(labelValue, entry2);

        // Both are foreign with similar distance, lower owner ID wins (determinism)
        int result = strategy.findTarget(labelValue, 1, callerCoords, environment);
        assertThat(result).isEqualTo(64); // owner1 (3) < owner2 (5)
    }

    @Test
    void testUpdateOwner() {
        int labelValue = 12345;
        int flatIndex = 100;
        int oldOwner = 1;
        int newOwner = 2;

        LabelEntry entry = new LabelEntry(flatIndex, oldOwner, 0);
        strategy.addLabel(labelValue, entry);

        // Update owner
        strategy.updateOwner(labelValue, flatIndex, newOwner);

        // Verify: searching as newOwner should find it as "own"
        var candidates = strategy.getCandidates(labelValue);
        assertThat(candidates).hasSize(1);
        LabelEntry updated = candidates.iterator().next();
        assertThat(updated.owner()).isEqualTo(newOwner);
        assertThat(updated.isForeign(newOwner)).isFalse();
    }

    @Test
    void testUpdateMarker() {
        int labelValue = 12345;
        int flatIndex = 100;
        int owner = 1;

        LabelEntry entry = new LabelEntry(flatIndex, owner, 0);
        strategy.addLabel(labelValue, entry);

        // Update marker
        strategy.updateMarker(labelValue, flatIndex, 5);

        // Verify: marker should be updated
        var candidates = strategy.getCandidates(labelValue);
        assertThat(candidates).hasSize(1);
        LabelEntry updated = candidates.iterator().next();
        assertThat(updated.marker()).isEqualTo(5);
        assertThat(updated.isForeign(owner)).isTrue(); // marker != 0 makes it foreign
    }

    @Test
    void testRemoveLabel() {
        int labelValue = 12345;
        int flatIndex = 100;
        int owner = 1;

        LabelEntry entry = new LabelEntry(flatIndex, owner, 0);
        strategy.addLabel(labelValue, entry);

        // Verify it exists
        assertThat(strategy.findTarget(labelValue, owner, callerCoords, environment)).isEqualTo(flatIndex);

        // Remove it
        strategy.removeLabel(labelValue, flatIndex);

        // Should no longer be found
        assertThat(strategy.findTarget(labelValue, owner, callerCoords, environment)).isEqualTo(-1);
    }

    @Test
    void testHammingNeighborsCount() {
        // For tolerance=2 with 20 bits:
        // Distance 1: 20 neighbors
        // Distance 2: 20*19/2 = 190 neighbors
        // Total: 210 neighbors + 1 (self) = 211
        int labelValue = 0;
        int flatIndex = 100;
        int owner = 1;

        LabelEntry entry = new LabelEntry(flatIndex, owner, 0);
        strategy.addLabel(labelValue, entry);

        // Check a few specific neighbors
        // Distance 1: flip bit 0
        assertThat(strategy.findTarget(1, owner, callerCoords, environment)).isEqualTo(flatIndex);
        // Distance 1: flip bit 19
        assertThat(strategy.findTarget(1 << 19, owner, callerCoords, environment)).isEqualTo(flatIndex);
        // Distance 2: flip bits 0 and 1
        assertThat(strategy.findTarget(3, owner, callerCoords, environment)).isEqualTo(flatIndex);
        // Distance 3: flip bits 0, 1, and 2 - should NOT match
        assertThat(strategy.findTarget(7, owner, callerCoords, environment)).isEqualTo(-1);
    }

    @Test
    void testToroidalDistanceWrapAround() {
        int labelValue = 12345;
        int owner = 1;

        // Caller at (0, 0)
        // Label A at (1, 0) - direct distance = 1
        // Label B at (63, 0) - direct distance = 63, but toroidal = 1 (wrap around)
        // Both should have same score, so tie-break by owner

        LabelEntry entryA = new LabelEntry(1, 2, 0);  // owner 2
        strategy.addLabel(labelValue, entryA);

        LabelEntry entryB = new LabelEntry(63, 3, 0); // owner 3
        strategy.addLabel(labelValue, entryB);

        // Both have same toroidal distance (1), both foreign, lower owner wins
        int result = strategy.findTarget(labelValue, owner, callerCoords, environment);
        assertThat(result).isEqualTo(1); // owner 2 < owner 3
    }

    // === Stochastic selection tests ===

    @Test
    void testSelectionSpreadZeroIsDeterministic() {
        // selectionSpread=0 with a random provider injected must still behave deterministically
        PreExpandedHammingStrategy strat = new PreExpandedHammingStrategy(2, 100, 50, 0);
        IRandomProvider rng = new SeededRandomProvider(42).deriveFor("labelMatching", 0);
        strat.setRandomProvider(rng);

        int labelValue = 12345;
        int owner = 1;
        // Near label at flatIndex 1 (distance 1), far label at flatIndex 10 (distance 10)
        strat.addLabel(labelValue, new LabelEntry(1, owner, 0));
        strat.addLabel(labelValue, new LabelEntry(10, owner, 0));

        // Run 10 times — must always pick the closer label
        for (int i = 0; i < 10; i++) {
            int result = strat.findTarget(labelValue, owner, callerCoords, environment);
            assertThat(result).isEqualTo(1);
        }
    }

    @Test
    void testStochasticWithoutRandomProviderThrows() {
        PreExpandedHammingStrategy strat = new PreExpandedHammingStrategy(2, 100, 50, 50);
        // No setRandomProvider call

        int labelValue = 12345;
        int owner = 1;
        strat.addLabel(labelValue, new LabelEntry(1, owner, 0));

        assertThatThrownBy(() -> strat.findTarget(labelValue, owner, callerCoords, environment))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("selectionSpread");
    }

    @Test
    void testStochasticDistributesEqualDistanceDuplicates() {
        // Two labels at equal distance should be selected roughly equally
        PreExpandedHammingStrategy strat = new PreExpandedHammingStrategy(2, 100, 50, 50);
        IRandomProvider rng = new SeededRandomProvider(42).deriveFor("labelMatching", 0);
        strat.setRandomProvider(rng);

        int labelValue = 12345;
        int owner = 1;
        // flatIndex 1 = (0,1) distance 1, flatIndex 64 = (1,0) distance 1 — equal distance
        strat.addLabel(labelValue, new LabelEntry(1, owner, 0));
        strat.addLabel(labelValue, new LabelEntry(64, owner, 0));

        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            int result = strat.findTarget(labelValue, owner, callerCoords, environment);
            counts.merge(result, 1, Integer::sum);
        }

        // Both labels must be selected at least once (seeded RNG, deterministic result)
        assertThat(counts).containsKey(1);
        assertThat(counts).containsKey(64);
    }

    @Test
    void testStochasticFavorsCloserLabel() {
        // Near label should be selected more often than far label
        PreExpandedHammingStrategy strat = new PreExpandedHammingStrategy(2, 100, 50, 20);
        IRandomProvider rng = new SeededRandomProvider(42).deriveFor("labelMatching", 0);
        strat.setRandomProvider(rng);

        int labelValue = 12345;
        int owner = 1;
        // Near: flatIndex 1 (distance 1), Far: flatIndex 20 (distance 20)
        int nearIndex = 1;
        int farIndex = 20;
        strat.addLabel(labelValue, new LabelEntry(nearIndex, owner, 0));
        strat.addLabel(labelValue, new LabelEntry(farIndex, owner, 0));

        int nearCount = 0;
        for (int i = 0; i < 20; i++) {
            if (strat.findTarget(labelValue, owner, callerCoords, environment) == nearIndex) {
                nearCount++;
            }
        }

        // With selectionSpread=20: weight(1) = 10000*20/21 = 9523, weight(20) = 10000*20/40 = 5000
        // Near should be selected more often
        assertThat(nearCount).isGreaterThan(20 - nearCount);
    }

    @Test
    void testHighSpreadFlattensDistanceBias() {
        // With very high selectionSpread, even distant labels get a fair chance
        PreExpandedHammingStrategy strat = new PreExpandedHammingStrategy(2, 100, 50, 1000);
        IRandomProvider rng = new SeededRandomProvider(42).deriveFor("labelMatching", 0);
        strat.setRandomProvider(rng);

        int labelValue = 12345;
        int owner = 1;
        // Near: flatIndex 1 (distance 1), Far: flatIndex 20 (distance 20)
        strat.addLabel(labelValue, new LabelEntry(1, owner, 0));
        strat.addLabel(labelValue, new LabelEntry(20, owner, 0));

        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            int result = strat.findTarget(labelValue, owner, callerCoords, environment);
            counts.merge(result, 1, Integer::sum);
        }

        // With selectionSpread=1000: weights are nearly equal (9990 vs 9803)
        // Both must be selected at least once
        assertThat(counts).containsKey(1);
        assertThat(counts).containsKey(20);
    }

    @Test
    void testStochasticOnlyAffectsPhase1() {
        // Phase 2 (foreign/fuzzy labels) must remain deterministic
        PreExpandedHammingStrategy strat = new PreExpandedHammingStrategy(2, 100, 50, 50);
        IRandomProvider rng = new SeededRandomProvider(42).deriveFor("labelMatching", 0);
        strat.setRandomProvider(rng);

        int labelValue = 12345;
        int foreignOwner = 2;
        int callingOwner = 1;
        // Only foreign labels — Phase 1 finds nothing, Phase 2 runs
        strat.addLabel(labelValue, new LabelEntry(64, foreignOwner, 0));
        strat.addLabel(labelValue, new LabelEntry(65, foreignOwner, 0));

        int firstResult = strat.findTarget(labelValue, callingOwner, callerCoords, environment);
        for (int i = 0; i < 10; i++) {
            int result = strat.findTarget(labelValue, callingOwner, callerCoords, environment);
            assertThat(result).isEqualTo(firstResult);
        }
    }

    @Test
    void testConfigConstructorReadsSelectionSpread() {
        com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.parseString(
            "selectionSpread = 42");
        PreExpandedHammingStrategy strat = new PreExpandedHammingStrategy(config);
        assertThat(strat.getSelectionSpread()).isEqualTo(42);
    }

    @Test
    void testConfigConstructorDefaultsSelectionSpreadToZero() {
        com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.parseString(
            "tolerance = 2");
        PreExpandedHammingStrategy strat = new PreExpandedHammingStrategy(config);
        assertThat(strat.getSelectionSpread()).isEqualTo(0);
    }
}
