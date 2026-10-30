package org.evochora.runtime.worldgen;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GenomeScanLines}.
 * <p>
 * Every case builds its grid by hand, places the molecules with their ownership and reads the
 * scan lines through their public accessors.
 */
@Tag("unit")
class GenomeScanLinesTest {

    private static final int ORGANISM_ID = 7;
    private static final int OTHER_ORGANISM_ID = 8;
    private static final int ROW = 4;
    private static final int[] FORWARD = new int[]{1, 0};
    private static final int[] BACKWARD = new int[]{-1, 0};

    /** An initial position on a scan line the organisms under test own no cell on. */
    private static final int[] ELSEWHERE = new int[]{0, 31};

    private Environment env;
    private GenomeScanLines scanLines;

    @BeforeEach
    void setUp() {
        env = new Environment(new int[]{32, 32}, false);
        scanLines = new GenomeScanLines();
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

    /** Along the direction vector the extent of a scan line is counted from its smallest coordinate. */
    @Test
    void theExtentIsCountedAlongTheDirectionVector() {
        data(3, ROW);
        data(10, ROW);

        scanLines.build(env, ORGANISM_ID, FORWARD, ELSEWHERE);

        assertThat(scanLines.cellsBefore(at(3))).isZero();
        assertThat(scanLines.cellsFrom(at(3))).isEqualTo(8);
        assertThat(scanLines.cellsBefore(at(6))).isEqualTo(3);
        assertThat(scanLines.cellsFrom(at(6))).isEqualTo(5);
        assertThat(scanLines.cellsFrom(at(10))).isEqualTo(1);
    }

    /** Against a backward direction vector the extent is counted from its largest coordinate. */
    @Test
    void theExtentIsCountedFromTheOtherEndForABackwardDirectionVector() {
        data(3, ROW);
        data(10, ROW);

        scanLines.build(env, ORGANISM_ID, BACKWARD, ELSEWHERE);

        assertThat(scanLines.cellsBefore(at(10))).isZero();
        assertThat(scanLines.cellsFrom(at(10))).isEqualTo(8);
        assertThat(scanLines.cellsBefore(at(6))).isEqualTo(4);
        assertThat(scanLines.cellsFrom(at(6))).isEqualTo(4);
    }

    /** A scan line across the world edge of a toroidal world is one arc, counted across the edge. */
    @Test
    void theExtentOfALineAcrossTheWorldEdgeIsCountedAcrossIt() {
        env = new Environment(new int[]{32, 32}, true);
        data(30, ROW);
        data(31, ROW);
        data(0, ROW);
        data(3, ROW);

        scanLines.build(env, ORGANISM_ID, FORWARD, ELSEWHERE);

        assertThat(scanLines.scanLineCount()).isEqualTo(1);
        assertThat(scanLines.arcStart(0)).isEqualTo(30);
        assertThat(scanLines.arcEnd(0)).isEqualTo(3);
        assertThat(scanLines.arcLength(0)).isEqualTo(6);
        assertThat(scanLines.cellsBefore(at(30))).isZero();
        assertThat(scanLines.cellsFrom(at(30))).isEqualTo(6);
        assertThat(scanLines.cellsBefore(at(1))).isEqualTo(3);
        assertThat(scanLines.cellsFrom(at(1))).isEqualTo(3);
    }

    /** The same cells in a bounded world: the arc runs from the smallest to the largest coordinate. */
    @Test
    void theExtentInABoundedWorldDoesNotWrap() {
        data(30, ROW);
        data(31, ROW);
        data(0, ROW);
        data(3, ROW);

        scanLines.build(env, ORGANISM_ID, FORWARD, ELSEWHERE);

        assertThat(scanLines.arcStart(0)).isZero();
        assertThat(scanLines.arcEnd(0)).isEqualTo(31);
        assertThat(scanLines.arcLength(0)).isEqualTo(32);
    }

    /** An initial position before the owned cells of its scan line extends the scan line's extent to it. */
    @Test
    void theInitialPositionBeforeTheOwnedCellsExtendsTheLine() {
        data(5, ROW);
        data(10, ROW);

        scanLines.build(env, ORGANISM_ID, FORWARD, at(2));

        assertThat(scanLines.arcStart(0)).isEqualTo(2);
        assertThat(scanLines.cellsBefore(at(5))).isEqualTo(3);
        assertThat(scanLines.cellsFrom(at(2))).isEqualTo(9);
        assertThat(scanLines.initialFlatIndex()).isEqualTo(env.getProperties().toFlatIndex(at(2)));
    }

    /** An initial position on a scan line the organism owns no cell on opens no scan line. */
    @Test
    void anInitialPositionOnALineWithoutOwnedCellsOpensNoLine() {
        data(5, ROW);

        scanLines.build(env, ORGANISM_ID, FORWARD, ELSEWHERE);

        assertThat(scanLines.scanLineCount()).isEqualTo(1);
        assertThat(scanLines.scanLineOf(ELSEWHERE)).isEqualTo(-1);
        assertThat(scanLines.arcStart(0)).isEqualTo(5);
    }

    /** A cell outside the extent, on its scan line or on a scan line the organism does not occupy, has none. */
    @Test
    void aCellOutsideTheExtentHasNone() {
        data(3, ROW);
        data(10, ROW);

        scanLines.build(env, ORGANISM_ID, FORWARD, ELSEWHERE);

        assertThat(scanLines.cellsBefore(at(11))).isEqualTo(-1);
        assertThat(scanLines.cellsFrom(at(2))).isEqualTo(-1);
        assertThat(scanLines.cellsFrom(new int[]{5, ROW + 1})).isEqualTo(-1);
        assertThat(scanLines.scanLineOf(new int[]{5, ROW + 1})).isEqualTo(-1);
    }

    /** Scan lines that were never built know no scan line and no extent. */
    @Test
    void unbuiltLinesKnowNoExtent() {
        assertThat(scanLines.scanLineCount()).isZero();
        assertThat(scanLines.cellsBefore(at(3))).isEqualTo(-1);
        assertThat(scanLines.cellsFrom(at(3))).isEqualTo(-1);
    }

    /**
     * Scan lines are numbered in the order their first cell is met in flat-index order, the labels
     * are listed in that order, and a cell another organism owns is in neither.
     */
    @Test
    void linesAndLabelsFollowTheFlatIndexOrder() {
        label(5, 2);
        data(1, 6);
        label(2, 6);
        label(9, 2);
        env.setMolecule(new Molecule(Config.TYPE_LABEL, 99), OTHER_ORGANISM_ID, new int[]{4, 8});

        scanLines.build(env, ORGANISM_ID, FORWARD, ELSEWHERE);

        assertThat(scanLines.scanLineCount()).isEqualTo(2);
        assertThat(scanLines.sampleFlatIndex(0)).isLessThan(scanLines.sampleFlatIndex(1));
        assertThat(scanLines.scanLineOf(new int[]{0, 2})).isNotEqualTo(scanLines.scanLineOf(new int[]{0, 6}));
        assertThat(scanLines.labelCount()).isEqualTo(3);
        assertThat(scanLines.labelFlatIndex(0)).isLessThan(scanLines.labelFlatIndex(1));
        assertThat(scanLines.labelFlatIndex(1)).isLessThan(scanLines.labelFlatIndex(2));
        assertThat(scanLines.scanLineOf(new int[]{4, 8})).isEqualTo(-1);
    }

    /** A direction vector without a non-zero component leaves no scan line. */
    @Test
    void aDegenerateDirectionVectorLeavesNoLine() {
        data(3, ROW);

        scanLines.build(env, ORGANISM_ID, new int[]{0, 0}, ELSEWHERE);

        assertThat(scanLines.dvDim()).isEqualTo(-1);
        assertThat(scanLines.scanLineCount()).isZero();
        assertThat(scanLines.cellsFrom(at(3))).isEqualTo(-1);
    }

    /** A rebuild drops what the build before recorded. */
    @Test
    void aRebuildDropsThePreviousLines() {
        data(3, ROW);
        label(5, ROW + 2);
        scanLines.build(env, ORGANISM_ID, FORWARD, ELSEWHERE);

        scanLines.build(env, OTHER_ORGANISM_ID, FORWARD, ELSEWHERE);

        assertThat(scanLines.scanLineCount()).isZero();
        assertThat(scanLines.labelCount()).isZero();
        assertThat(scanLines.organismId()).isEqualTo(OTHER_ORGANISM_ID);
    }
}
