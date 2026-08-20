package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OrganismRandom}: values are a pure function of tick seed, organism ID
 * and draw index, and bounded values are uniform over their range.
 */
@Tag("unit")
class OrganismRandomTest {

    private static final long TICK_SEED = 0x5DEECE66DL;

    @Test
    void sameTickSeedAndId_produceSameSequence() {
        OrganismRandom a = new OrganismRandom(7);
        OrganismRandom b = new OrganismRandom(7);
        a.beginTick(TICK_SEED);
        b.beginTick(TICK_SEED);

        assertThat(draw(a, 16)).isEqualTo(draw(b, 16));
    }

    @Test
    void beginTick_restartsTheSequence() {
        OrganismRandom random = new OrganismRandom(7);
        random.beginTick(TICK_SEED);
        List<Long> first = draw(random, 16);

        random.beginTick(TICK_SEED);

        assertThat(draw(random, 16)).isEqualTo(first);
    }

    @Test
    void differentIds_produceDifferentSequences() {
        OrganismRandom a = new OrganismRandom(1);
        OrganismRandom b = new OrganismRandom(2);
        a.beginTick(TICK_SEED);
        b.beginTick(TICK_SEED);

        assertThat(draw(a, 4)).isNotEqualTo(draw(b, 4));
        assertThat(a.tickStreamSeed()).isNotEqualTo(b.tickStreamSeed());
    }

    @Test
    void differentTickSeeds_produceDifferentSequences() {
        OrganismRandom random = new OrganismRandom(1);
        random.beginTick(TICK_SEED);
        List<Long> tickOne = draw(random, 4);
        long seedOne = random.tickStreamSeed();

        random.beginTick(TICK_SEED + 1);

        assertThat(draw(random, 4)).isNotEqualTo(tickOne);
        assertThat(random.tickStreamSeed()).isNotEqualTo(seedOne);
    }

    @Test
    void nextInt_staysWithinBound() {
        OrganismRandom random = new OrganismRandom(3);
        random.beginTick(TICK_SEED);
        int[] bounds = {1, 2, 3, 7, 1000, Integer.MAX_VALUE};

        for (int bound : bounds) {
            for (int i = 0; i < 1000; i++) {
                assertThat(random.nextInt(bound)).isBetween(0, bound - 1);
            }
        }
    }

    @Test
    void nextInt_coversTheWholeRangeRoughlyUniformly() {
        OrganismRandom random = new OrganismRandom(3);
        random.beginTick(TICK_SEED);
        int bound = 10;
        int draws = 100_000;
        int[] histogram = new int[bound];

        for (int i = 0; i < draws; i++) {
            histogram[random.nextInt(bound)]++;
        }

        int expected = draws / bound;
        for (int value = 0; value < bound; value++) {
            assertThat(histogram[value])
                    .as("count of value %d", value)
                    .isBetween((int) (expected * 0.95), (int) (expected * 1.05));
        }
    }

    @Test
    void nextInt_rejectsNonPositiveBound() {
        OrganismRandom random = new OrganismRandom(3);
        random.beginTick(TICK_SEED);

        assertThatThrownBy(() -> random.nextInt(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> random.nextInt(-5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nextDouble_staysWithinUnitInterval() {
        OrganismRandom random = new OrganismRandom(3);
        random.beginTick(TICK_SEED);

        for (int i = 0; i < 10_000; i++) {
            assertThat(random.nextDouble()).isGreaterThanOrEqualTo(0.0).isLessThan(1.0);
        }
    }

    @Test
    void nextBoolean_producesBothValues() {
        OrganismRandom random = new OrganismRandom(3);
        random.beginTick(TICK_SEED);
        int trues = 0;
        int draws = 10_000;

        for (int i = 0; i < draws; i++) {
            if (random.nextBoolean()) trues++;
        }

        assertThat(trues).isBetween((int) (draws * 0.45), (int) (draws * 0.55));
    }

    private static List<Long> draw(OrganismRandom random, int count) {
        List<Long> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(random.nextLong());
        }
        return values;
    }
}
