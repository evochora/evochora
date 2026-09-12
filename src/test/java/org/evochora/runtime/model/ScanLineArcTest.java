package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests the extent an organism occupies on one line of the world.
 * <p>
 * Every case is a hand-written set of coordinates on an axis of 32 cells, so that "half the axis"
 * is 16 and the cases around it can be written down exactly. The cases cover a body inside the
 * world, a body across the world edge, the two spans on either side of the half-axis threshold,
 * the same coordinates in a bounded world, a single cell, and bodies with gaps in them. They need
 * no environment and no simulation.
 */
@Tag("unit")
class ScanLineArcTest {

    private static final int AXIS = 32;

    private final ScanLineArc.Result arc = new ScanLineArc.Result();

    /** Resolves the arc over the whole of {@code coordinates}, which must be sorted ascending. */
    private void resolve(boolean toroidal, int... coordinates) {
        ScanLineArc.resolve(coordinates, 0, coordinates.length, AXIS, toroidal, arc);
    }

    /**
     * A body that lies inside the world spans from its smallest to its largest coordinate.
     */
    @Test
    void aBodyInsideTheWorldSpansFromItsSmallestToItsLargestCoordinate() {
        resolve(true, 4, 5, 6, 7, 10);

        assertThat(arc.start).isEqualTo(4);
        assertThat(arc.end).isEqualTo(10);
    }

    /**
     * A body whose cells sit near both ends of the axis is read as one body across the world edge:
     * the arc starts at the end of the axis, crosses the edge and ends near 0.
     */
    @Test
    void aBodyAcrossTheWorldEdgeWrapsAroundIt() {
        resolve(true, 0, 1, 2, 29, 30, 31);

        assertThat(arc.start).isEqualTo(29);
        assertThat(arc.end).isEqualTo(2);
    }

    /**
     * A span of exactly half the axis is still read as the plain stretch from the smallest to the
     * largest coordinate; the largest gap decides only above that.
     */
    @Test
    void aSpanOfExactlyHalfTheAxisIsNotDecidedByTheLargestGap() {
        assertThat(ScanLineArc.largestGapRuleApplies(0, 15, AXIS, true)).isFalse();

        resolve(true, 0, 5, 15);

        assertThat(arc.start).isEqualTo(0);
        assertThat(arc.end).isEqualTo(15);
    }

    /**
     * One cell more and the largest gap decides — which here is the gap across the world edge, so
     * the arc comes out as the plain stretch all the same.
     */
    @Test
    void aSpanOneCellOverHalfTheAxisIsDecidedByTheLargestGap() {
        assertThat(ScanLineArc.largestGapRuleApplies(0, 16, AXIS, true)).isTrue();

        resolve(true, 0, 5, 16);

        assertThat(arc.start).isEqualTo(0);
        assertThat(arc.end).isEqualTo(16);
    }

    /**
     * Where a gap inside the body is exactly as wide as the gap across the edge, the gap inside is
     * taken as the outside and the arc crosses the edge.
     */
    @Test
    void aTieBetweenAGapInsideAndTheGapAcrossTheEdgeGoesToTheGapInside() {
        resolve(true, 0, 16);

        assertThat(arc.start).isEqualTo(16);
        assertThat(arc.end).isEqualTo(0);
    }

    /**
     * In a bounded world nothing wraps: the same coordinates that describe a body across the edge
     * of a toroidal world describe a body spanning almost the whole of a bounded one.
     */
    @Test
    void aBoundedWorldNeverWraps() {
        assertThat(ScanLineArc.largestGapRuleApplies(0, 31, AXIS, false)).isFalse();

        resolve(false, 0, 1, 2, 29, 30, 31);

        assertThat(arc.start).isEqualTo(0);
        assertThat(arc.end).isEqualTo(31);
    }

    /**
     * A single owned cell is an arc of one cell, in either topology.
     */
    @Test
    void aSingleCellIsItsOwnArc() {
        resolve(true, 7);
        assertThat(arc.start).isEqualTo(7);
        assertThat(arc.end).isEqualTo(7);

        resolve(false, 7);
        assertThat(arc.start).isEqualTo(7);
        assertThat(arc.end).isEqualTo(7);
    }

    /**
     * A gap between owned cells that is narrower than the space around the body stays inside the
     * arc: ownership within the arc need not be contiguous.
     */
    @Test
    void aGapInsideTheBodyStaysInsideTheArc() {
        resolve(true, 4, 5, 12, 13);

        assertThat(arc.start).isEqualTo(4);
        assertThat(arc.end).isEqualTo(13);
    }

    /**
     * Of several gaps the widest is the outside, not the first one met: here the arc crosses the
     * world edge and keeps the narrower gap between its own cells.
     */
    @Test
    void theWidestOfSeveralGapsIsTheOutside() {
        resolve(true, 0, 6, 7, 20, 21);

        assertThat(arc.start).isEqualTo(20);
        assertThat(arc.end).isEqualTo(7);
    }

    /**
     * A body across the edge keeps a gap of its own inside the arc while the widest gap, the one
     * before the body starts, is the outside.
     */
    @Test
    void aBodyAcrossTheEdgeKeepsItsOwnGapInsideTheArc() {
        resolve(true, 0, 1, 4, 5, 28, 29);

        assertThat(arc.start).isEqualTo(28);
        assertThat(arc.end).isEqualTo(5);
    }

    /**
     * Only the named range of the array is read, so that a caller may hold the coordinates of many
     * lines in one buffer.
     */
    @Test
    void onlyTheNamedRangeOfTheArrayIsRead() {
        int[] buffer = {99, 99, 29, 30, 31, 0, 1, 2, 99};

        ScanLineArc.resolve(buffer, 5, 3, AXIS, true, arc);
        assertThat(arc.start).isEqualTo(0);
        assertThat(arc.end).isEqualTo(2);

        ScanLineArc.resolve(buffer, 2, 3, AXIS, true, arc);
        assertThat(arc.start).isEqualTo(29);
        assertThat(arc.end).isEqualTo(31);
    }
}
