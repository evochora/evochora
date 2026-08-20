package org.evochora.runtime.spi;

import java.util.Random;

/**
 * Provides deterministic randomness scoped to a Simulation.
 * Implementations must be pure with respect to the provided seed.
 * <p>
 * This is the randomness of the sequential parts of a tick (tick plugins, birth and death
 * handlers), consumed in their deterministic call order. Code that runs for a specific organism
 * inside the parallel wave uses the organism's own
 * {@link org.evochora.runtime.model.OrganismRandom} instead; implementations reject draws made
 * there with an {@link IllegalStateException}.
 * <p>
 * Implements {@link ISerializable} to support simulation checkpointing and resume.
 * </p>
 */
public interface IRandomProvider extends ISerializable {

    /**
     * Returns the root seed of this provider.
     * <p>
     * The seed identifies the run's randomness as a whole: it is fixed at construction and is not
     * affected by drawing values or by {@link #loadState(byte[])}.
     *
     * @return the seed this provider was created with
     */
    long seed();

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
}


