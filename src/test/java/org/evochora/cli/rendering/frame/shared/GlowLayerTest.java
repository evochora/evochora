package org.evochora.cli.rendering.frame.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GlowLayer}: a group drawn in one colour gives exactly the pixels of the
 * reference arithmetic below, a colour sprite per size blended channel by channel, and a group
 * drawn by shade takes the mean shade of the positions on every pixel.
 */
@Tag("unit")
class GlowLayerTest {

    private static final int WIDTH = 400;
    private static final int HEIGHT = 240;

    /**
     * The glow arithmetic as a group drawn in one colour has to produce it: per density level a
     * sprite of the colour with a quartic alpha bell, alpha-blended over the buffer. Kept here as
     * the fixed expectation for {@link GlowLayer#drawTo(int[], int)}.
     */
    private static final class Reference {
        private static final int[] BASE_GLOW_SIZES = {8, 12, 16, 22};
        private static final int[] DENSITY_THRESHOLDS = {3, 10, 30};
        private static final int PEAK_ALPHA = 180;

        private final int width;
        private final int height;
        private final int[] sizes = new int[BASE_GLOW_SIZES.length];
        private final int[] density;

        Reference(final int width, final int height, final double glowSize) {
            this.width = width;
            this.height = height;
            final double scale = (double) width / 400 * glowSize;
            for (int i = 0; i < sizes.length; i++) {
                sizes[i] = Math.max(2, (int) (BASE_GLOW_SIZES[i] * scale));
            }
            this.density = new int[width * height];
        }

        void clear() {
            java.util.Arrays.fill(density, 0);
        }

        void add(final int index) {
            if (index >= 0 && index < density.length) {
                density[index]++;
            }
        }

