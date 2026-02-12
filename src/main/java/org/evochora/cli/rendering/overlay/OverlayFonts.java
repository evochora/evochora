package org.evochora.cli.rendering.overlay;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Shared font loading and styling constants for video overlay renderers.
 * <p>
 * Loads embedded TTF fonts (Roboto Mono for data panels, Doto for logo) from
 * classpath resources. Fonts are cached after first load. No system font
 * fallback — throws {@link IllegalStateException} if resources are missing.
 * <p>
 * All overlay renderers should use these constants for consistent visual
 * appearance matching the web visualizer's design language.
 * <p>
 * <strong>Thread Safety:</strong> All methods are thread-safe via synchronized lazy loading.
 */
public final class OverlayFonts {

    // ─────────────────────────────────────────────────────────────────────────────
    // Shared styling constants
    // ─────────────────────────────────────────────────────────────────────────────

    /** Font size as fraction of image width. */
    public static final double FONT_SIZE_RATIO = 0.016;
    /** Panel margin as fraction of image width. */
    public static final double MARGIN_RATIO = 0.015;
    /** Minimum font size in pixels. */
    public static final int MIN_FONT_SIZE = 8;
    /** Maximum font size in pixels. */
    public static final int MAX_FONT_SIZE = 36;

    /** Glass morphism background (70% opacity). */
    public static final Color BACKGROUND = new Color(25, 25, 35, 178);
    /** Panel border color. */
    public static final Color BORDER = new Color(51, 51, 51);
    /** Primary text color (light gray). */
    public static final Color TEXT_PRIMARY = new Color(224, 224, 224);
    /** Secondary text color (medium gray, for labels). */
    public static final Color TEXT_SECONDARY = new Color(136, 136, 136);

    // ─────────────────────────────────────────────────────────────────────────────
    // Font resources
    // ─────────────────────────────────────────────────────────────────────────────

    private static final String DATA_FONT_PATH = "/fonts/RobotoMono-Regular.ttf";
    private static final String LOGO_FONT_PATH = "/fonts/Doto-Black.ttf";

    private static Font dataFontBase;
    private static Font logoFontBase;

    private OverlayFonts() {
        // Utility class
    }

    /**
     * Returns the data font (Roboto Mono Regular) at the specified size.
     *
     * @param size Font size in points.
     * @return Roboto Mono font at the requested size.
     * @throws IllegalStateException if the font resource is not found.
     */
    public static Font getDataFont(int size) {
        return getOrLoadDataFont().deriveFont(Font.PLAIN, size);
    }

    /**
     * Returns the logo font (Doto Black) at the specified size.
     *
     * @param size Font size in points.
     * @return Doto Black font at the requested size.
     * @throws IllegalStateException if the font resource is not found.
     */
    public static Font getLogoFont(int size) {
        return getOrLoadLogoFont().deriveFont(Font.PLAIN, size);
    }

    /**
     * Computes a responsive font size based on image width.
     *
     * @param imageWidth Width of the target image in pixels.
     * @return Font size clamped between {@link #MIN_FONT_SIZE} and {@link #MAX_FONT_SIZE}.
     */
    public static int computeFontSize(int imageWidth) {
        return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE,
                (int) (imageWidth * FONT_SIZE_RATIO)));
    }

    /**
     * Computes a responsive margin based on image width.
     *
     * @param imageWidth Width of the target image in pixels.
     * @return Margin in pixels, at least 5.
     */
    public static int computeMargin(int imageWidth) {
        return Math.max(5, (int) (imageWidth * MARGIN_RATIO));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Lazy font loading
    // ─────────────────────────────────────────────────────────────────────────────

    private static synchronized Font getOrLoadDataFont() {
        if (dataFontBase == null) {
            dataFontBase = loadFont(DATA_FONT_PATH);
        }
        return dataFontBase;
    }

    private static synchronized Font getOrLoadLogoFont() {
        if (logoFontBase == null) {
            logoFontBase = loadFont(LOGO_FONT_PATH);
        }
        return logoFontBase;
    }

    private static Font loadFont(String resourcePath) {
        try (InputStream stream = OverlayFonts.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Font resource not found on classpath: " + resourcePath);
            }
            return Font.createFont(Font.TRUETYPE_FONT, stream);
        } catch (FontFormatException | IOException e) {
            throw new IllegalStateException(
                    "Failed to load font from " + resourcePath + ": " + e.getMessage(), e);
        }
    }
}
