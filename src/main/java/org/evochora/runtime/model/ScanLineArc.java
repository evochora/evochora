package org.evochora.runtime.model;

/**
 * The extent an organism occupies on one line of the world.
 * <p>
 * A line is the set of cells that share every coordinate but one; the cells an organism owns on it
 * are given by their coordinates along that one axis and need not be contiguous. The arc is the
 * stretch of the line the organism spans: it runs from the smallest to the largest owned
 * coordinate and contains whatever lies between them, gaps and foreign cells included.
 * <p>
 * On a toroidal axis the plain stretch from the smallest to the largest coordinate can be the
 * wrong one. A short body sitting across the world edge has cells near coordinate 0 and cells near
 * the end of the axis, so its smallest and largest coordinate lie on opposite sides of the world
 * although the body between them is short. Which stretch is the body and which is the outside is
 * then decided by the <em>largest gap</em>: of all gaps between neighbouring owned coordinates,
 * including the gap that runs across the edge from the largest coordinate back to the smallest,
 * the widest one is the outside. The arc begins at the coordinate on the far side of that gap and
 * ends at the coordinate on its near side, so it crosses the edge whenever the gap does not. Ties
 * are decided in favour of the gap between the smaller coordinates, and a gap across the edge
 * takes the outside only when it is strictly wider than every gap inside.
 * <p>
 * That rule is applied only where it can be needed, namely when the span from the smallest to the
 * largest coordinate exceeds half the axis (halved downwards, so a span of exactly half an even
 * axis does not reach it). A shorter span cannot reach around the edge, and its arc is simply the
 * smallest to the largest coordinate. On a bounded axis nothing wraps at all and the arc is always
 * the smallest to the largest coordinate.
 * <p>
 * The rule can be applied in two ways, and both allocate nothing. {@link #resolve} computes the
 * two ends of an arc from coordinates the caller has collected, and is a function of those
 * coordinates, the axis size and the topology alone: they are read out of a range of an array the
 * caller owns and the two ends are written into a {@link Result} the caller owns, so a caller that
 * keeps both around pays no allocation per call. {@link #isWithinArc} answers the narrower question
 * whether one position lies in the arc, and reads the owners along the line out of the world itself,
 * stopping as soon as the answer is certain. The two must agree on every line, which is what
 * {@code ScanLineArcTest} checks.
 */
public final class ScanLineArc {

    /**
     * The two ends of an arc, both inclusive.
     * <p>
     * An {@link #end} smaller than {@link #start} means the arc crosses the world edge: it runs
     * from {@code start} upwards to the last coordinate of the axis, continues at 0 and ends at
     * {@code end}.
     */
    public static final class Result {
        /** First coordinate of the arc. */
        public int start;
        /** Last coordinate of the arc. */
        public int end;
    }

    private ScanLineArc() {
        // Utility class - no instantiation
    }

    /**
     * Reports whether the arc on a line has to be determined from the individual coordinates.
     * <p>
     * It has to whenever the span from the smallest to the largest coordinate exceeds half the
     * axis on a toroidal world, because only such a span can reach around the world edge. Where
     * this reports {@code false} the arc is the smallest to the largest coordinate, and a caller
     * that already knows those two need not collect the coordinates in between.
     *
     * @param minCoordinate The smallest coordinate owned on the line.
     * @param maxCoordinate The largest coordinate owned on the line.
     * @param axisSize The size of the world along the line's axis.
     * @param toroidal Whether the world wraps around at the ends of that axis.
     * @return {@code true} if the largest-gap rule decides the arc, {@code false} if the arc is
     *         {@code minCoordinate} to {@code maxCoordinate}.
     */
    public static boolean largestGapRuleApplies(int minCoordinate, int maxCoordinate, int axisSize, boolean toroidal) {
        return toroidal && maxCoordinate - minCoordinate + 1 > axisSize / 2;
    }

