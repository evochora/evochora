package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GenomeLines}.
 * <p>
 * Every case builds its grid by hand, places the molecules with their ownership and reads the
 * lines through their public accessors.
 */
@Tag("unit")
class GenomeLinesTest {

    private static final int ORGANISM_ID = 7;
    private static final int OTHER_ORGANISM_ID = 8;
    private static final int ROW = 4;
    private static final int[] FORWARD = new int[]{1, 0};
    private static final int[] BACKWARD = new int[]{-1, 0};

    /** An initial position on a line the organisms under test own no cell on. */
    private static final int[] ELSEWHERE = new int[]{0, 31};

    private Environment env;
    private GenomeLines lines;

    @BeforeEach
    void setUp() {
        env = new Environment(new int[]{32, 32}, false);
        lines = new GenomeLines();
    }

    /** Places a cell that carries a number, owned by the organism under test. */
    private void data(int x, int y) {
        env.setMolecule(new Molecule(Config.TYPE_DATA, 1), ORGANISM_ID, new int[]{x, y});
    }

    /** Places a label, owned by the organism under test. */
    private void label(int x, int y) {
        env.setMolecule(new Molecule(Config.TYPE_LABEL, 1234), ORGANISM_ID, new int[]{x, y});
    }

    /** The coordinates of a cell in the row the tests use. */
    private static int[] at(int x) {
        return new int[]{x, ROW};
    }

    /** Along the direction vector the extent of a line is counted from its smallest coordinate. */
    @Test
    void theExtentIsCountedAlongTheDirectionVector() {
        data(3, ROW);
        data(10, ROW);

        lines.build(env, ORGANISM_ID, FORWARD, ELSEWHERE);

        assertThat(lines.cellsBefore(at(3))).isZero();
        assertThat(lines.cellsFrom(at(3))).isEqualTo(8);
        assertThat(lines.cellsBefore(at(6))).isEqualTo(3);
        assertThat(lines.cellsFrom(at(6))).isEqualTo(5);
        assertThat(lines.cellsFrom(at(10))).isEqualTo(1);
    }

    /** Against a backward direction vector the extent is counted from its largest coordinate. */
    @Test
    void theExtentIsCountedFromTheOtherEndForABackwardDirectionVector() {
        data(3, ROW);
        data(10, ROW);

        lines.build(env, ORGANISM_ID, BACKWARD, ELSEWHERE);

        assertThat(lines.cellsBefore(at(10))).isZero();
        assertThat(lines.cellsFrom(at(10))).isEqualTo(8);
        assertThat(lines.cellsBefore(at(6))).isEqualTo(4);
        assertThat(lines.cellsFrom(at(6))).isEqualTo(4);
    }

    /** A line across the world edge of a toroidal world is one arc, counted across the edge. */
    @Test
    void theExtentOfALineAcrossTheWorldEdgeIsCountedAcrossIt() {
        env = new Environment(new int[]{32, 32}, true);
        data(30, ROW);
        data(31, ROW);
        data(0, ROW);
        data(3, ROW);

        lines.build(env, ORGANISM_ID, FORWARD, ELSEWHERE);

        assertThat(lines.lineCount()).isEqualTo(1);
        assertThat(lines.arcStart(0)).isEqualTo(30);
        assertThat(lines.arcEnd(0)).isEqualTo(3);
        assertThat(lines.arcLength(0)).isEqualTo(6);
        assertThat(lines.cellsBefore(at(30))).isZero();
        assertThat(lines.cellsFrom(at(30))).isEqualTo(6);
        assertThat(lines.cellsBefore(at(1))).isEqualTo(3);
        assertThat(lines.cellsFrom(at(1))).isEqualTo(3);
    }

    /** The same cells in a bounded world: the arc runs from the smallest to the largest coordinate. */
    @Test
    void theExtentInABoundedWorldDoesNotWrap() {
        data(30, ROW);
        data(31, ROW);
        data(0, ROW);
        data(3, ROW);

        lines.build(env, ORGANISM_ID, FORWARD, ELSEWHERE);

        assertThat(lines.arcStart(0)).isZero();
        assertThat(lines.arcEnd(0)).isEqualTo(31);
        assertThat(lines.arcLength(0)).isEqualTo(32);
    }

