package org.evochora.runtime.label;

import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TiledLabelIndex: what it holds, and that its search by tiles finds the same label
 * as a search over every label of the world.
 */
@Tag("unit")
class TiledLabelIndexTest {

    private static final int VALUE = 0x12345;
    private static final int NOBODY = -5;

    private static long noneFound() {
        return TiledLabelIndex.searchState(Integer.MAX_VALUE, -1);
    }

    @Test
    void holdsALabelUnderItsOwnerUntilItIsRemoved() {
        TiledLabelIndex index = new TiledLabelIndex(new EnvironmentProperties(new int[]{256, 256}, true));
        assertThat(index.isEmpty()).isTrue();

        index.put(VALUE, 1000, 7);

        assertThat(index.isInUse(VALUE)).isTrue();
        assertThat(index.ownerOf(VALUE, 1000)).isEqualTo(7);
        assertThat(index.ownerOf(VALUE, 1001)).isEqualTo(-1);

        assertThat(index.remove(VALUE, 1000)).isTrue();
        assertThat(index.remove(VALUE, 1000)).isFalse();
        assertThat(index.isInUse(VALUE)).isFalse();
        assertThat(index.isEmpty()).isTrue();
    }

    @Test
    void puttingALabelAgainSetsItsOwner() {
        TiledLabelIndex index = new TiledLabelIndex(new EnvironmentProperties(new int[]{256, 256}, true));
        index.put(VALUE, 1000, 7);

        index.put(VALUE, 1000, 0);

        assertThat(index.ownerOf(VALUE, 1000)).isZero();
        assertThat(index.remove(VALUE, 1000)).isTrue();
        assertThat(index.isEmpty()).as("the label was held once").isTrue();
    }

    @Test
    void setsTheOwnerOfALabelItHolds_andOfNoOther() {
        TiledLabelIndex index = new TiledLabelIndex(new EnvironmentProperties(new int[]{256, 256}, true));
        index.put(VALUE, 1000, 7);

        assertThat(index.setOwner(VALUE, 1000, 8)).isTrue();
        assertThat(index.setOwner(VALUE, 1001, 8)).isFalse();
        assertThat(index.setOwner(VALUE + 1, 1000, 8)).isFalse();

        assertThat(index.ownerOf(VALUE, 1000)).isEqualTo(8);
        assertThat(index.ownerOf(VALUE, 1001)).isEqualTo(-1);
        assertThat(index.isInUse(VALUE + 1)).isFalse();
    }

    @Test
    void passesOverTheLabelsOfTheExcludedOwner() {
        EnvironmentProperties properties = new EnvironmentProperties(new int[]{256, 256}, true);
        TiledLabelIndex index = new TiledLabelIndex(properties);
        int near = properties.toFlatIndex(new int[]{10, 10});
        int far = properties.toFlatIndex(new int[]{40, 10});
        index.put(VALUE, near, 7);
        index.put(VALUE, far, 8);

        long search = index.nearest(VALUE, 7, new int[]{9, 10}, 250, true, noneFound());

        assertThat(TiledLabelIndex.foundFlatIndex(search)).isEqualTo(far);
    }

    @Test
    void carriesTheBestLabelFromOneValueToTheNext() {
        EnvironmentProperties properties = new EnvironmentProperties(new int[]{256, 256}, true);
        TiledLabelIndex index = new TiledLabelIndex(properties);
        int nearOfFirst = properties.toFlatIndex(new int[]{12, 10});
        int fartherOfSecond = properties.toFlatIndex(new int[]{30, 10});
        index.put(VALUE, nearOfFirst, 7);
        index.put(VALUE + 1, fartherOfSecond, 7);

        long search = index.nearest(VALUE, NOBODY, new int[]{10, 10}, 250, true, noneFound());
        search = index.nearest(VALUE + 1, NOBODY, new int[]{10, 10}, 250, true, search);

        assertThat(TiledLabelIndex.foundFlatIndex(search)).isEqualTo(nearOfFirst);
    }

    @Test
    void findsTheSameLabelAsASearchOverEveryLabel() {
        // Worlds of one to three dimensions, smaller than a tile, a multiple of the tile side and
        // not, each toroidal and bounded
        int[][] shapes = {{64, 64}, {300, 200}, {1024, 32}, {512, 384}, {256}, {1000}, {40, 40, 40}, {2048, 1152}};
        int[] radii = {0, 5, 60, 130, 250, 5000};
        for (int[] shape : shapes) {
            for (boolean toroidal : new boolean[]{true, false}) {
                Random random = new Random(shape[0] * 31L + shape.length + (toroidal ? 1 : 0));
                EnvironmentProperties properties = new EnvironmentProperties(shape, toroidal);
                TiledLabelIndex index = new TiledLabelIndex(properties);
                CoordinateDecoder coordinates = index.coordinates();
                Map<Integer, Integer> ownerByFlatIndex = new HashMap<>();
                List<Integer> flatIndexes = new ArrayList<>();
                int cells = 1;
                for (int extent : shape) {
                    cells *= extent;
                }

                for (int round = 0; round < 400; round++) {
                    // Labels come and go between the searches
                    if (flatIndexes.isEmpty() || random.nextInt(4) > 0) {
                        int flatIndex = random.nextInt(cells);
                        if (ownerByFlatIndex.put(flatIndex, random.nextInt(6)) == null) {
                            flatIndexes.add(flatIndex);
                        }
                        index.put(VALUE, flatIndex, ownerByFlatIndex.get(flatIndex));
                    } else {
                        int flatIndex = flatIndexes.remove(random.nextInt(flatIndexes.size()));
                        ownerByFlatIndex.remove(flatIndex);
                        assertThat(index.remove(VALUE, flatIndex)).isTrue();
                    }

                    int[] from = new int[shape.length];
                    for (int i = 0; i < shape.length; i++) {
                        from[i] = random.nextInt(shape[i]);
                    }
                    int radius = radii[random.nextInt(radii.length)];
                    int excludedOwner = random.nextInt(6);
                    boolean preferLowIndex = random.nextBoolean();

                    int expected = -1;
                    int expectedDistance = Integer.MAX_VALUE;
                    for (int flatIndex : flatIndexes) {
                        int distance = coordinates.distance(from, flatIndex);
                        boolean counts = ownerByFlatIndex.get(flatIndex) != excludedOwner && distance <= radius;
                        if (counts && TiledLabelIndex.isNearer(distance, flatIndex, expectedDistance, expected,
                                preferLowIndex)) {
                            expectedDistance = distance;
                            expected = flatIndex;
                        }
                    }

                    long search = index.nearest(VALUE, excludedOwner, from, radius, preferLowIndex, noneFound());
                    assertThat(TiledLabelIndex.foundFlatIndex(search))
                            .as("shape %s, toroidal %s, from %s, radius %d", java.util.Arrays.toString(shape), toroidal,
                                    java.util.Arrays.toString(from), radius)
                            .isEqualTo(expected);
                }
            }
        }
    }
}