    /**
     * Determines the arc an organism occupies on one line.
     * <p>
     * The coordinates are read from {@code sortedCoordinates[from]} to
     * {@code sortedCoordinates[from + count - 1]} and must be sorted ascending; duplicates are
     * harmless. The result is written into {@code out}, which is left holding an end smaller than
     * its start when the arc crosses the world edge.
     * <p>
     * The arc starts at the coordinate after the gap that counts as the outside and ends at the
     * coordinate before it, which is the previous entry of the sorted coordinates, or the last
     * entry when the outside is the gap across the edge. Two examples on an axis of size 12, where
     * any span wider than 6 lets the rule decide:
     * <ul>
     *   <li>coordinates 2, 4, 8: the gaps inside are 2 and 4, the gap across the edge is 6 and
     *       wider than both, so the edge is the outside and the arc runs from 2 to 8;</li>
     *   <li>coordinates 0, 5, 10: the gaps inside are 5 and 5, the gap across the edge is 2, so
     *       the first widest gap inside, between 0 and 5, is the outside and the arc runs from 5
     *       across the edge to 0 — reported as start 5, end 0.</li>
     * </ul>
     *
     * @param sortedCoordinates Array holding the owned coordinates along the line, sorted ascending.
     * @param from Index of the first coordinate to read.
     * @param count Number of coordinates to read; at least one.
     * @param axisSize The size of the world along the line's axis.
     * @param toroidal Whether the world wraps around at the ends of that axis.
     * @param out Receives the two ends of the arc.
     */
    public static void resolve(int[] sortedCoordinates, int from, int count, int axisSize, boolean toroidal,
                               Result out) {
        int minCoordinate = sortedCoordinates[from];
        int maxCoordinate = sortedCoordinates[from + count - 1];

        if (!largestGapRuleApplies(minCoordinate, maxCoordinate, axisSize, toroidal)) {
            out.start = minCoordinate;
            out.end = maxCoordinate;
            return;
        }

        int largestGap = 0;
        int startIndex = 0;
        for (int i = 1; i < count; i++) {
            int gap = sortedCoordinates[from + i] - sortedCoordinates[from + i - 1];
            if (gap > largestGap) {
                largestGap = gap;
                startIndex = i;
            }
        }

        int gapAcrossTheEdge = minCoordinate + axisSize - maxCoordinate;
        if (gapAcrossTheEdge > largestGap) {
            startIndex = 0;
        }

        out.start = sortedCoordinates[from + startIndex];
        out.end = sortedCoordinates[from + (startIndex - 1 + count) % count];
    }

    /**
     * Reports whether a position lies in the arc an organism occupies on the line through it.
     * <p>
     * The line runs along {@code axis} through {@code position}: it holds the cells that share
     * every other coordinate with it. The arc on that line is the one the class documentation
     * defines, so a position on a cell of the organism always lies in it, a position between two
     * of its cells lies in it as well, and a line the organism owns no cell on has no arc to lie
     * in.
     * <p>
     * Rather than collecting the owned coordinates, this walks the line outwards from the position
     * and stops as soon as the answer is certain. It first looks for the organism's cells on either
     * side, which bound the stretch of free cells the position sits in, and then examines the rest
     * of the line only as far as it takes to find out whether a wider stretch exists somewhere
     * else: a stretch wider than half the axis is the widest there can be, and one that no
     * remaining piece of the line could still exceed is the widest as well. A position on an owned
     * cell is answered without reading a second cell.
     * <p>
     * Allocation-free and free of state, and it only reads, so it may be called from the parallel
     * wave. A position outside a bounded world names no cell and lies in no arc, which is answered
     * as {@code false} rather than rejected, the way the coordinate accessors of
     * {@link Environment} report the owner of such a position as unowned.
     *
     * @param environment The world the owners are read from.
     * @param position The position to test, one component per dimension.
     * @param axis The dimension the line runs along.
     * @param ownerId The organism whose cells form the arc.
     * @return {@code true} if the position lies in that organism's arc on the line.
     * @throws IllegalArgumentException if the position does not have one component per dimension.
     */
    public static boolean isWithinArc(Environment environment, int[] position, int axis, int ownerId) {
        int dimensions = environment.dimensions();
        if (position.length != dimensions) {
            throw new IllegalArgumentException("Coordinate dimensions do not match world dimensions.");
        }
        for (int i = 0; i < dimensions; i++) {
            if (position[i] < 0 || position[i] >= environment.axisSize(i)) {
                return false;
            }
        }

        int index = environment.getIndexFromCoordinate(position);
        if (environment.getOwnerIdByIndex(index) == ownerId) {
            return true;
        }

        int axisSize = environment.axisSize(axis);
        if (!environment.getProperties().isToroidal()) {
            return ownedCellFound(environment, index, axis, false, ownerId)
                    && ownedCellFound(environment, index, axis, true, ownerId);
        }

        // The free cells around the position form one stretch, bounded by the nearest owned cell
        // on either side. Its width is the distance between those two cells, and the search for
        // them stops once that width must exceed half the axis: all stretches of a line add up to
        // the axis, so one wider than half of it is the widest, and the position is outside.
        int forward = 0;
        int forwardIndex = index;
        boolean forwardFound = false;
        // One more step is worth taking only while the stretch it could close still stays within
        // half the axis; the cell behind the position accounts for the one step of the other side.
        while (2 * (forward + 2) <= axisSize) {
            forwardIndex = environment.stepIndex(forwardIndex, axis, true);
            forward++;
            if (environment.getOwnerIdByIndex(forwardIndex) == ownerId) {
                forwardFound = true;
                break;
            }
        }
        if (!forwardFound) {
            return false;
        }

        int backward = 0;
        int backwardIndex = index;
        boolean backwardFound = false;
        while (2 * (forward + backward + 1) <= axisSize) {
            backwardIndex = environment.stepIndex(backwardIndex, axis, false);
            backward++;
            if (environment.getOwnerIdByIndex(backwardIndex) == ownerId) {
                backwardFound = true;
                break;
            }
        }
        if (!backwardFound) {
            return false;
        }

        int coordinate = position[axis];
        int ownStart = coordinate - backward;
        if (ownStart < 0) {
            ownStart += axisSize;
        }
        int ownEnd = coordinate + forward;
        if (ownEnd >= axisSize) {
            ownEnd -= axisSize;
        }
        return widerStretchExists(environment, forwardIndex, ownEnd, axis, ownerId, axisSize,
                forward + backward, ownStart);
    }