        void drawTo(final int[] buffer, final int colour) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final int count = density[y * width + x];
                    if (count > 0) {
                        blit(buffer, x, y, spriteIndex(count), colour);
                    }
                }
            }
        }

        private int spriteIndex(final int count) {
            for (int i = 0; i < DENSITY_THRESHOLDS.length; i++) {
                if (count <= DENSITY_THRESHOLDS[i]) {
                    return i;
                }
            }
            return sizes.length - 1;
        }

        private static int[] sprite(final int size, final int colour) {
            final int[] pixels = new int[size * size];
            final float center = size / 2.0f;
            final int r = (colour >> 16) & 0xFF;
            final int g = (colour >> 8) & 0xFF;
            final int b = colour & 0xFF;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    final float dx = x - center + 0.5f;
                    final float dy = y - center + 0.5f;
                    final float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    int alpha = 0;
                    if (dist < center) {
                        final float t = dist / center;
                        final float falloff = 1.0f - t * t;
                        alpha = (int) (PEAK_ALPHA * falloff * falloff);
                    }
                    pixels[y * size + x] = (alpha << 24) | (r << 16) | (g << 8) | b;
                }
            }
            return pixels;
        }

        private void blit(final int[] buffer, final int cx, final int cy, final int index, final int colour) {
            final int size = sizes[index];
            final int[] sprite = sprite(size, colour);
            final int half = size / 2;
            for (int sy = 0; sy < size; sy++) {
                final int fy = cy - half + sy;
                if (fy < 0 || fy >= height) {
                    continue;
                }
                for (int sx = 0; sx < size; sx++) {
                    final int fx = cx - half + sx;
                    if (fx < 0 || fx >= width) {
                        continue;
                    }
                    final int src = sprite[sy * size + sx];
                    final int alpha = (src >>> 24) & 0xFF;
                    if (alpha == 0) {
                        continue;
                    }
                    final int i = fy * width + fx;
                    final int dst = buffer[i];
                    final int inv = 255 - alpha;
                    final int r = (((src >> 16) & 0xFF) * alpha + ((dst >> 16) & 0xFF) * inv) / 255;
                    final int g = (((src >> 8) & 0xFF) * alpha + ((dst >> 8) & 0xFF) * inv) / 255;
                    final int b = ((src & 0xFF) * alpha + (dst & 0xFF) * inv) / 255;
                    buffer[i] = (r << 16) | (g << 8) | b;
                }
            }
        }
    }

    /** A frame of random background colours. */
    private static int[] background(final long seed) {
        final Random random = new Random(seed);
        final int[] buffer = new int[WIDTH * HEIGHT];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = random.nextInt(0x1000000);
        }
        return buffer;
    }

    /**
     * Positions of a group: scattered pixels, the four corners, indices outside the frame, and
     * piles on a few pixels that reach every density level.
     */
    private static int[] positions(final long seed) {
        final Random random = new Random(seed);
        final int[] positions = new int[300 + 4 + 2 + 1 + 5 + 20 + 60];
        int n = 0;
        for (int i = 0; i < 300; i++) {
            positions[n++] = random.nextInt(WIDTH * HEIGHT);
        }
        positions[n++] = 0;
        positions[n++] = WIDTH - 1;
        positions[n++] = (HEIGHT - 1) * WIDTH;
        positions[n++] = WIDTH * HEIGHT - 1;
        positions[n++] = -1;
        positions[n++] = WIDTH * HEIGHT;
        final int[] piles = {1, 5, 20, 60};
        for (final int pile : piles) {
            final int pixel = random.nextInt(WIDTH * HEIGHT);
            for (int i = 0; i < pile; i++) {
                positions[n++] = pixel;
            }
        }
        return positions;
    }

    @Test
    void groupsDrawnInOneColourGiveExactlyThePixelsOfTheReferenceArithmetic() {
        final int[] colours = {0x5cff3b, 0xff3bc8, 0x8f9bb3, 0xffffff, 0x000000};
        for (final double glowSize : new double[]{1.0, 1.7, 0.2}) {
            final GlowLayer layer = new GlowLayer(WIDTH, HEIGHT, glowSize);
            final Reference reference = new Reference(WIDTH, HEIGHT, glowSize);
            final int[] actual = background(7);
            final int[] expected = background(7);
            for (int group = 0; group < colours.length; group++) {
                layer.clear();
                reference.clear();
                for (final int position : positions(100 + group)) {
                    layer.add(position);
                    reference.add(position);
                }
                layer.drawTo(actual, colours[group]);
                reference.drawTo(expected, colours[group]);
            }
            assertThat(actual).as("glow size %s", glowSize).isEqualTo(expected);
        }
    }

    /** A table of colours by shade: every shade a colour of its own. */
    private static int[] distinctTable() {
        final int[] table = new int[GlowLayer.SHADES];
        for (int i = 0; i < table.length; i++) {
            table[i] = (i << 16) | ((255 - i) << 8) | (i * 7 & 0xFF);
        }
        return table;
    }

    /** The frame of one pixel with {@code count} positions drawn in one colour. */
    private static int[] onePixel(final int pixel, final int count, final int colour) {
        final GlowLayer layer = new GlowLayer(WIDTH, HEIGHT, 1.0);
        final int[] buffer = background(3);
        layer.clear();
        for (int i = 0; i < count; i++) {
            layer.add(pixel);
        }
        layer.drawTo(buffer, colour);
        return buffer;
    }

    @Test
    void aPixelWithTwoShadesIsDrawnAtTheirMean() {
        final int[] table = distinctTable();
        final int pixel = 120 * WIDTH + 200;
        // {a, b, mean rounded half up}
        for (final int[] shades : new int[][]{{40, -10, 15}, {3, 4, 4}, {-3, -4, -3}, {127, 127, 127}, {-127, 0, -63}}) {
            final GlowLayer layer = new GlowLayer(WIDTH, HEIGHT, 1.0);
            final int[] buffer = background(3);
            layer.clear();
            layer.add(pixel, shades[0]);
            layer.add(pixel, shades[1]);
            layer.drawTo(buffer, table);

            assertThat(buffer).as("shades %d and %d", shades[0], shades[1])
                .isEqualTo(onePixel(pixel, 2, table[shades[2] + GlowLayer.MAX_SHADE]));
        }
    }

    @Test
    void aGroupWhoseShadesAreAllZeroIsDrawnExactlyAsInOneColour() {
        final int colour = 0x3a7bd5;
        final int[] table = distinctTable();
        table[GlowLayer.MAX_SHADE] = colour;
        final GlowLayer shaded = new GlowLayer(WIDTH, HEIGHT, 1.0);
        final GlowLayer plain = new GlowLayer(WIDTH, HEIGHT, 1.0);
        final int[] byShade = background(5);
        final int[] inOneColour = background(5);
        shaded.clear();
        plain.clear();
        for (final int position : positions(11)) {
            shaded.add(position, 0);
            plain.add(position);
        }
        shaded.drawTo(byShade, table);
        plain.drawTo(inOneColour, colour);

        assertThat(byShade).isEqualTo(inOneColour);
    }

    @Test
    void theShadesOfAGroupDoNotReachTheNextGroup() {
        final int[] table = distinctTable();
        final int pixel = 50 * WIDTH + 60;
        final int[] expected = onePixel(pixel, 1, table[GlowLayer.MAX_SHADE]);
        final GlowLayer layer = new GlowLayer(WIDTH, HEIGHT, 1.0);

        // A group added by shade but drawn in one colour leaves its sums behind until clear()
        layer.clear();
        layer.add(pixel, 100);
        layer.drawTo(new int[WIDTH * HEIGHT], 0x123456);
        layer.clear();
        layer.add(pixel, 0);
        final int[] afterOneColour = background(3);
        layer.drawTo(afterOneColour, table);
        assertThat(afterOneColour).isEqualTo(expected);

        // A group drawn by shade clears its sums while drawing
        layer.clear();
        layer.add(pixel, -90);
        layer.drawTo(new int[WIDTH * HEIGHT], table);
        layer.clear();
        layer.add(pixel);
        final int[] afterByShade = background(3);
        layer.drawTo(afterByShade, table);
        assertThat(afterByShade).isEqualTo(expected);
    }
}
