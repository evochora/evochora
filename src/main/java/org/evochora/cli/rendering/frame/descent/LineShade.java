package org.evochora.cli.rendering.frame.descent;

import org.evochora.cli.rendering.frame.shared.GlowLayer;

/**
 * The shade of an organism within its line: a drift of the line's lightness along the genome
 * chain, so that every change of the genome moves the colour a little while the line keeps its
 * hue.
 * <p>
 * A shade is a signed value in [{@code -}{@link #MAX_SHADE}, {@link #MAX_SHADE}]; 0 is the
 * line's colour as it is. A founder has shade 0; an organism with its parent's genome has its
 * parent's shade; an organism whose genome differs from its parent's has the parent's shade
 * moved by a step of {@link #drift}. The step follows from the organism's genome hash alone: its
 * direction and its size, between {@link #MIN_STEP} and {@link #MAX_STEP}, are taken from
 * well-mixed bits of the hash, and a step beyond either end of the range is reflected back into
 * it. The same parent shade and the same genome therefore give the same shade on every machine.
 * <p>
 * Parameters:
 * <ul>
 *   <li>{@link #MIN_STEP}, {@link #MAX_STEP}: the size range of one step, 10 % and 30 % of
 *       {@link #MAX_SHADE}.</li>
 *   <li>{@link #SPREAD}: how far the extreme shades move the lightness in HSL
 *       ({@link #colour}).</li>
 * </ul>
 * The class holds the rule alone: the pre-pass decides which organisms have a changed genome and
 * the renderer which groups are shaded, so that a variant of the rule is a variant of this class
 * and nothing else.
 * <p>
 * <strong>Thread Safety:</strong> stateless and thread-safe.
 */
final class LineShade {

    /**
     * The largest shade in either direction: the range of shades a glow position can carry,
     * {@link GlowLayer#MAX_SHADE}.
     */
    static final int MAX_SHADE = GlowLayer.MAX_SHADE;

    /** The smallest step of a changed genome: 10 % of {@link #MAX_SHADE}, rounded. */
    static final int MIN_STEP = 13;

    /** The largest step of a changed genome: 30 % of {@link #MAX_SHADE}, rounded down. */
    static final int MAX_STEP = 38;

    /** The lightness shift, in HSL lightness, of the shade {@link #MAX_SHADE}; negative shades darken. */
    static final double SPREAD = 0.18;

    private LineShade() {
    }

    /**
     * The shade of an organism whose genome differs from its parent's.
     *
     * @param parentShade The parent's shade, in [{@code -MAX_SHADE}, {@code MAX_SHADE}]
     * @param genomeHash  The organism's genome hash
     * @return The parent's shade moved by the step of the genome, reflected at
     *         {@code -MAX_SHADE} and {@code MAX_SHADE}
     */
    static byte drift(final byte parentShade, final long genomeHash) {
        final long mixed = mix(genomeHash);
        final int size = MIN_STEP + (int) ((mixed >>> 1) % (MAX_STEP - MIN_STEP + 1));
        int shade = mixed < 0 ? parentShade - size : parentShade + size;
        if (shade > MAX_SHADE) {
            shade = 2 * MAX_SHADE - shade;
        } else if (shade < -MAX_SHADE) {
            shade = -2 * MAX_SHADE - shade;
        }
        return (byte) shade;
    }

    /**
     * The colour of a shade: the colour's lightness in HSL moved by
     * {@code shade / MAX_SHADE * SPREAD} and clamped to [0, 1], hue and saturation unchanged.
     *
     * @param rgb   The line's colour (0xRRGGBB)
     * @param shade The shade, in [{@code -MAX_SHADE}, {@code MAX_SHADE}]
     * @return The shaded colour (0xRRGGBB); for shade 0 {@code rgb} itself
     */
    static int colour(final int rgb, final int shade) {
        if (shade == 0) {
            return rgb;
        }
        final double r = ((rgb >> 16) & 0xFF) / 255.0;
        final double g = ((rgb >> 8) & 0xFF) / 255.0;
        final double b = (rgb & 0xFF) / 255.0;
        final double max = Math.max(r, Math.max(g, b));
        final double min = Math.min(r, Math.min(g, b));
        final double lightness = (max + min) / 2;
        final double chroma = max - min;
        double hue = 0;
        double saturation = 0;
        if (chroma > 0) {
            saturation = chroma / (1 - Math.abs(2 * lightness - 1));
            if (max == r) {
                hue = ((g - b) / chroma) % 6;
            } else if (max == g) {
                hue = (b - r) / chroma + 2;
            } else {
                hue = (r - g) / chroma + 4;
            }
            if (hue < 0) {
                hue += 6;
            }
        }
        final double shifted = Math.max(0, Math.min(1, lightness + shade * SPREAD / MAX_SHADE));
        return fromHsl(hue, saturation, shifted);
    }

    /**
     * Converts HSL to RGB.
     *
     * @param hue        Hue in sixths of the circle, in [0, 6)
     * @param saturation Saturation in [0, 1]
     * @param lightness  Lightness in [0, 1]
     * @return The colour (0xRRGGBB)
     */
    private static int fromHsl(final double hue, final double saturation, final double lightness) {
        final double chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
        final double x = chroma * (1 - Math.abs(hue % 2 - 1));
        final double m = lightness - chroma / 2;
        final double r;
        final double g;
        final double b;
        switch ((int) hue) {
            case 0 -> { r = chroma; g = x; b = 0; }
            case 1 -> { r = x; g = chroma; b = 0; }
            case 2 -> { r = 0; g = chroma; b = x; }
            case 3 -> { r = 0; g = x; b = chroma; }
            case 4 -> { r = x; g = 0; b = chroma; }
            default -> { r = chroma; g = 0; b = x; }
        }
        return (channel(r + m) << 16) | (channel(g + m) << 8) | channel(b + m);
    }

    private static int channel(final double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value * 255)));
    }

    /**
     * Mixes the bits of a hash so that every bit of the result depends on every bit of the input
     * (the finaliser of SplitMix64).
     */
    private static long mix(final long hash) {
        long z = hash + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
