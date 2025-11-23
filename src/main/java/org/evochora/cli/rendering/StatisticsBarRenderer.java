package org.evochora.cli.rendering;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/**
 * Renders a vertical stacked bar chart showing organism statistics.
 * <p>
 * The bar shows dead organisms (gray) stacked on top of alive organisms (green),
 * growing from bottom to top as the simulation progresses.
 */
public class StatisticsBarRenderer {

    private final int barWidth;
    private final int barHeight;
    private final BufferedImage barImage;
    private final int[] barBuffer;

    private final int colorBackground = Color.decode("#1a1a1a").getRGB(); // Dark gray background
    private final int colorAlive = Color.decode("#32cd32").getRGB(); // Green for alive organisms
    private final int colorDead = Color.decode("#808080").getRGB(); // Gray for dead organisms
    private final int colorBorder = Color.decode("#ffffff").getRGB(); // White border

    /**
     * Creates a new statistics bar renderer.
     *
     * @param barWidth The width of the bar in pixels.
     * @param barHeight The height of the bar in pixels (should match video height).
     */
    public StatisticsBarRenderer(int barWidth, int barHeight) {
        this.barWidth = barWidth;
        this.barHeight = barHeight;
        this.barImage = new BufferedImage(barWidth, barHeight, BufferedImage.TYPE_INT_RGB);
        this.barBuffer = ((DataBufferInt) barImage.getRaster().getDataBuffer()).getData();
    }

    /**
     * Renders the statistics bar with the given organism counts.
     * <p>
     * The bar shows:
     * - Max = 100% = entire bar height
     * - Dead organisms (gray) at the bottom
     * - Alive organisms (green) stacked on top of dead
     *
     * @param aliveCount Number of alive organisms.
     * @param deadCount Number of dead organisms (cumulative).
     * @param maxCount Maximum total organisms (alive + dead) for scaling. This represents 100% of the bar height.
     * @return The rendered bar as a pixel array (RGB format).
     */
    public int[] render(int aliveCount, int deadCount, int maxCount) {
        // Clear background
        Arrays.fill(barBuffer, colorBackground);

        if (maxCount == 0) {
            return barBuffer;
        }

        // Draw border (1 pixel on all sides)
        drawBorder();

        // Calculate heights relative to maxCount (which represents 100% = barHeight)
        // Both alive and dead are scaled by the same maxCount
        int deadHeight = maxCount > 0 ? (int) ((long) deadCount * (barHeight - 2) / maxCount) : 0;
        int aliveHeight = maxCount > 0 ? (int) ((long) aliveCount * (barHeight - 2) / maxCount) : 0;
        
        // Clamp heights to fit within bar (accounting for 1px border on each side)
        int maxBarHeight = barHeight - 2; // Inside border
        deadHeight = Math.min(deadHeight, maxBarHeight);
        aliveHeight = Math.min(aliveHeight, maxBarHeight - deadHeight); // Ensure alive doesn't exceed remaining space

        // Draw stacked bars from bottom (inside border)
        int barStartX = 1; // Inside border
        int barEndX = barWidth - 1; // Inside border

        // Draw dead organisms (gray) - bottom part
        if (deadHeight > 0) {
            int deadStartY = barHeight - 1 - deadHeight; // From bottom, inside border
            for (int y = deadStartY; y < barHeight - 1; y++) {
                if (y >= 1 && y < barHeight - 1) { // Inside border
                    int startIndex = y * barWidth + barStartX;
                    int endIndex = y * barWidth + barEndX;
                    Arrays.fill(barBuffer, startIndex, endIndex, colorDead);
                }
            }
        }

        // Draw alive organisms (green) - stacked on top of dead
        if (aliveHeight > 0) {
            int aliveStartY = barHeight - 1 - deadHeight - aliveHeight; // Above dead, inside border
            int aliveEndY = barHeight - 1 - deadHeight; // Just above dead section
            for (int y = aliveStartY; y < aliveEndY; y++) {
                if (y >= 1 && y < barHeight - 1) { // Inside border
                    int startIndex = y * barWidth + barStartX;
                    int endIndex = y * barWidth + barEndX;
                    Arrays.fill(barBuffer, startIndex, endIndex, colorAlive);
                }
            }
        }

        return barBuffer;
    }

    /**
     * Draws a 1-pixel border around the bar.
     */
    private void drawBorder() {
        // Top and bottom borders
        Arrays.fill(barBuffer, 0, barWidth, colorBorder);
        Arrays.fill(barBuffer, (barHeight - 1) * barWidth, barHeight * barWidth, colorBorder);

        // Left and right borders
        for (int y = 0; y < barHeight; y++) {
            barBuffer[y * barWidth] = colorBorder; // Left
            barBuffer[y * barWidth + barWidth - 1] = colorBorder; // Right
        }
    }

    /**
     * Returns the width of the bar in pixels.
     *
     * @return The bar width.
     */
    public int getWidth() {
        return barWidth;
    }

    /**
     * Returns the height of the bar in pixels.
     *
     * @return The bar height.
     */
    public int getHeight() {
        return barHeight;
    }
}

