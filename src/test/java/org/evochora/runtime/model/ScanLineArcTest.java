package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.evochora.runtime.label.PreExpandedHammingStrategy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests the extent an organism occupies on one line of the world.
 * <p>
 * The cases for {@link ScanLineArc#resolve} are hand-written sets of coordinates on an axis of 32
 * cells, so that "half the axis" is 16 and the cases around it can be written down exactly. They
 * cover a body inside the world, a body across the world edge, the two spans on either side of the
 * half-axis threshold, the same coordinates in a bounded world, a single cell, and bodies with gaps
 * in them. They need no environment and no simulation.
 * <p>
 * {@link ScanLineArc#isWithinArc} applies the same rule by walking the world, and is therefore
 * compared with the resolved arc over every line two short axes can carry, and checked separately
 * on a world whose memory tiles the walk has to cross.
 */
@Tag("unit")
class ScanLineArcTest {

    private static final int AXIS = 32;

    /** Odd axis the exhaustive comparison runs on, where half the axis is rounded down. */
    private static final int ODD_AXIS = 11;

    /** Even axis the exhaustive comparison runs on. */
    private static final int EVEN_AXIS = 12;

    /** The organism the cells in the comparisons belong to. */
    private static final int OWNER = 7;

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

    /**
     * The walk along a line answers for every position exactly what the resolved arc contains.
     * <p>
     * Both ways of applying the rule are written independently of each other, so this compares them
     * over every line a short axis can carry: each of the {@code 2^axisSize} ways to place the
     * owner's cells on it, each position on it, on an odd and an even axis so that the threshold of
     * half an axis is met from both sides, and in a toroidal and a bounded world. The placements
     * are walked in Gray-code order, so one cell changes owner between two of them.
     */
    @Test
    void theWalkAnswersWhatTheResolvedArcContains() {
        for (int axisSize : new int[]{ODD_AXIS, EVEN_AXIS}) {
            for (boolean toroidal : new boolean[]{true, false}) {
                assertTheTwoAgreeOnEveryLine(axisSize, toroidal);
            }
        }
    }

    /**
     * Compares the walk with the resolved arc on every placement of the owner's cells on one axis.
     *
     * @param axisSize The size of the one-dimensional world to run this on.
     * @param toroidal Whether that world wraps around.
     */
    private void assertTheTwoAgreeOnEveryLine(int axisSize, boolean toroidal) {
        Environment environment = new Environment(new EnvironmentProperties(new int[]{axisSize}, toroidal),
                new PreExpandedHammingStrategy(), 1);
        int[] position = new int[1];
        int[] owned = new int[axisSize];
        int placement = 0;

        assertTheTwoAgree(environment, position, owned, placement, axisSize, toroidal);
        for (int step = 1; step < (1 << axisSize); step++) {
            int cell = Integer.numberOfTrailingZeros(step);
            placement ^= 1 << cell;
            environment.setOwnerId((placement & (1 << cell)) != 0 ? OWNER : 0, cell);
            assertTheTwoAgree(environment, position, owned, placement, axisSize, toroidal);
        }
    }

    /**
     * Compares the walk with the resolved arc for one placement of the owner's cells, at every
     * position of the line.
     *
     * @param environment The world holding that placement.
     * @param position Buffer for the position handed to the walk.
     * @param owned Buffer for the owned coordinates handed to {@link ScanLineArc#resolve}.
     * @param placement The placement as a bit per cell, for the message of a failure.
     * @param axisSize The size of the world.
     * @param toroidal Whether that world wraps around.
     */
    private void assertTheTwoAgree(Environment environment, int[] position, int[] owned, int placement,
                                   int axisSize, boolean toroidal) {
        int count = 0;
        for (int coordinate = 0; coordinate < axisSize; coordinate++) {
            if ((placement & (1 << coordinate)) != 0) {
                owned[count++] = coordinate;
            }
        }
        if (count > 0) {
            ScanLineArc.resolve(owned, 0, count, axisSize, toroidal, arc);
        }

        for (int coordinate = 0; coordinate < axisSize; coordinate++) {
            position[0] = coordinate;
            boolean withinArc = count > 0 && (arc.start <= arc.end
                    ? coordinate >= arc.start && coordinate <= arc.end
                    : coordinate >= arc.start || coordinate <= arc.end);

            assertThat(ScanLineArc.isWithinArc(environment, position, 0, OWNER))
                    .as("axis=%d toroidal=%b placement=%s position=%d", axisSize, toroidal,
                            Integer.toBinaryString(placement), coordinate)
                    .isEqualTo(withinArc);
        }
    }

    /**
     * The walk reads the line through the position and no other, crosses the edges of the memory
     * tiles, and gives the same answers whatever the tile side is.
     */
    @Test
    void theWalkFollowsOneLineAcrossTileAndWorldEdges() {
        for (int tileSide : new int[]{1, 32}) {
            Environment environment = new Environment(new EnvironmentProperties(new int[]{64, 64}, true),
                    new PreExpandedHammingStrategy(), tileSide);
            // A body across the world edge on one line, one across a tile edge with a hole in it on
            // the next, and two cells on one column, all owned by the same organism.
            own(environment, 62, 40);
            own(environment, 63, 40);
            own(environment, 0, 40);
            own(environment, 1, 40);
            own(environment, 30, 41);
            own(environment, 31, 41);
            own(environment, 33, 41);
            own(environment, 10, 20);
            own(environment, 10, 24);

            // The body across the world edge: inside between its cells, outside beyond them.
            assertWalk(environment, tileSide, true, 0, new int[]{63, 40});
            assertWalk(environment, tileSide, true, 0, new int[]{0, 40});
            assertWalk(environment, tileSide, false, 0, new int[]{2, 40});
            assertWalk(environment, tileSide, false, 0, new int[]{61, 40});
            assertWalk(environment, tileSide, false, 0, new int[]{30, 40});

            // The hole at 32 lies between cells of the body, across the edge of a 32-cell tile.
            assertWalk(environment, tileSide, true, 0, new int[]{32, 41});
            assertWalk(environment, tileSide, false, 0, new int[]{29, 41});

            // The column: within the body along the column, and on a row of its own that carries
            // no cell of the organism at all.
            assertWalk(environment, tileSide, true, 1, new int[]{10, 22});
            assertWalk(environment, tileSide, false, 0, new int[]{10, 22});
        }
    }

    /**
     * A position outside a bounded world names no cell and therefore lies in no arc, and a position
     * that does not describe a cell of the world at all is rejected. A data pointer can come to
     * stand outside a bounded world, so the first of the two is a case of the simulation, not only
     * of this method.
     */
    @Test
    void aPositionThatNamesNoCellLiesInNoArc() {
        Environment environment = new Environment(new EnvironmentProperties(new int[]{16, 16}, false),
                new PreExpandedHammingStrategy(), 1);
        environment.setOwnerId(OWNER, 4, 5);
        environment.setOwnerId(OWNER, 9, 5);

        assertThat(ScanLineArc.isWithinArc(environment, new int[]{6, 5}, 0, OWNER)).isTrue();
        assertThat(ScanLineArc.isWithinArc(environment, new int[]{6, 16}, 0, OWNER)).isFalse();
        assertThat(ScanLineArc.isWithinArc(environment, new int[]{-1, 5}, 0, OWNER)).isFalse();
        assertThatThrownBy(() -> ScanLineArc.isWithinArc(environment, new int[]{6}, 0, OWNER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Gives one cell of a two-dimensional world to the owner these tests use. */
    private void own(Environment environment, int x, int y) {
        environment.setOwnerId(OWNER, x, y);
    }

    /**
     * Asserts what the walk answers for one position and one axis.
     *
     * @param environment The world to walk.
     * @param tileSide The tile side that world was built with, for the message of a failure.
     * @param expected What the walk is expected to answer.
     * @param axis The dimension the line runs along.
     * @param position The position to test.
     */
    private void assertWalk(Environment environment, int tileSide, boolean expected, int axis, int[] position) {
        assertThat(ScanLineArc.isWithinArc(environment, position, axis, OWNER))
                .as("tileSide=%d axis=%d position=%s", tileSide, axis, java.util.Arrays.toString(position))
                .isEqualTo(expected);
    }
}
