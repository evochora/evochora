package org.evochora.runtime.model;

/**
 * The contents of a location register: a position in the world, or no position at all.
 * <p>
 * A position has one component per world dimension. {@link #NONE} has no components, so it is not
 * a position and cannot be confused with one — the world origin included, which stays an ordinary
 * position that can be stored, handed out and jumped to like any other. A world always has at least
 * one dimension, which {@link GridLayout} enforces; that is what keeps the two apart.
 * <p>
 * The state travels wherever a location register's contents travel: a copy between registers, the
 * location stack, and with it the location parameters of a procedure. What refuses it are the
 * instructions that read a location value as a coordinate — no instruction hands it out as a
 * vector, and none jumps to it.
 * <p>
 * Thread safety: stateless and therefore safe for concurrent use.
 */
public final class LocationValue {

    /**
     * A location register that holds no position.
     * <p>
     * The instance is shared and safe to alias across registers and stack entries: an array without
     * components has nothing that could be written into it.
     */
    public static final int[] NONE = new int[0];

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private LocationValue() {
        // Utility class - prevent instantiation
    }

    /**
     * Tells the two states of a location value apart.
     * <p>
     * Recognition is by length, not by identity: a location register is copied with
     * {@code clone()}, and a cloned empty array is a different instance holding the same state.
     *
     * @param value the contents of a location register or of a location stack entry
     * @return {@code true} if the value holds no position
     */
    public static boolean isNone(int[] value) {
        return value.length == 0;
    }
}
