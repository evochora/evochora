package org.evochora.runtime.internal.services;

import org.apache.commons.rng.RestorableUniformRandomProvider;
import org.apache.commons.rng.core.RandomProviderDefaultState;
import org.apache.commons.rng.simple.RandomSource;
import org.evochora.runtime.ParallelWave;
import org.evochora.runtime.ParallelWaveViolation;
import org.evochora.runtime.spi.IRandomProvider;

import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Default implementation of {@link IRandomProvider} backed by the Well19937c generator of Apache
 * Commons RNG.
 * <p>
 * Well19937c is a scientifically validated generator with a period of 2^19937-1. Commons RNG
 * saves and restores its complete state through its own API, so a simulation resumed from a
 * checkpoint continues with exactly the sequence it would have drawn without the interruption.
 * </p>
 * <p>
 * Every draw — including those made through {@link #asJavaRandom()} — is rejected with a
 * {@link ParallelWaveViolation} while the calling thread executes the parallel wave of a tick
 * ({@link ParallelWave#isActive()}). A value drawn there would be handed out in
 * scheduling order and make the run irreproducible; code in that phase uses the organism's own
 * {@link org.evochora.runtime.model.OrganismRandom} instead.
 * </p>
 */
public final class SeededRandomProvider implements IRandomProvider {

    private final long seed;
    private final RestorableUniformRandomProvider rng;

    /**
     * Creates a new seeded random provider.
     * @param seed The initial seed for the random number generator.
     */
    public SeededRandomProvider(long seed) {
        this.seed = seed;
        this.rng = RandomSource.WELL_19937_C.create(seed);
    }

    @Override
    public long seed() {
        return seed;
    }

    @Override
    public int nextInt(int bound) {
        rejectDrawInParallelWave();
        return rng.nextInt(bound);
    }

    @Override
    public double nextDouble() {
        rejectDrawInParallelWave();
        return rng.nextDouble();
    }

    /**
     * {@inheritDoc}
     * <p>
     * The view draws from this provider's generator and does not support {@link Random#setSeed}:
     * reseeding the generator shared by the whole simulation would break its reproducibility.
     * </p>
     */
    @Override
    public Random asJavaRandom() {
        return Random.from(new GuardedGenerator(rng));
    }

    private static void rejectDrawInParallelWave() {
        if (ParallelWave.isActive()) {
            throw new ParallelWaveViolation(
                    "IRandomProvider is reserved for the sequential parts of a tick (tick plugins, birth and "
                    + "death handlers). Code running for an organism inside the parallel wave - instructions "
                    + "and instruction interceptors - must use Organism.getRandom() instead.");
        }
    }

    /**
     * Delegates to the underlying generator after rejecting draws made inside the parallel wave,
     * so that the {@link Random} view handed out by {@link #asJavaRandom()} is guarded like the
     * provider itself. The draws the generator offers are delegated to it, so the view yields the
     * same values the provider would.
     */
    private static final class GuardedGenerator implements RandomGenerator {
        private final RestorableUniformRandomProvider delegate;

        GuardedGenerator(RestorableUniformRandomProvider delegate) {
            this.delegate = delegate;
        }

        @Override public void nextBytes(byte[] bytes) { rejectDrawInParallelWave(); delegate.nextBytes(bytes); }
        @Override public int nextInt() { rejectDrawInParallelWave(); return delegate.nextInt(); }
        @Override public int nextInt(int bound) { rejectDrawInParallelWave(); return delegate.nextInt(bound); }
        @Override public long nextLong() { rejectDrawInParallelWave(); return delegate.nextLong(); }
        @Override public boolean nextBoolean() { rejectDrawInParallelWave(); return delegate.nextBoolean(); }
        @Override public float nextFloat() { rejectDrawInParallelWave(); return delegate.nextFloat(); }
        @Override public double nextDouble() { rejectDrawInParallelWave(); return delegate.nextDouble(); }
    }

    @Override
    public byte[] saveState() {
        return ((RandomProviderDefaultState) rng.saveState()).getState();
    }

    @Override
    public void loadState(byte[] state) {
        if (state == null) {
            throw new IllegalArgumentException("RNG state cannot be null");
        }
        rng.restoreState(new RandomProviderDefaultState(state));
    }
}
