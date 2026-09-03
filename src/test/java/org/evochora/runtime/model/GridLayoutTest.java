package org.evochora.runtime.model;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.BitSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The layout is checked exhaustively: every cell of every test world, in two to four dimensions,
 * for tile sides from 1 (the persisted row-major numbering itself) to the production side of 32.
 */
@Tag("unit")
class GridLayoutTest {

    /** Worlds and tile sides that divide them; every combination is a separate test case. */
    static Stream<Arguments> worlds() {
        return Stream.of(
                Arguments.of(new int[]{64, 32}, true, 1),
                Arguments.of(new int[]{64, 32}, true, 4),
                Arguments.of(new int[]{64, 32}, true, 32),
                Arguments.of(new int[]{64, 32}, false, 32),
                Arguments.of(new int[]{96, 32}, false, 8),
                Arguments.of(new int[]{8, 12, 4}, true, 4),
                Arguments.of(new int[]{8, 12, 4}, false, 1),
                Arguments.of(new int[]{32, 64, 32}, true, 32),
                Arguments.of(new int[]{4, 8, 4, 8}, true, 4),
                Arguments.of(new int[]{4, 8, 4, 8}, false, 2)
        );
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void everyCoordinateSurvivesTheRoundTripAndTheIndicesFormAPermutation(int[] shape, boolean toroidal, int tileSide) {
        GridLayout layout = layout(shape, toroidal, tileSide);
        BitSet seen = new BitSet(layout.totalCells());
        int[] back = new int[shape.length];

        forEachCoordinate(shape, coord -> {
            int index = layout.layoutIndex(coord);
            assertThat(index).isBetween(0, layout.totalCells() - 1);
            assertThat(seen.get(index)).as("index %d used twice", index).isFalse();
            seen.set(index);
            layout.coordinate(index, back);
            assertThat(back).isEqualTo(coord);
        });
        assertThat(seen.cardinality()).isEqualTo(layout.totalCells());
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void flatIndexIsThePersistedRowMajorIndex(int[] shape, boolean toroidal, int tileSide) {
        EnvironmentProperties properties = new EnvironmentProperties(shape, toroidal);
        GridLayout layout = new GridLayout(properties, tileSide);

        forEachCoordinate(shape, coord ->
                assertThat(layout.flatIndex(layout.layoutIndex(coord))).isEqualTo(properties.toFlatIndex(coord)));
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void stepAgreesWithCoordinateArithmeticInEveryDirection(int[] shape, boolean toroidal, int tileSide) {
        GridLayout layout = layout(shape, toroidal, tileSide);
        int[] neighbour = new int[shape.length];

        forEachCoordinate(shape, coord -> {
            int index = layout.layoutIndex(coord);
            for (int dim = 0; dim < shape.length; dim++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    System.arraycopy(coord, 0, neighbour, 0, coord.length);
                    neighbour[dim] += sign;
                    boolean leaves = neighbour[dim] < 0 || neighbour[dim] >= shape[dim];
                    int stepped = layout.step(index, dim, sign > 0);
                    if (leaves && !toroidal) {
                        assertThat(stepped).as("bounded world, from %s along %d by %d", coordString(coord), dim, sign).isEqualTo(-1);
                    } else {
                        neighbour[dim] = Math.floorMod(neighbour[dim], shape[dim]);
                        assertThat(stepped).as("from %s along %d by %d", coordString(coord), dim, sign)
                                .isEqualTo(layout.layoutIndex(neighbour));
                    }
                }
            }
        });
    }


    @ParameterizedTest
    @MethodSource("worlds")
    void flatIndexSplitsIntoATilePartAndAnOffsetPart(int[] shape, boolean toroidal, int tileSide) {
        GridLayout layout = layout(shape, toroidal, tileSide);
        int shift = layout.cellsPerTileShift();
        int offsetMask = (1 << shift) - 1;

        for (int index = 0; index < layout.totalCells(); index++) {
            assertThat(layout.flatIndexOfTile(index >>> shift) + layout.flatIndexOffset(index & offsetMask))
                    .as("index %d", index).isEqualTo(layout.flatIndex(index));
        }
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void containsExactlyTheCoordinatesOfTheWorld(int[] shape, boolean toroidal, int tileSide) {
        GridLayout layout = layout(shape, toroidal, tileSide);
        int[] probe = new int[shape.length];
        forEachCoordinate(shape, coord -> {
            assertThat(layout.contains(coord)).as("inside %s", java.util.Arrays.toString(coord)).isTrue();
            for (int i = 0; i < shape.length; i++) {
                System.arraycopy(coord, 0, probe, 0, shape.length);
                probe[i] = -1 - coord[i];
                assertThat(layout.contains(probe)).as("negative %s", java.util.Arrays.toString(probe)).isFalse();
                probe[i] = shape[i] + coord[i];
                assertThat(layout.contains(probe)).as("beyond the edge %s", java.util.Arrays.toString(probe)).isFalse();
            }
        });
    }

    @Test
    void tileSideOneIsThePersistedNumberingItself() {
        int[] shape = {7, 5, 3};
        EnvironmentProperties properties = new EnvironmentProperties(shape, true);
        GridLayout layout = new GridLayout(properties, 1);

        forEachCoordinate(shape, coord -> {
            int index = layout.layoutIndex(coord);
            assertThat(index).isEqualTo(properties.toFlatIndex(coord));
            assertThat(layout.flatIndex(index)).isEqualTo(index);
        });
    }

    @Test
    void dimensionZeroIsContiguousInsideATile() {
        GridLayout layout = layout(new int[]{64, 64}, true, 32);
        int[] coord = {0, 17};
        int index = layout.layoutIndex(coord);
        for (int x = 1; x < 32; x++) {
            coord[0] = x;
            assertThat(layout.layoutIndex(coord)).isEqualTo(index + x);
        }
    }

    @Test
    void aTileIsContiguousInMemory() {
        GridLayout layout = layout(new int[]{64, 64}, true, 32);
        int first = layout.layoutIndex(new int[]{32, 32});
        BitSet inTile = new BitSet();
        forEachCoordinate(new int[]{64, 64}, coord -> {
            if (coord[0] >= 32 && coord[1] >= 32) {
                inTile.set(layout.layoutIndex(coord) - first);
            }
        });
        assertThat(inTile.cardinality()).isEqualTo(1024);
        assertThat(inTile.nextClearBit(0)).isEqualTo(1024);
    }

    @Test
    void rejectsAWorldDimensionThatIsNotAMultipleOfTheTileSide() {
        assertThatThrownBy(() -> layout(new int[]{7680, 3000}, true, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("World dimension 1 is 3000, which is not a multiple of 32; the nearest valid sizes are 2976 and 3008");
        assertThatThrownBy(() -> layout(new int[]{20, 64}, true, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("World dimension 0 is 20, which is not a multiple of 32; the nearest valid sizes are 32");
    }

    @Test
    void rejectsAWorldDimensionSmallerThanOneCell() {
        assertThatThrownBy(() -> layout(new int[]{64, 0}, true, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("World dimension 1 is 0; every dimension must be at least 32");
        assertThatThrownBy(() -> layout(new int[]{-32, 64}, true, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("World dimension 0 is -32; every dimension must be at least 32");
    }

    @Test
    void rejectsATileSideThatIsNotAPowerOfTwo() {
        assertThatThrownBy(() -> layout(new int[]{64, 64}, true, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("power of two");
        assertThatThrownBy(() -> layout(new int[]{64, 64}, true, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("power of two");
    }

    @Test
    void rejectsATileThatAnIntCannotIndex() {
        int[] shape = {32, 32, 32, 32, 32, 32, 32};
        assertThatThrownBy(() -> layout(shape, true, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more cells than an int can index");
    }

    @Test
    void rejectsAWorldThatAnIntCannotIndex() {
        assertThatThrownBy(() -> layout(new int[]{65536, 65536}, true, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("World too large");
    }

    // -----------------------------------------------------------------------------------------

    private static GridLayout layout(int[] shape, boolean toroidal, int tileSide) {
        return new GridLayout(new EnvironmentProperties(shape, toroidal), tileSide);
    }

    private interface CoordinateVisitor {
        void visit(int[] coord);
    }

    /** Visits every coordinate of the world in row-major order; the array is reused between visits. */
    private static void forEachCoordinate(int[] shape, CoordinateVisitor visitor) {
        int[] coord = new int[shape.length];
        int total = 1;
        for (int size : shape) {
            total *= size;
        }
        for (int n = 0; n < total; n++) {
            int remaining = n;
            for (int i = shape.length - 1; i >= 0; i--) {
                coord[i] = remaining % shape[i];
                remaining /= shape[i];
            }
            visitor.visit(coord);
        }
    }

    private static String coordString(int[] coord) {
        return java.util.Arrays.toString(coord);
    }
}
