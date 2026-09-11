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
 * An arc is a function of the coordinates, the axis size and the topology alone, and it allocates
 * nothing: the coordinates are read out of a range of an array the caller owns and the two ends
 * are written into a {@link Result} the caller owns, so a caller that keeps both around pays no
 * allocation per call.
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
}
