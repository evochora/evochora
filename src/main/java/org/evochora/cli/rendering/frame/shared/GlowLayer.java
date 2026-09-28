package org.evochora.cli.rendering.frame.shared;

import java.util.Arrays;

/**
 * Draws groups of organisms as soft glows ("territory clouds") onto a frame buffer.
 * <p>
 * A group is drawn in one pass: its positions are first counted per output pixel
 * ({@link #clear()}, {@link #add(int)}), then every pixel with a count receives a glow sprite
 * whose size grows with the count. The sprite is a semi-transparent bell with a smooth quartic
 * falloff, alpha-blended over what the buffer already holds, so groups drawn later lie above
 * those drawn before.
 * <p>
 * Two ways to draw a group:
 * <ul>
 *   <li><em>In one colour</em> ({@link #drawTo(int[], int)}): every sprite of the group takes the
 *       colour.</li>
 *   <li><em>By shade</em> ({@link #add(int, int)}, {@link #drawTo(int[], int[])}): every position
 *       carries a shade in [{@code -}{@link #MAX_SHADE}, {@link #MAX_SHADE}]; a pixel takes the mean
 *       shade of the positions on it, rounded, and its sprite takes the colour a table gives for
 *       that shade. A group whose shades are all 0 is drawn exactly as in one colour, given the
 *       table's entry for 0 is that colour.</li>
 * </ul>
 * <p>
 * Sprite sizes scale with the output width relative to 400 pixels and with a user multiplier. The
 * sprites are colourless: one set of alpha masks, made once, and the colour is given when a sprite
 * is blended. The per-pixel shade sums are allocated on the first position added with a shade, so
 * a layer that draws no group by shade never holds them; nothing is allocated while drawing.
 * <p>
 * <strong>Thread Safety:</strong> not thread-safe; one instance per renderer instance.
 */
public final class GlowLayer {

    /**
     * The largest shade a position can carry in either direction: a shade lies in
     * [{@code -MAX_SHADE}, {@code MAX_SHADE}].
     */
    public static final int MAX_SHADE = 127;

    /** Length of a table of colours by shade: one entry per shade, the shade 0 in the middle. */
    public static final int SHADES = 2 * MAX_SHADE + 1;

    /** Sprite sizes for density levels (scaled by output resolution). */
    private static final int[] BASE_GLOW_SIZES = {8, 12, 16, 22};
    /** Density thresholds for glow size selection. */
    private static final int[] DENSITY_THRESHOLDS = {3, 10, 30};
    /** Reference width for glow scaling. */
    private static final int BASE_OUTPUT_WIDTH = 400;
    /** Peak opacity at glow center. */
    private static final int PEAK_ALPHA = 180;

    private final int outputWidth;
    private final int outputHeight;
    private final int[] glowSizes;
    /** The alpha of every sprite pixel, per density level. */
    private final int[][] sprites;
    private final int[] density;
    /** Sum of the shades per pixel of the current group; {@code null} until a shade is added. */
    private int[] shadeSums;
    /** Whether {@link #shadeSums} may hold values that the next group must not see. */
    private boolean shadesPending;

    /**
     * Creates a layer for frames of the given size.
     *
     * @param outputWidth  Frame width in pixels (must be &gt; 0)
     * @param outputHeight Frame height in pixels (must be &gt; 0)
     * @param glowSize     Multiplier of the sprite sizes (must be &gt; 0)
     */
    public GlowLayer(final int outputWidth, final int outputHeight, final double glowSize) {
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        final double glowScale = (double) outputWidth / BASE_OUTPUT_WIDTH * glowSize;
        this.glowSizes = new int[BASE_GLOW_SIZES.length];
        this.sprites = new int[BASE_GLOW_SIZES.length][];
        for (int i = 0; i < BASE_GLOW_SIZES.length; i++) {
            this.glowSizes[i] = Math.max(2, (int) (BASE_GLOW_SIZES[i] * glowScale));
            this.sprites[i] = createSprite(glowSizes[i]);
        }
        this.density = new int[outputWidth * outputHeight];
    }

    /**
     * Starts a new group: forgets the positions counted so far.
     */
    public void clear() {
        Arrays.fill(density, 0);
        if (shadesPending) {
            Arrays.fill(shadeSums, 0);
            shadesPending = false;
        }
    }

    /**
     * Counts one position of the current group.
     *
     * @param pixelIndex Index of the output pixel; an index outside the frame is ignored
     */
    public void add(final int pixelIndex) {
        if (pixelIndex >= 0 && pixelIndex < density.length) {
            density[pixelIndex]++;
        }
    }

    /**
     * Counts one position of the current group with its shade, for a group drawn by shade.
     *
     * @param pixelIndex Index of the output pixel; an index outside the frame is ignored
     * @param shade      The shade of the position, in [{@code -}{@link #MAX_SHADE},
     *                   {@link #MAX_SHADE}]
     */
    public void add(final int pixelIndex, final int shade) {
        if (pixelIndex >= 0 && pixelIndex < density.length) {
            if (shadeSums == null) {
                shadeSums = new int[density.length];
            }
            density[pixelIndex]++;
            shadeSums[pixelIndex] += shade;
            shadesPending = true;
        }
    }

