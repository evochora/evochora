package org.evochora.cli.rendering.overlay;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import org.evochora.cli.rendering.IOverlayRenderer;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;

/**
 * Logo overlay renderer displaying the EVOCHORA brand text.
 * <p>
 * Renders "EVOCHORA" in the Doto Black font with letter-spacing matching
 * the web visualizer's logo panel. Positioned at the top-right corner
 * with glass morphism background.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Use one renderer per thread.
 */
public class LogoOverlayRenderer implements IOverlayRenderer {

    private static final String LOGO_TEXT = "EVOCHORA";
    /** Logo font is 70% of the data font size. */
    private static final double LOGO_FONT_RATIO = 1.40;
    /** Letter spacing in pixels, scaled with font size. */
    private static final double LETTER_SPACING_RATIO = 0.08;

    // Cached rendering resources
    private int cachedImageHeight;
    private Font cachedFont;

    /**
     * Creates a new logo overlay renderer.
     */
    public LogoOverlayRenderer() {
        // No-arg constructor for reflection-based instantiation
    }

    @Override
    public void render(BufferedImage frame, TickData snapshot) {
        renderOverlay(frame);
    }

    @Override
    public void render(BufferedImage frame, TickDelta delta) {
        renderOverlay(frame);
    }

    private void renderOverlay(BufferedImage image) {
        int imgWidth = image.getWidth();
        int imgHeight = image.getHeight();

        int dataFontSize = OverlayFonts.computeFontSize(imgWidth);
        int logoFontSize = Math.max(OverlayFonts.MIN_FONT_SIZE,
                (int) (dataFontSize * LOGO_FONT_RATIO));
        int margin = OverlayFonts.computeMargin(imgWidth);
        int paddingX = logoFontSize;
        int paddingY = logoFontSize / 2;
        int borderRadius = logoFontSize / 2;
        int borderWidth = Math.max(1, logoFontSize / 10);
        float letterSpacing = (float) (logoFontSize * LETTER_SPACING_RATIO);

        if (imgHeight != cachedImageHeight || cachedFont == null) {
            cachedImageHeight = imgHeight;
            cachedFont = OverlayFonts.getLogoFont(logoFontSize);
        }

        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        g2d.setFont(cachedFont);
        FontMetrics fm = g2d.getFontMetrics();

        // Calculate text width with letter spacing
        int textWidth = 0;
        for (int i = 0; i < LOGO_TEXT.length(); i++) {
            textWidth += fm.charWidth(LOGO_TEXT.charAt(i));
            if (i < LOGO_TEXT.length() - 1) {
                textWidth += (int) letterSpacing;
            }
        }

        int lineHeight = fm.getHeight();
        int panelWidth = paddingX * 2 + textWidth;
        int panelHeight = paddingY * 2 + lineHeight;

        // Position at top-right
        int panelX = imgWidth - panelWidth - margin;
        int panelY = margin;

        // Draw background
        g2d.setColor(OverlayFonts.BACKGROUND);
        g2d.fillRoundRect(panelX, panelY, panelWidth, panelHeight, borderRadius, borderRadius);

        // Draw border
        g2d.setColor(OverlayFonts.BORDER);
        g2d.setStroke(new BasicStroke(borderWidth));
        g2d.drawRoundRect(panelX, panelY, panelWidth, panelHeight, borderRadius, borderRadius);

        // Draw logo text with letter spacing
        g2d.setColor(OverlayFonts.TEXT_PRIMARY);
        float x = panelX + paddingX;
        int textY = panelY + paddingY + fm.getAscent();

        for (int i = 0; i < LOGO_TEXT.length(); i++) {
            char c = LOGO_TEXT.charAt(i);
            g2d.drawString(String.valueOf(c), x, textY);
            x += fm.charWidth(c) + letterSpacing;
        }

        g2d.dispose();
    }
}