    /** An initial position before the owned cells of its line extends the line's extent to it. */
    @Test
    void theInitialPositionBeforeTheOwnedCellsExtendsTheLine() {
        data(5, ROW);
        data(10, ROW);

        lines.build(env, ORGANISM_ID, FORWARD, at(2));

        assertThat(lines.arcStart(0)).isEqualTo(2);
        assertThat(lines.cellsBefore(at(5))).isEqualTo(3);
        assertThat(lines.cellsFrom(at(2))).isEqualTo(9);
        assertThat(lines.initialFlatIndex()).isEqualTo(env.getProperties().toFlatIndex(at(2)));
    }

    /** An initial position on a line the organism owns no cell on opens no line. */
    @Test
    void anInitialPositionOnALineWithoutOwnedCellsOpensNoLine() {
        data(5, ROW);

        lines.build(env, ORGANISM_ID, FORWARD, ELSEWHERE);

        assertThat(lines.lineCount()).isEqualTo(1);
        assertThat(lines.lineOf(ELSEWHERE)).isEqualTo(-1);
        assertThat(lines.arcStart(0)).isEqualTo(5);
    }

    /** A cell outside the extent, on its line or on a line the organism does not occupy, has none. */
    @Test
    void aCellOutsideTheExtentHasNone() {
        data(3, ROW);
        data(10, ROW);

        lines.build(env, ORGANISM_ID, FORWARD, ELSEWHERE);

        assertThat(lines.cellsBefore(at(11))).isEqualTo(-1);
        assertThat(lines.cellsFrom(at(2))).isEqualTo(-1);
        assertThat(lines.cellsFrom(new int[]{5, ROW + 1})).isEqualTo(-1);
        assertThat(lines.lineOf(new int[]{5, ROW + 1})).isEqualTo(-1);
    }

    /** Lines that were never built know no line and no extent. */
    @Test
    void unbuiltLinesKnowNoExtent() {
        assertThat(lines.lineCount()).isZero();
        assertThat(lines.cellsBefore(at(3))).isEqualTo(-1);
        assertThat(lines.cellsFrom(at(3))).isEqualTo(-1);
    }

    /**
     * Lines are numbered in the order their first cell is met in flat-index order, the labels are
     * listed in that order, and a cell another organism owns is in neither.
     */
    @Test
    void linesAndLabelsFollowTheFlatIndexOrder() {
        label(5, 2);
        data(1, 6);
        label(2, 6);
        label(9, 2);
        env.setMolecule(new Molecule(Config.TYPE_LABEL, 99), OTHER_ORGANISM_ID, new int[]{4, 8});

        lines.build(env, ORGANISM_ID, FORWARD, ELSEWHERE);

        assertThat(lines.lineCount()).isEqualTo(2);
        assertThat(lines.sampleFlatIndex(0)).isLessThan(lines.sampleFlatIndex(1));
        assertThat(lines.lineOf(new int[]{0, 2})).isNotEqualTo(lines.lineOf(new int[]{0, 6}));
        assertThat(lines.labelCount()).isEqualTo(3);
        assertThat(lines.labelFlatIndex(0)).isLessThan(lines.labelFlatIndex(1));
        assertThat(lines.labelFlatIndex(1)).isLessThan(lines.labelFlatIndex(2));
        assertThat(lines.lineOf(new int[]{4, 8})).isEqualTo(-1);
    }

    /** A direction vector without a non-zero component leaves no line. */
    @Test
    void aDegenerateDirectionVectorLeavesNoLine() {
        data(3, ROW);

        lines.build(env, ORGANISM_ID, new int[]{0, 0}, ELSEWHERE);

        assertThat(lines.dvDim()).isEqualTo(-1);
        assertThat(lines.lineCount()).isZero();
        assertThat(lines.cellsFrom(at(3))).isEqualTo(-1);
    }

    /** A rebuild drops what the build before recorded. */
    @Test
    void aRebuildDropsThePreviousLines() {
        data(3, ROW);
        label(5, ROW + 2);
        lines.build(env, ORGANISM_ID, FORWARD, ELSEWHERE);

        lines.build(env, OTHER_ORGANISM_ID, FORWARD, ELSEWHERE);

        assertThat(lines.lineCount()).isZero();
        assertThat(lines.labelCount()).isZero();
        assertThat(lines.organismId()).isEqualTo(OTHER_ORGANISM_ID);
    }
}
