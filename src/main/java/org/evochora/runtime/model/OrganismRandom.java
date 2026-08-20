package org.evochora.runtime.model;

import org.evochora.runtime.internal.services.SplitMix64;

/**
 * The random number source of a single organism.
 * <p>
 * Values are not drawn from a shared generator but <em>computed</em>: the n-th value an organism
 * obtains in a tick is a pure function of the simulation seed, the tick number, the organism ID
 * and n. The simulation folds seed and tick number into a per-tick seed
 * ({@link org.evochora.runtime.Simulation#getTickSeed()}); {@link #beginTick(long)} combines it
 * with the organism's own salt into the seed of this tick's stream and restarts the draw index.
 * <p>
 * Consequences:
 * <ul>
 *   <li>The same organism receives the same values in the same tick regardless of which thread
 *       executes it or how many organisms run concurrently — randomness never depends on
 *       scheduling.</li>
 *   <li>Nothing needs to be persisted: after a resume the tick number and organism ID are known,
 *       and the draw index starts at zero at the beginning of every tick.</li>
 *   <li>Streams of different organisms and different ticks are distinct by construction, because
 *       seed, tick and ID enter through bijective mixing.</li>
 * </ul>
 * Each value costs a handful of arithmetic operations on organism-local fields; no shared memory
 * is touched. Not thread-safe: an instance belongs to exactly one organism and is used only by
 * the thread currently executing that organism.
 */
public final class OrganismRandom {

    /** Constant contribution of the organism ID to every stream of this organism. */
    private final long organismSalt;

    /** Seed of this organism's stream in the current tick. */
    private long streamSeed;

    /** Number of values already produced in the current tick. */
    private long drawIndex;

    /**
     * Creates the random source of an organism.
     *
     * @param organismId the organism's ID
     */
    public OrganismRandom(int organismId) {
        this.organismSalt = SplitMix64.mix(organismId);
    }

    /**
     * Positions the stream at the beginning of a tick.
     *
     * @param tickSeed the simulation's seed for the current tick
     */
    public void beginTick(long tickSeed) {
        this.streamSeed = SplitMix64.mix(tickSeed ^ organismSalt);
        this.drawIndex = 0;
    }

    /**
     * Returns the seed of this organism's stream in the current tick.
     * <p>
     * Because it is a pure function of simulation seed, tick and organism ID, it doubles as a
     * per-tick priority that is fair over time and independent of scheduling.
     *
     * @return the stream seed of the current tick
     */
    public long tickStreamSeed() {
        return streamSeed;
    }

    /**
     * Returns the next 64-bit value of the stream.
     *
     * @return a pseudo-random long
     */
    public long nextLong() {
        drawIndex++;
        return SplitMix64.mix(streamSeed + drawIndex * SplitMix64.GOLDEN_GAMMA);
    }

    /**
     * Returns a uniformly distributed value in {@code [0, bound)}.
     * <p>
     * Uses multiply-shift range reduction with rejection of the biased remainder, so every value
     * in the range is equally likely.
     *
     * @param bound the exclusive upper bound, must be positive
     * @return a value in {@code [0, bound)}
     * @throws IllegalArgumentException if {@code bound <= 0}
     */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive, got " + bound);
        }
        long range = bound;
        long product = (nextLong() >>> 32) * range;
        long low = product & 0xFFFFFFFFL;
        if (low < range) {
            long threshold = (0x1_0000_0000L - range) % range;
            while (low < threshold) {
                product = (nextLong() >>> 32) * range;
                low = product & 0xFFFFFFFFL;
            }
        }
        return (int) (product >>> 32);
    }

    /**
     * Returns a uniformly distributed value in {@code [0.0, 1.0)}.
     *
     * @return a pseudo-random double
     */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /**
     * Returns a uniformly distributed boolean.
     *
     * @return a pseudo-random boolean
     */
    public boolean nextBoolean() {
        return nextLong() < 0;
    }
}
