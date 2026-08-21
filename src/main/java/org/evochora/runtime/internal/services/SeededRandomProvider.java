package org.evochora.runtime.internal.services;

import org.evochora.runtime.ParallelWave;
import org.evochora.runtime.ParallelWaveViolation;
import org.evochora.runtime.spi.IRandomProvider;
import org.apache.commons.math3.random.RandomAdaptor;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.Well19937c;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Default implementation of {@link IRandomProvider} backed by Apache Commons Math {@link Well19937c}.
 * <p>
 * Uses reflection to access Well19937c's internal state (v array and index) for perfect checkpoint
 * serialization. This ensures 100% reproducibility across checkpoints, which is critical for
 * long-running evolutionary simulations. Well19937c is an industry-standard, scientifically-validated
 * RNG with excellent statistical properties and a period of 2^19937-1.
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
    private final Well19937c rng;

    // Cached reflection fields for fast serialization (initialized once, used every tick)
    private final Field vField;
    private final Field indexField;

    /**
     * Creates a new seeded random provider.
     * @param seed The initial seed for the random number generator.
     */
    public SeededRandomProvider(long seed) {
        this.seed = seed;
        this.rng = new Well19937c(seed);

        // Initialize reflection fields once for fast repeated access
        try {
            this.vField = rng.getClass().getSuperclass().getDeclaredField("v");
            this.indexField = rng.getClass().getSuperclass().getDeclaredField("index");
            this.vField.setAccessible(true);
            this.indexField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Failed to initialize RNG reflection fields", e);
        }
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

    @Override
    public Random asJavaRandom() {
        return new RandomAdaptor(new GuardedGenerator(rng));
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
     * provider itself.
     */
    private static final class GuardedGenerator implements RandomGenerator {
        private final RandomGenerator delegate;

        GuardedGenerator(RandomGenerator delegate) {
            this.delegate = delegate;
        }

        @Override public void setSeed(int seed) { rejectDrawInParallelWave(); delegate.setSeed(seed); }
        @Override public void setSeed(int[] seed) { rejectDrawInParallelWave(); delegate.setSeed(seed); }
        @Override public void setSeed(long seed) { rejectDrawInParallelWave(); delegate.setSeed(seed); }
        @Override public void nextBytes(byte[] bytes) { rejectDrawInParallelWave(); delegate.nextBytes(bytes); }
        @Override public int nextInt() { rejectDrawInParallelWave(); return delegate.nextInt(); }
        @Override public int nextInt(int n) { rejectDrawInParallelWave(); return delegate.nextInt(n); }
        @Override public long nextLong() { rejectDrawInParallelWave(); return delegate.nextLong(); }
        @Override public boolean nextBoolean() { rejectDrawInParallelWave(); return delegate.nextBoolean(); }
        @Override public float nextFloat() { rejectDrawInParallelWave(); return delegate.nextFloat(); }
        @Override public double nextDouble() { rejectDrawInParallelWave(); return delegate.nextDouble(); }
        @Override public double nextGaussian() { rejectDrawInParallelWave(); return delegate.nextGaussian(); }
    }

    @Override
    public byte[] saveState() {
        try {
            // Access protected fields using cached Field objects (fast)
            int[] v = (int[]) vField.get(rng);
            int index = indexField.getInt(rng);

            // Serialize: 4 bytes for index + (v.length * 4) bytes for state array
            ByteBuffer buffer = ByteBuffer.allocate(4 + (v.length * 4));
            buffer.putInt(index);
            for (int value : v) {
                buffer.putInt(value);
            }
            return buffer.array();
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to serialize RNG state", e);
        }
    }

    @Override
    public void loadState(byte[] state) {
        if (state == null) {
            throw new IllegalArgumentException("RNG state cannot be null");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(state);
            int index = buffer.getInt();

            // Read the state array
            int[] v = (int[]) vField.get(rng);
            for (int i = 0; i < v.length; i++) {
                v[i] = buffer.getInt();
            }

            // Set both index and state array using cached Field objects (fast)
            indexField.setInt(rng, index);
            vField.set(rng, v);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to deserialize RNG state", e);
        }
    }
}


