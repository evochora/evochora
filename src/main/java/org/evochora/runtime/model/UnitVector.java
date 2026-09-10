package org.evochora.runtime.model;

/**
 * Maps an arbitrary vector to the unit vector nearest to it in angle.
 * <p>
 * A unit vector has exactly one component of ±1 and zeros elsewhere; it names one of the
 * {@code 2 · dimensions} directions a step can take. Instructions that address a neighbouring cell,
 * that set a direction of travel, that place a child or that convert a direction into a bit mask
 * need such a vector, and this class is where an arbitrary one becomes it.
 * <p>
 * <strong>Which unit vector is nearest.</strong> For a vector {@code v} and a candidate
 * {@code s · ê_i} the cosine of the angle between them is {@code s · v_i / |v|}. The length
 * {@code |v|} is the same for every candidate and cancels out, so the nearest unit vector is the
 * one on the axis with the largest absolute component, carrying that component's sign. No square
 * root and no trigonometry are involved.
 * <p>
 * <strong>Ties.</strong> Several axes can share the largest absolute value, and then no candidate
 * is nearer than another. Among the tied axes, in ascending order, the one at position
 * {@code (number of tied axes whose component is negative) mod (number of tied axes)} is chosen.
 * For two tied axes this distributes the four sign combinations evenly over both axes, so that no
 * axis is preferred over the generations of a mutating program — the property the rule exists for.
 * A single mutation of a unit vector always produces exactly two tied axes. Three or more tied axes
 * need several mutations of one vector and are not perfectly balanced.
 * <p>
 * <strong>Allocation.</strong> A vector that already is a unit vector is returned unchanged and as
 * the same object, so the common case allocates nothing. The argument is never written into: it may
 * be an organism's live register or an entry still held on its data stack.
 * <p>
 * Thread safety: stateless and therefore safe for concurrent use.
 */
final class UnitVector {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private UnitVector() {
        // Utility class - prevent instantiation
    }

    /**
     * Returns the unit vector nearest in angle to the given vector.
     * <p>
     * The zero vector carries no direction and therefore has no nearest unit vector; the caller
     * decides what to use instead. Every other vector yields one of the {@code 2 · length}
     * directions, by the rule described on this class.
     *
     * @param vector The vector to map. Not modified. Must not be {@code null}
     * @return The argument itself if it already is a unit vector, {@code null} if it is the zero
     *         vector, and otherwise a newly allocated unit vector of the same length
     */
    static int[] nearest(int[] vector) {
        int magnitudeSum = 0;
        for (int component : vector) {
            magnitudeSum += Math.abs(component);
        }
        if (magnitudeSum == 1) {
            return vector;
        }
        if (magnitudeSum == 0) {
            return null;
        }

        // The tied axes are collected while the largest magnitude is still being found: a larger
        // magnitude discards the candidates seen so far, an equal one joins them.
        int largest = -1;
        int candidates = 0;
        int negativeCandidates = 0;
        for (int component : vector) {
            int magnitude = Math.abs(component);
            if (magnitude > largest) {
                largest = magnitude;
                candidates = 1;
                negativeCandidates = component < 0 ? 1 : 0;
            } else if (magnitude == largest) {
                candidates++;
                if (component < 0) {
                    negativeCandidates++;
                }
            }
        }

        // The count of negative candidates lies between zero and the number of candidates, so the
        // position is that count itself, except when every candidate is negative and it wraps to
        // the first.
        int pick = negativeCandidates == candidates ? 0 : negativeCandidates;
        int axis = -1;
        int seen = 0;
        for (int i = 0; i < vector.length; i++) {
            if (Math.abs(vector[i]) == largest) {
                if (seen == pick) {
                    axis = i;
                    break;
                }
                seen++;
            }
        }

        int[] nearest = new int[vector.length];
        nearest[axis] = vector[axis] > 0 ? 1 : -1;
        return nearest;
    }
}
