package org.evochora.runtime.spi;

import java.util.Random;

/**
 * Provides deterministic randomness scoped to a Simulation.
 * Implementations should be pure with respect to the provided seed and
 * support derivation of child providers for independent sub-streams.
 * <p>
 * Implements {@link ISerializable} to support simulation checkpointing and resume.
 * </p>
 */
public interface IRandomProvider extends ISerializable {

    /**
     * Returns a random integer in the range [0, bound).
     *
     * @param bound exclusive upper bound, must be > 0
     * @return the random int
     */
    int nextInt(int bound);

    /**
     * Returns a random double in the range [0.0, 1.0).
     *
     * @return the random double
     */
    double nextDouble();

    /**
     * Provides access to an underlying {@link Random} instance for APIs that require it
     * (e.g., {@code Collections.shuffle}).
     *
     * @return the Random instance
     */
    Random asJavaRandom();

    /**
     * Creates a derived provider that is deterministically based on this provider and the given scope/key.
     * Use this to create independent sub-streams (e.g., per organism or per strategy instance).
     *
     * @param scope a stable, descriptive scope name (e.g., "organism", "energyStrategy")
     * @param key a stable numeric key (e.g., organism id, strategy index)
     * @return a derived random provider
     */
    IRandomProvider deriveFor(String scope, long key);
}