    /**
     * Walks a line in one direction until a cell of the organism is reached or the world ends.
     *
     * @param environment The world the owners are read from.
     * @param fromIndex The layout index of the cell to start from, which is not examined.
     * @param axis The dimension the line runs along.
     * @param forward Whether to walk towards higher coordinates.
     * @param ownerId The organism to look for.
     * @return {@code true} if a cell of that organism lies in that direction.
     */
    private static boolean ownedCellFound(Environment environment, int fromIndex, int axis, boolean forward,
                                          int ownerId) {
        int index = fromIndex;
        while (true) {
            index = environment.stepIndex(index, axis, forward);
            if (index < 0) {
                return false;
            }
            if (environment.getOwnerIdByIndex(index) == ownerId) {
                return true;
            }
        }
    }

    /**
     * Reports whether a toroidal line holds a stretch of free cells that takes the outside from the
     * one the tested position sits in.
     * <p>
     * The walk starts at the owned cell that ends the position's own stretch and follows the line
     * away from it until it reaches the cell that begins that stretch again, measuring every other
     * stretch on the way. It ends early where the outcome is settled: a wider stretch decides
     * against the position's own, and a remaining piece of line too short to hold one decides for
     * it.
     *
     * @param environment The world the owners are read from.
     * @param fromIndex The layout index of the owned cell the position's stretch ends at.
     * @param fromCoordinate The coordinate of that cell along the axis.
     * @param axis The dimension the line runs along.
     * @param ownerId The organism whose cells bound the stretches.
     * @param axisSize The size of the world along that axis.
     * @param ownStretch The width of the stretch the position sits in.
     * @param ownStart The coordinate of the owned cell that stretch begins at.
     * @return {@code true} if another stretch takes the outside, which leaves the position inside.
     */
    private static boolean widerStretchExists(Environment environment, int fromIndex, int fromCoordinate, int axis,
                                              int ownerId, int axisSize, int ownStretch, int ownStart) {
        int remaining = axisSize - ownStretch;
        int index = fromIndex;
        int coordinate = fromCoordinate;
        int previousOwnedOffset = 0;
        int previousOwnedCoordinate = fromCoordinate;
        for (int offset = 1; offset <= remaining; offset++) {
            index = environment.stepIndex(index, axis, true);
            coordinate = coordinate + 1 == axisSize ? 0 : coordinate + 1;
            if (environment.getOwnerIdByIndex(index) != ownerId) {
                continue;
            }
            int stretch = offset - previousOwnedOffset;
            if (stretch > ownStretch) {
                return true;
            }
            if (stretch == ownStretch
                    && takesTheOutside(previousOwnedCoordinate, ownStart, stretch, axisSize)) {
                return true;
            }
            previousOwnedOffset = offset;
            previousOwnedCoordinate = coordinate;
            if (remaining - previousOwnedOffset < ownStretch) {
                break;
            }
        }
        return false;
    }

    /**
     * Decides which of two equally wide stretches of free cells counts as the outside.
     * <p>
     * This is the tie of the largest-gap rule the class documentation states: a stretch that runs
     * across the world edge loses against one that does not, and between two that both stay inside
     * the axis the one at the smaller coordinates wins.
     *
     * @param start The coordinate the stretch in question begins at.
     * @param otherStart The coordinate the stretch it is compared with begins at.
     * @param stretch The width both of them have.
     * @param axisSize The size of the world along the axis.
     * @return {@code true} if the stretch beginning at {@code start} counts as the outside.
     */
    private static boolean takesTheOutside(int start, int otherStart, int stretch, int axisSize) {
        if (start + stretch >= axisSize) {
            return false;
        }
        if (otherStart + stretch >= axisSize) {
            return true;
        }
        return start < otherStart;
    }
}
