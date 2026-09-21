package org.evochora.runtime.label;

import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CoordinateDecoder: its division-free decoding must agree with the division it
 * replaces for every cell of a world, whatever the world's shape.
 */
@Tag("unit")
class CoordinateDecoderTest {

    private static final int[][] SHAPES = {
            {256}, {1}, {64, 64}, {100, 37}, {2048, 1152}, {7, 5, 3}, {40, 40, 40}, {3, 1000}, {46340, 46340}
    };

    @Test
    void decodesTheCoordinatesOfTheFirstTwoDimensionsLikeADivision() {
        for (int[] shape : SHAPES) {
            EnvironmentProperties properties = new EnvironmentProperties(shape, true);
            CoordinateDecoder decoder = new CoordinateDecoder(properties);
            for (int flatIndex : sampleOf(shape)) {
                int[] expected = properties.flatIndexToCoordinates(flatIndex);
                assertThat(decoder.coordinate(0, flatIndex)).isEqualTo(expected[0]);
                assertThat(decoder.coordinate(1, flatIndex)).isEqualTo(shape.length > 1 ? expected[1] : 0);
            }
        }
    }

    @Test
    void measuresTheManhattanDistanceWithAndWithoutWrapping() {
        Random random = new Random(7);
        for (int[] shape : SHAPES) {
            for (boolean toroidal : new boolean[]{true, false}) {
                EnvironmentProperties properties = new EnvironmentProperties(shape, toroidal);
                CoordinateDecoder decoder = new CoordinateDecoder(properties);
                for (int flatIndex : sampleOf(shape)) {
                    int[] from = new int[shape.length];
                    for (int i = 0; i < shape.length; i++) {
                        from[i] = random.nextInt(shape[i]);
                    }
                    int[] to = properties.flatIndexToCoordinates(flatIndex);
                    int expected = 0;
                    for (int i = 0; i < shape.length; i++) {
                        int difference = Math.abs(from[i] - to[i]);
                        expected += toroidal ? Math.min(difference, shape[i] - difference) : difference;
                    }
                    assertThat(decoder.distance(from, flatIndex)).isEqualTo(expected);
                }
            }
        }
    }

    /** Every cell of a small world; the corners, the stride boundaries and a random sample of a large one. */
    private static int[] sampleOf(int[] shape) {
        long cells = 1;
        for (int extent : shape) {
            cells *= extent;
        }
        if (cells <= 20_000) {
            int[] all = new int[(int) cells];
            for (int i = 0; i < all.length; i++) {
                all[i] = i;
            }
            return all;
        }
        Random random = new Random(shape.length * 31L + shape[0]);
        int[] sample = new int[20_000];
        int stride = (int) (cells / shape[0]);
        for (int i = 0; i < sample.length; i++) {
            int around = random.nextInt(shape[0]) * stride;
            sample[i] = switch (i % 4) {
                case 0 -> around;
                case 1 -> Math.max(0, around - 1);
                case 2 -> (int) (cells - 1 - random.nextInt(1000));
                default -> (int) (random.nextDouble() * cells);
            };
        }
        return sample;
    }
}
