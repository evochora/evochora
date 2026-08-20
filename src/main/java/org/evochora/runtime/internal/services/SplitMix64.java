package org.evochora.runtime.internal.services;

/**
 * The SplitMix64 mixing function and its stream increment.
 * <p>
 * {@link #mix(long)} is a bijection on 64-bit values with full avalanche: every output bit depends
 * on every input bit, so structured inputs such as counters, identifiers or tick numbers are
 * turned into statistically independent-looking values. Advancing an input by
 * {@link #GOLDEN_GAMMA} between calls yields the SplitMix64 generator sequence.
 * <p>
 * Because the function is a bijection, distinct inputs always produce distinct outputs — a
 * property the simulation relies on to keep the randomness of different ticks and organisms
 * free of collisions by construction.
 */
public final class SplitMix64 {

    /**
     * The SplitMix64 stream increment: the 64-bit fractional part of the golden ratio, odd so
     * that repeated addition cycles through all 2^64 values.
     */
    public static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private SplitMix64() {
        // Static utility
    }

    /**
     * Mixes a 64-bit value.
     *
     * @param z the value to mix
     * @return the mixed value; distinct inputs yield distinct outputs
     */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
