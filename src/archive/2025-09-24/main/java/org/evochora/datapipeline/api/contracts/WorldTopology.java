package org.evochora.datapipeline.api.contracts;

/**
 * Defines the topology of the world's boundaries.
 */
public enum WorldTopology {
    /**
     * A world where the edges wrap around to the opposite side.
     */
    TORUS,

    /**
     * A world with hard boundaries that cannot be crossed.
     */
    BOUNDED
}
