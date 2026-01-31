package org.evochora.cli.rendering.overlay;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

import org.evochora.cli.rendering.IOverlayRenderer;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;

/**
 * Info overlay renderer displaying simulation statistics.
 * <p>
 * Styled to match the web visualizer's design language:
 * <ul>
 *   <li>Glass morphism background (semi-transparent dark)</li>
 *   <li>Subtle border and rounded corners</li>
 *   <li>Monospace font for number alignment</li>
 *   <li>Positioned at bottom-right corner</li>
 * </ul>
 * <p>
 * Displays: Tick number, Alive organisms, Total born organisms.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Use one renderer per thread.
 */
public class InfoOverlayRenderer implements IOverlayRenderer {

    // Visualizer-consistent colors
    private static final Color BACKGROUND_COLOR = new Color(25, 25, 35, 217); // rgba(25,25,35,0.85)
    private static final Color BORDER_COLOR = new Color(51, 51, 51); // #333333
    private static final Color TEXT_PRIMARY = new Color(224, 224, 224); // #e0e0e0
    private static final Color TEXT_SECONDARY = new Color(136, 136, 136); // #888888
    private static final Color TEXT_ALIVE = new Color(74, 154, 106); // #4a9a6a (organism green)

    private static final double MARGIN_RATIO = 0.015;      // Margin = 1.5% of image width
    private static final double FONT_SIZE_RATIO = 0.022;   // Font size = 2.2% of image WIDTH (not height!)
    private static final int MIN_FONT_SIZE = 10;           // Targets ~1/4 width overlay
    private static final int MAX_FONT_SIZE = 48;

    // Font settings
    private static final String[] FONT_FAMILIES = {"Roboto Mono", "Consolas", "Liberation Mono", "Monospaced"};

    private final NumberFormat numberFormat;

    // Cached rendering resources (lazily initialized on first use)
    private int cachedImageHeight;
    private Font cachedFont;

    /**
     * Creates a new info overlay renderer.
     */
    public InfoOverlayRenderer() {
        this.numberFormat = NumberFormat.getNumberInstance(Locale.GERMAN); // Uses . as thousands separator
    }

    @Override
    public void render(BufferedImage frame, TickData snapshot) {
        long tick = snapshot.getTickNumber();
        int aliveCount = countAlive(snapshot.getOrganismsList());
        long totalBorn = snapshot.getTotalOrganismsCreated();

        renderOverlay(frame, tick, aliveCount, totalBorn);
    }

    @Override
    public void render(BufferedImage frame, TickDelta delta) {
        long tick = delta.getTickNumber();
        int aliveCount = countAlive(delta.getOrganismsList());
        long totalBorn = delta.getTotalOrganismsCreated();

        renderOverlay(frame, tick, aliveCount, totalBorn);
    }

