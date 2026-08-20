package org.evochora.runtime.internal.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.evochora.runtime.TickWorkerPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SeededRandomProvider}: seed exposure, checkpoint round trip, and the
 * rejection of draws made inside the parallel wave of a tick.
 */
@Tag("unit")
class SeededRandomProviderTest {

    private TickWorkerPool pool;

    @AfterEach
    void shutdownPool() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Test
    void seed_isTheConstructorSeed() {
        assertThat(new SeededRandomProvider(42L).seed()).isEqualTo(42L);
    }

    @Test
    void sameSeed_producesSameSequence() {
        SeededRandomProvider a = new SeededRandomProvider(7L);
        SeededRandomProvider b = new SeededRandomProvider(7L);

        assertThat(draw(a, 16)).isEqualTo(draw(b, 16));
    }

    @Test
    void loadState_restoresThePositionInTheSequence() {
        SeededRandomProvider original = new SeededRandomProvider(7L);
        draw(original, 5);
        byte[] state = original.saveState();
        List<Integer> continuation = draw(original, 16);

        SeededRandomProvider restored = new SeededRandomProvider(7L);
        restored.loadState(state);

        assertThat(draw(restored, 16)).isEqualTo(continuation);
        assertThat(restored.seed()).as("loading state keeps the seed").isEqualTo(7L);
    }

    @Test
    void draws_areRejectedInsideTheParallelWave() {
        SeededRandomProvider provider = new SeededRandomProvider(7L);
        Random javaView = provider.asJavaRandom();
        pool = new TickWorkerPool(2);

        assertThatThrownBy(() -> pool.dispatch(2, (from, to) -> provider.nextInt(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Organism.getRandom()");
        assertThatThrownBy(() -> pool.dispatch(2, (from, to) -> provider.nextDouble()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pool.dispatch(2, (from, to) -> javaView.nextInt(10)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pool.dispatch(2, (from, to) -> javaView.nextDouble()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pool.dispatch(2, (from, to) -> javaView.setSeed(1L)))
                .as("reseeding the shared generator from the wave is rejected too")
                .isInstanceOf(IllegalStateException.class);

        // Outside the wave the same provider works again
        assertThat(provider.nextInt(10)).isBetween(0, 9);
        assertThat(javaView.nextInt(10)).isBetween(0, 9);
    }

    private static List<Integer> draw(SeededRandomProvider provider, int count) {
        List<Integer> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(provider.nextInt(1_000_000));
        }
        return values;
    }
}
