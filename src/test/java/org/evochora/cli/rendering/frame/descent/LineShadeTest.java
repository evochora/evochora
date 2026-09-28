package org.evochora.cli.rendering.frame.descent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LineShade}: the drift of a changed genome and the colour of a shade.
 */
@Tag("unit")
class LineShadeTest {

    private static final int HASHES = 2000;

    /** The step a genome takes from shade 0, where no reflection can happen. */
    private static int step(final long genomeHash) {
        return LineShade.drift((byte) 0, genomeHash);
    }

    /** A shade moved by a step and reflected at both ends. */
    private static int reflected(final int shade) {
        if (shade > LineShade.MAX_SHADE) {
            return 2 * LineShade.MAX_SHADE - shade;
        }
        if (shade < -LineShade.MAX_SHADE) {
            return -2 * LineShade.MAX_SHADE - shade;
        }
        return shade;
    }

    @Test
    void stepSizesLieBetweenTenAndThirtyPercentAndGoBothWays() {
        int smallest = Integer.MAX_VALUE;
        int largest = 0;
        int up = 0;
        int down = 0;
        for (long hash = 0; hash < HASHES; hash++) {
            final int step = step(hash * 0x1234567L);
            smallest = Math.min(smallest, Math.abs(step));
            largest = Math.max(largest, Math.abs(step));
            if (step > 0) {
                up++;
            } else {
                down++;
            }
        }
        assertThat(smallest).isEqualTo(LineShade.MIN_STEP).isGreaterThanOrEqualTo((int) (0.1 * LineShade.MAX_SHADE));
        assertThat(largest).isEqualTo(LineShade.MAX_STEP).isLessThanOrEqualTo((int) (0.3 * LineShade.MAX_SHADE));
        assertThat(up).as("steps up").isBetween(HASHES * 4 / 10, HASHES * 6 / 10);
        assertThat(down).as("steps down").isBetween(HASHES * 4 / 10, HASHES * 6 / 10);
    }

    @Test
    void theDriftStaysInRangeAndReflectsAtBothEnds() {
        int reflectedAtTop = 0;
        int reflectedAtBottom = 0;
        for (int parent = -LineShade.MAX_SHADE; parent <= LineShade.MAX_SHADE; parent++) {
            for (long hash = 0; hash < 200; hash++) {
                final int step = step(hash);
                final int shade = LineShade.drift((byte) parent, hash);
                assertThat(shade).isBetween(-LineShade.MAX_SHADE, LineShade.MAX_SHADE);
                assertThat(shade).as("parent %d, hash %d", parent, hash).isEqualTo(reflected(parent + step));
                reflectedAtTop += parent + step > LineShade.MAX_SHADE ? 1 : 0;
                reflectedAtBottom += parent + step < -LineShade.MAX_SHADE ? 1 : 0;
            }
        }
        assertThat(reflectedAtTop).isPositive();
        assertThat(reflectedAtBottom).isPositive();
        // At the very end a step up comes back by the size of the step
        final long upward = firstHash(true);
        assertThat(LineShade.drift((byte) LineShade.MAX_SHADE, upward)).isEqualTo((byte) (LineShade.MAX_SHADE - step(upward)));
        final long downward = firstHash(false);
        assertThat(LineShade.drift((byte) -LineShade.MAX_SHADE, downward))
            .isEqualTo((byte) (-LineShade.MAX_SHADE - step(downward)));
    }

    private static long firstHash(final boolean up) {
        long hash = 0;
        while (step(hash) > 0 != up) {
            hash++;
        }
        return hash;
    }

    @Test
    void theSameParentShadeAndGenomeGiveTheSameShade() {
        for (long hash = -50; hash < 50; hash++) {
            assertThat(LineShade.drift((byte) 17, hash * 0x9E3779B9L)).isEqualTo(LineShade.drift((byte) 17, hash * 0x9E3779B9L));
        }
        assertThat(LineShade.drift((byte) 0, 1L)).as("different genomes differ somewhere")
            .isNotEqualTo(LineShade.drift((byte) 0, 2L)).isNotEqualTo(LineShade.drift((byte) 0, 3L));
    }

    @Test
    void shadeZeroIsTheColourItselfBitForBit() {
        for (int rgb = 0; rgb < 0x1000000; rgb += 0x010203 * 7) {
            assertThat(LineShade.colour(rgb, 0)).isEqualTo(rgb);
        }
        for (final int rgb : DescentRenderer.PALETTE) {
            assertThat(LineShade.colour(rgb, 0)).isEqualTo(rgb);
        }
    }

    @Test
    void positiveShadesAreLighterAndNegativeDarkerWithHueAndSaturationKept() {
        for (final int rgb : DescentRenderer.PALETTE) {
            final double[] base = hsl(rgb);
            for (final int shade : new int[]{-127, -60, -13, 13, 60, 127}) {
                final double[] shaded = hsl(LineShade.colour(rgb, shade));
                final String what = String.format("%06x at shade %d", rgb, shade);
                assertThat(shaded[2] - base[2]).as(what + ": lightness")
                    .isCloseTo(shade * LineShade.SPREAD / LineShade.MAX_SHADE, within(0.004));
                assertThat(hueDistance(shaded[0], base[0])).as(what + ": hue in degrees").isLessThan(1.5);
                assertThat(shaded[1]).as(what + ": saturation").isCloseTo(base[1], within(0.02));
            }
            assertThat(hsl(LineShade.colour(rgb, 40))[2]).isGreaterThan(base[2]);
            assertThat(hsl(LineShade.colour(rgb, -40))[2]).isLessThan(base[2]);
        }
    }

    @Test
    void theLightnessIsClampedAtBlackAndWhite() {
        assertThat(LineShade.colour(0xffffff, 127)).isEqualTo(0xffffff);
        assertThat(LineShade.colour(0x000000, -127)).isEqualTo(0x000000);
        assertThat(LineShade.colour(0xeeeeee, 127)).as("lightness beyond 1").isEqualTo(0xffffff);
        assertThat(LineShade.colour(0x101010, -127)).as("lightness below 0").isEqualTo(0x000000);
        assertThat(LineShade.colour(0xf0f8ff, 127)).isEqualTo(0xffffff);
    }

    private static double hueDistance(final double a, final double b) {
        final double d = Math.abs(a - b) % 360;
        return Math.min(d, 360 - d);
    }

    /** Hue in degrees, saturation and lightness of a colour. */
    private static double[] hsl(final int rgb) {
        final double r = ((rgb >> 16) & 0xFF) / 255.0;
        final double g = ((rgb >> 8) & 0xFF) / 255.0;
        final double b = (rgb & 0xFF) / 255.0;
        final double max = Math.max(r, Math.max(g, b));
        final double min = Math.min(r, Math.min(g, b));
        final double l = (max + min) / 2;
        final double c = max - min;
        if (c == 0) {
            return new double[]{0, 0, l};
        }
        final double s = c / (1 - Math.abs(2 * l - 1));
        double h;
        if (max == r) {
            h = 60 * (((g - b) / c) % 6);
        } else if (max == g) {
            h = 60 * ((b - r) / c + 2);
        } else {
            h = 60 * ((r - g) / c + 4);
        }
        if (h < 0) {
            h += 360;
        }
        return new double[]{h, s, l};
    }
}