    private int countAlive(List<OrganismState> organisms) {
        int count = 0;
        for (OrganismState org : organisms) {
            if (!org.getIsDead()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Finds the first available font from the preferred list.
     *
     * @param fontSize The desired font size.
     * @return A monospace font at the specified size.
     */
    private Font findAvailableFont(int fontSize) {
        for (String family : FONT_FAMILIES) {
            Font f = new Font(family, Font.BOLD, fontSize);
            if (f.getFamily().equalsIgnoreCase(family) || family.equals("Monospaced")) {
                return f;
            }
        }
        return new Font(Font.MONOSPACED, Font.BOLD, fontSize);
    }

    /**
     * Formats a number with appropriate notation.
     * <ul>
     *   <li>Under 1,000,000: Full number with thousands separator (1.234.567)</li>
     *   <li>1M+: Short form (1.2M)</li>
     * </ul>
     *
     * @param value The number to format.
     * @return Formatted string.
     */
    private String formatNumber(long value) {
        if (value >= 1_000_000) {
            double millions = value / 1_000_000.0;
            if (millions >= 100) {
                return String.format("%.0fM", millions);
            } else if (millions >= 10) {
                return String.format("%.1fM", millions);
            } else {
                return String.format("%.2fM", millions);
            }
        }
        return numberFormat.format(value);
    }

    /**
     * Renders the overlay panel onto the given image.
     * Panel size scales automatically with image resolution.
     *
     * @param image The image to render onto.
     * @param tick Current tick number.
     * @param aliveCount Number of alive organisms.
     * @param totalBorn Total organisms ever created.
     */
    private void renderOverlay(BufferedImage image, long tick, int aliveCount, long totalBorn) {
        int imgWidth = image.getWidth();
        int imgHeight = image.getHeight();

        // Calculate sizes relative to image WIDTH (so overlay is always ~1/4 of width)
        int fontSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE,
            (int) (imgWidth * FONT_SIZE_RATIO)));
        int margin = Math.max(5, (int) (imgWidth * MARGIN_RATIO));
        int paddingX = fontSize;
        int paddingY = fontSize / 2;
        int lineSpacing = fontSize / 4;
        int borderRadius = fontSize / 2;
        int borderWidth = Math.max(1, fontSize / 10);

        // Cache font if image dimensions changed
        if (imgHeight != cachedImageHeight || cachedFont == null) {
            cachedImageHeight = imgHeight;
            cachedFont = findAvailableFont(fontSize);
        }

        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        g2d.setFont(cachedFont);
        FontMetrics fm = g2d.getFontMetrics();

        // Prepare text content
        String tickLabel = "Tick";
        String aliveLabel = "Alive";
        String bornLabel = "Born";

        String tickValue = formatNumber(tick);
        String aliveValue = formatNumber(aliveCount);
        String bornValue = formatNumber(totalBorn);

        // Calculate panel dimensions
        int labelWidth = Math.max(fm.stringWidth(tickLabel),
                         Math.max(fm.stringWidth(aliveLabel), fm.stringWidth(bornLabel)));
        int valueWidth = Math.max(fm.stringWidth(tickValue),
                         Math.max(fm.stringWidth(aliveValue), fm.stringWidth(bornValue)));
        int gap = fm.stringWidth("    "); // Space between label and value

        int panelWidth = paddingX * 2 + labelWidth + gap + valueWidth;
        int lineHeight = fm.getHeight();
        int panelHeight = paddingY * 2 + lineHeight * 3 + lineSpacing * 2;

        // Position at bottom-right
        int panelX = imgWidth - panelWidth - margin;
        int panelY = imgHeight - panelHeight - margin;

        // Draw background with rounded corners
        g2d.setColor(BACKGROUND_COLOR);
        g2d.fillRoundRect(panelX, panelY, panelWidth, panelHeight, borderRadius, borderRadius);

        // Draw border
        g2d.setColor(BORDER_COLOR);
        g2d.setStroke(new BasicStroke(borderWidth));
        g2d.drawRoundRect(panelX, panelY, panelWidth, panelHeight, borderRadius, borderRadius);

        // Draw text
        int textX = panelX + paddingX;
        int valueX = panelX + panelWidth - paddingX - valueWidth;
        int textY = panelY + paddingY + fm.getAscent();

        // Line 1: Tick
        g2d.setColor(TEXT_SECONDARY);
        g2d.drawString(tickLabel, textX, textY);
        g2d.setColor(TEXT_PRIMARY);
        g2d.drawString(tickValue, valueX + (valueWidth - fm.stringWidth(tickValue)), textY);

        // Line 2: Alive
        textY += lineHeight + lineSpacing;
        g2d.setColor(TEXT_SECONDARY);
        g2d.drawString(aliveLabel, textX, textY);
        g2d.setColor(TEXT_ALIVE);
        g2d.drawString(aliveValue, valueX + (valueWidth - fm.stringWidth(aliveValue)), textY);

        // Line 3: Born
        textY += lineHeight + lineSpacing;
        g2d.setColor(TEXT_SECONDARY);
        g2d.drawString(bornLabel, textX, textY);
        g2d.setColor(TEXT_PRIMARY);
        g2d.drawString(bornValue, valueX + (valueWidth - fm.stringWidth(bornValue)), textY);

        g2d.dispose();
    }
}