    /**
     * Draws the current group in one colour.
     *
     * @param frameBuffer The frame to draw into, {@code outputWidth * outputHeight} RGB pixels
     * @param colour      RGB colour (0xRRGGBB)
     */
    public void drawTo(final int[] frameBuffer, final int colour) {
        final int r = (colour >> 16) & 0xFF;
        final int g = (colour >> 8) & 0xFF;
        final int b = colour & 0xFF;
        for (int my = 0; my < outputHeight; my++) {
            for (int mx = 0; mx < outputWidth; mx++) {
                final int count = density[my * outputWidth + mx];
                if (count > 0) {
                    blit(frameBuffer, mx, my, selectSpriteIndex(count), r, g, b);
                }
            }
        }
    }

    /**
     * Draws the current group by shade: every pixel with positions takes the mean shade of its
     * positions, rounded half up, and its sprite the colour the table gives for that shade.
     * Positions added without a shade count as shade 0. Leaves the shade sums cleared.
     *
     * @param frameBuffer    The frame to draw into, {@code outputWidth * outputHeight} RGB pixels
     * @param coloursByShade RGB colour (0xRRGGBB) of every shade, at index
     *                       {@code shade + MAX_SHADE} (length {@link #SHADES})
     */
    public void drawTo(final int[] frameBuffer, final int[] coloursByShade) {
        final int[] sums = shadeSums;
        for (int my = 0; my < outputHeight; my++) {
            for (int mx = 0; mx < outputWidth; mx++) {
                final int index = my * outputWidth + mx;
                final int count = density[index];
                if (count > 0) {
                    int shade = 0;
                    if (sums != null) {
                        shade = Math.floorDiv(2 * sums[index] + count, 2 * count);
                        sums[index] = 0;
                    }
                    final int colour = coloursByShade[shade + MAX_SHADE];
                    blit(frameBuffer, mx, my, selectSpriteIndex(count),
                        (colour >> 16) & 0xFF, (colour >> 8) & 0xFF, colour & 0xFF);
                }
            }
        }
        // Every sum that was not 0 lay on a pixel with positions and has been cleared above
        shadesPending = false;
    }

    /**
     * Creates the alpha mask of a glow sprite of the given size.
     * Uses a smooth quartic radial falloff {@code (1 - r²)²} from center to edge,
     * producing a soft bell curve without hard core/edge boundaries.
     *
     * @param size Total sprite size in pixels.
     * @return The alpha (0 to {@value #PEAK_ALPHA}) of every sprite pixel, row by row.
     */
    private static int[] createSprite(final int size) {
        final int[] alphas = new int[size * size];
        final float center = size / 2.0f;
        final float radius = center;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                final float dx = x - center + 0.5f;
                final float dy = y - center + 0.5f;
                final float dist = (float) Math.sqrt(dx * dx + dy * dy);

                if (dist < radius) {
                    // Smooth quartic bell: (1 - r²)² — no hard core/edge boundary
                    final float t = dist / radius;
                    final float falloff = 1.0f - t * t;
                    alphas[y * size + x] = (int) (PEAK_ALPHA * falloff * falloff);
                }
            }
        }

        return alphas;
    }

    /**
     * Selects the glow sprite index based on organism density count.
     *
     * @param count Number of positions at this pixel.
     * @return Sprite index (larger sprite for higher density).
     */
    private int selectSpriteIndex(final int count) {
        for (int i = 0; i < DENSITY_THRESHOLDS.length; i++) {
            if (count <= DENSITY_THRESHOLDS[i]) {
                return i;
            }
        }
        return glowSizes.length - 1;
    }

    /**
     * Alpha-blends a glow sprite in a colour onto the frame buffer at the given center position.
     */
    private void blit(final int[] frameBuffer, final int centerX, final int centerY, final int spriteIndex,
                      final int r, final int g, final int b) {
        final int[] sprite = sprites[spriteIndex];
        final int size = glowSizes[spriteIndex];
        final int half = size / 2;
        final int startX = centerX - half;
        final int startY = centerY - half;

        for (int sy = 0; sy < size; sy++) {
            final int fy = startY + sy;
            if (fy < 0 || fy >= outputHeight) {
                continue;
            }

            for (int sx = 0; sx < size; sx++) {
                final int fx = startX + sx;
                if (fx < 0 || fx >= outputWidth) {
                    continue;
                }

                final int alpha = sprite[sy * size + sx];
                if (alpha == 0) {
                    continue;
                }

                final int idx = fy * outputWidth + fx;
                final int dst = frameBuffer[idx];

                // Alpha blend
                final int invA = 255 - alpha;
                final int outR = (r * alpha + ((dst >> 16) & 0xFF) * invA) / 255;
                final int outG = (g * alpha + ((dst >> 8) & 0xFF) * invA) / 255;
                final int outB = (b * alpha + (dst & 0xFF) * invA) / 255;

                frameBuffer[idx] = (outR << 16) | (outG << 8) | outB;
            }
        }
    }
}
