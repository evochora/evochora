package org.evochora.runtime.model;

import java.util.Locale;

/**
 * The topology of an environment, in the words the simulation's configuration takes:
 * {@code TORUS} wraps around at every edge, {@code BOUND} ends there.
 * <p>
 * Every place that reads or writes the word goes through this type, so that a configuration, a
 * run's metadata and a command line accept the same words and no other.
 */
public enum Topology {

    /** The environment wraps around at every edge. */
    TORUS,

    /** The environment ends at its edges; a cell beyond them does not exist. */
    BOUND;

    /**
     * Reads a topology word, ignoring case.
     *
     * @param word the word, as a configuration or a command line writes it
     * @return the topology the word names
     * @throws IllegalArgumentException if the word is null or names no topology
     */
    public static Topology parse(String word) {
        if (word != null) {
            for (Topology topology : values()) {
                if (topology.name().equalsIgnoreCase(word)) {
                    return topology;
                }
            }
        }
        throw new IllegalArgumentException("'" + word + "' is no topology; "
                + TORUS.name().toLowerCase(Locale.ROOT) + " or " + BOUND.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the topology of an environment that wraps around or not.
     *
     * @param toroidal whether the environment wraps around at its edges
     * @return {@link #TORUS} if it does, {@link #BOUND} otherwise
     */
    public static Topology of(boolean toroidal) {
        return toroidal ? TORUS : BOUND;
    }

    /**
     * Returns whether this topology wraps around at the edges.
     *
     * @return {@code true} for {@link #TORUS}
     */
    public boolean isToroidal() {
        return this == TORUS;
    }
}
