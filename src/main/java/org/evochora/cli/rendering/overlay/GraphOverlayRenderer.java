package org.evochora.cli.rendering.overlay;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.evochora.cli.rendering.IOverlayRenderer;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;

/**
 * Graph overlay renderer displaying organism count and genome count over time.
 * <p>
 * Renders a time-series graph at the bottom center of the frame, positioned
 * between the diversity panel (bottom-left) and info panel (bottom-right).
 * Two smooth spline curves are drawn:
 * <ul>
 *   <li><strong>Organisms:</strong> Number of alive organisms per tick (green)</li>
 *   <li><strong>Genomes:</strong> Number of unique genome hashes per tick (gold)</li>
 * </ul>
 * <p>
 * Data accumulates across all rendered frames. When there are more data points
 * than {@link #MAX_DISPLAY_POINTS}, they are evenly sampled to always show
 * approximately that many points — no rolling window, full history preserved.
 * <p>
 * <strong>Stability:</strong> The Y-axis maximum is monotonically increasing and
 * rounded to nice numbers, preventing graph jitter. X-axis uses tick-proportional
 * positioning so the graph fills from left to right as time progresses.
 * <p>
 * <strong>Thread Safety:</strong> The internal {@link GraphHistory} is thread-safe
 * and shared across thread instances via {@link #initFromOriginal(IOverlayRenderer)}.
 *
 * @see DiversityOverlayRenderer
 * @see InfoOverlayRenderer
 */
public class GraphOverlayRenderer implements IOverlayRenderer {

    /** Maximum number of data points rendered on the graph. */
    static final int MAX_DISPLAY_POINTS = 500;

    /** Graph width as fraction of image width. */
    private static final double GRAPH_WIDTH_RATIO = 0.40;
    /** Graph height as fraction of image height. */
    private static final double GRAPH_HEIGHT_RATIO = 0.15;
    /** Minimum graph height in pixels. */
    private static final int MIN_GRAPH_HEIGHT = 40;
    /** Minimum graph width in pixels. */
    private static final int MIN_GRAPH_WIDTH = 80;

    /** Organism line color (matching InfoOverlayRenderer's alive color). */
    static final Color LINE_ORGANISMS = new Color(74, 154, 106);   // #4a9a6a
    /** Genome line color (warm gold, high contrast to green). */
    static final Color LINE_GENOMES = new Color(200, 175, 80);     // #c8af50
    /** Axis line color. */
    private static final Color AXIS_COLOR = new Color(80, 80, 90);

    /** Catmull-Rom tension (0.5 = standard). */
    private static final float CR_TENSION = 0.5f;

    // Shared across thread instances
    private GraphHistory sharedHistory;

    // Cached rendering resources
    private int cachedImageHeight;
    private Font cachedFont;

    /**
     * Creates a new graph overlay renderer with its own history.
     */
    public GraphOverlayRenderer() {
        this.sharedHistory = new GraphHistory();
    }

    @Override
    public void initFromOriginal(IOverlayRenderer original) {
        this.sharedHistory = ((GraphOverlayRenderer) original).sharedHistory;
    }

    @Override
    public void render(BufferedImage frame, TickData snapshot) {
        long tick = snapshot.getTickNumber();
        int[] counts = countOrganismsAndGenomes(snapshot.getOrganismsList());
        sharedHistory.addPoint(tick, counts[0], counts[1]);
        renderOverlay(frame);
    }

    @Override
    public void render(BufferedImage frame, TickDelta delta) {
        long tick = delta.getTickNumber();
        int[] counts = countOrganismsAndGenomes(delta.getOrganismsList());
        sharedHistory.addPoint(tick, counts[0], counts[1]);
        renderOverlay(frame);
    }

    /**
     * Counts alive organisms and unique genomes in a single pass.
     *
     * @param organisms List of organisms.
     * @return Array of [aliveCount, genomeCount].
     */
    private int[] countOrganismsAndGenomes(List<OrganismState> organisms) {
        int alive = 0;
        HashSet<Long> genomes = new HashSet<>();

        for (OrganismState org : organisms) {
            if (org.getIsDead()) continue;
            alive++;
            long hash = org.getGenomeHash();
            if (hash != 0L) {
                genomes.add(hash);
            }
        }

        return new int[]{alive, genomes.size()};
    }

    private void renderOverlay(BufferedImage image) {
        int imgWidth = image.getWidth();
        int imgHeight = image.getHeight();

        int fontSize = OverlayFonts.computeFontSize(imgWidth);
        int margin = OverlayFonts.computeMargin(imgWidth);
        int borderRadius = fontSize / 2;
        int borderWidth = Math.max(1, fontSize / 10);
        int labelFontSize = Math.max(OverlayFonts.MIN_FONT_SIZE, fontSize * 3 / 4);

        int graphWidth = Math.max(MIN_GRAPH_WIDTH, (int) (imgWidth * GRAPH_WIDTH_RATIO));
        int graphHeight = Math.max(MIN_GRAPH_HEIGHT, (int) (imgHeight * GRAPH_HEIGHT_RATIO));

        int paddingX = fontSize;
        int paddingY = fontSize / 2;
        int labelAreaHeight = labelFontSize + 4;
        // Extra padding on right for value labels
        int valueLabelWidth = labelFontSize * 5;

        int panelWidth = graphWidth + paddingX * 2 + valueLabelWidth;
        int panelHeight = graphHeight + paddingY * 2 + labelAreaHeight;

        // Position at bottom center
        int panelX = (imgWidth - panelWidth) / 2;
        int panelY = imgHeight - panelHeight - margin;

        if (imgHeight != cachedImageHeight || cachedFont == null) {
            cachedImageHeight = imgHeight;
            cachedFont = OverlayFonts.getDataFont(labelFontSize);
        }

        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // Draw background
        g2d.setColor(OverlayFonts.BACKGROUND);
        g2d.fillRoundRect(panelX, panelY, panelWidth, panelHeight, borderRadius, borderRadius);

        // Draw border
        g2d.setColor(OverlayFonts.BORDER);
        g2d.setStroke(new BasicStroke(borderWidth));
        g2d.drawRoundRect(panelX, panelY, panelWidth, panelHeight, borderRadius, borderRadius);

        // Graph area coordinates
        int graphX = panelX + paddingX;
        int graphY = panelY + paddingY;

        // Draw axis lines
        g2d.setColor(AXIS_COLOR);
        g2d.setStroke(new BasicStroke(1));
        g2d.drawLine(graphX, graphY + graphHeight, graphX + graphWidth, graphY + graphHeight);
        g2d.drawLine(graphX, graphY, graphX, graphY + graphHeight);

        // Get sampled data points and stable Y maximum
        List<DataPoint> points = sharedHistory.getSampledPoints();
        int stableMaxY = sharedHistory.getStableMaxY();
        int totalPoints = sharedHistory.size();

        if (points.size() >= 2 && stableMaxY > 0) {
            // Graph fills from left to right proportionally to accumulated data.
            // Below MAX_DISPLAY_POINTS: only use a fraction of the width.
            // At/above MAX_DISPLAY_POINTS: full width.
            float fillRatio = Math.min(1.0f, (float) totalPoints / MAX_DISPLAY_POINTS);
            int usedWidth = Math.max(2, (int) (graphWidth * fillRatio));

            long firstTick = points.get(0).tick();
            long lastTick = points.get(points.size() - 1).tick();
            long tickRange = lastTick - firstTick;
            if (tickRange <= 0) tickRange = 1;

            float lineWidth = Math.max(1.5f, fontSize / 6.0f);

            // Compute pixel coordinates for both series
            float[] orgXs = new float[points.size()];
            float[] orgYs = new float[points.size()];
            float[] genXs = new float[points.size()];
            float[] genYs = new float[points.size()];

            for (int i = 0; i < points.size(); i++) {
                DataPoint p = points.get(i);
                float xFrac = (float) (p.tick() - firstTick) / tickRange;
                float px = graphX + xFrac * usedWidth;

                orgXs[i] = px;
                orgYs[i] = graphY + graphHeight - (float) p.organisms() / stableMaxY * graphHeight;
                orgYs[i] = Math.max(graphY, Math.min(graphY + graphHeight, orgYs[i]));

                genXs[i] = px;
                genYs[i] = graphY + graphHeight - (float) p.genomes() / stableMaxY * graphHeight;
                genYs[i] = Math.max(graphY, Math.min(graphY + graphHeight, genYs[i]));
            }

            // Draw organism spline (thicker, behind)
            drawSpline(g2d, orgXs, orgYs, LINE_ORGANISMS, lineWidth * 1.3f);

            // Draw genome spline (thinner, in front)
            drawSpline(g2d, genXs, genYs, LINE_GENOMES, lineWidth);

            // Draw current value labels at right end of each line
            g2d.setFont(cachedFont);
            FontMetrics fm = g2d.getFontMetrics();
            int labelX = graphX + usedWidth + fontSize / 3;

            DataPoint lastPoint = points.get(points.size() - 1);
            int orgEndY = (int) orgYs[points.size() - 1];
            int genEndY = (int) genYs[points.size() - 1];

            // Prevent label overlap: ensure minimum vertical distance
            int minLabelGap = fm.getHeight() + 2;
            if (Math.abs(orgEndY - genEndY) < minLabelGap) {
                int mid = (orgEndY + genEndY) / 2;
                orgEndY = mid - minLabelGap / 2;
                genEndY = mid + minLabelGap / 2;
            }

            // Clamp labels to graph area
            orgEndY = Math.max(graphY + fm.getAscent(),
                    Math.min(graphY + graphHeight, orgEndY));
            genEndY = Math.max(graphY + fm.getAscent(),
                    Math.min(graphY + graphHeight, genEndY));

            g2d.setColor(LINE_ORGANISMS);
            g2d.drawString(formatCompact(lastPoint.organisms()), labelX, orgEndY + fm.getAscent() / 3);

            g2d.setColor(LINE_GENOMES);
            g2d.drawString(formatCompact(lastPoint.genomes()), labelX, genEndY + fm.getAscent() / 3);
        }

        // Draw legend below graph
        g2d.setFont(cachedFont);
        FontMetrics fm = g2d.getFontMetrics();
        int legendY = graphY + graphHeight + labelAreaHeight - 2;

        int legendLineLength = fontSize;
        float legendLineWidth = Math.max(1.5f, fontSize / 6.0f);

        // Organisms legend
        g2d.setColor(LINE_ORGANISMS);
        g2d.setStroke(new BasicStroke(legendLineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int legendX = graphX;
        g2d.drawLine(legendX, legendY - fm.getAscent() / 2,
                legendX + legendLineLength, legendY - fm.getAscent() / 2);
        legendX += legendLineLength + 4;
        g2d.drawString("Organisms", legendX, legendY);
        legendX += fm.stringWidth("Organisms") + fontSize;

        // Genomes legend
        g2d.setColor(LINE_GENOMES);
        g2d.drawLine(legendX, legendY - fm.getAscent() / 2,
                legendX + legendLineLength, legendY - fm.getAscent() / 2);
        legendX += legendLineLength + 4;
        g2d.drawString("Genomes", legendX, legendY);

        g2d.dispose();
    }

    /**
     * Draws a smooth Catmull-Rom spline through the given points.
     *
     * @param g2d       Graphics context.
     * @param xs        X coordinates.
     * @param ys        Y coordinates.
     * @param color     Line color.
     * @param lineWidth Line width in pixels.
     */
    private void drawSpline(Graphics2D g2d, float[] xs, float[] ys,
                            Color color, float lineWidth) {
        int n = xs.length;
        if (n < 2) return;

        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        Path2D.Float path = new Path2D.Float();
        path.moveTo(xs[0], ys[0]);

        if (n == 2) {
            path.lineTo(xs[1], ys[1]);
        } else {
            for (int i = 0; i < n - 1; i++) {
                // Catmull-Rom: use P(i-1), P(i), P(i+1), P(i+2)
                // Clamp to first/last for boundary segments
                float x0 = xs[Math.max(0, i - 1)];
                float y0 = ys[Math.max(0, i - 1)];
                float x1 = xs[i];
                float y1 = ys[i];
                float x2 = xs[i + 1];
                float y2 = ys[i + 1];
                float x3 = xs[Math.min(n - 1, i + 2)];
                float y3 = ys[Math.min(n - 1, i + 2)];

                // Convert Catmull-Rom to cubic Bezier control points
                float cp1x = x1 + (x2 - x0) / (6.0f / CR_TENSION);
                float cp1y = y1 + (y2 - y0) / (6.0f / CR_TENSION);
                float cp2x = x2 - (x3 - x1) / (6.0f / CR_TENSION);
                float cp2y = y2 - (y3 - y1) / (6.0f / CR_TENSION);

                path.curveTo(cp1x, cp1y, cp2x, cp2y, x2, y2);
            }
        }

        g2d.draw(path);
    }

    /**
     * Formats a number compactly for value labels.
     *
     * @param value The value to format.
     * @return Compact string (e.g., "1.2K", "3.4M", "42").
     */
    private String formatCompact(int value) {
        if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000.0);
        } else if (value >= 1_000) {
            return String.format("%.1fK", value / 1_000.0);
        }
        return String.valueOf(value);
    }

    /**
     * Returns the shared history for testing.
     *
     * @return The shared graph history.
     */
    GraphHistory getHistory() {
        return sharedHistory;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Thread-safe history accumulator
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Thread-safe accumulator for time-series data points.
     * <p>
     * Stores all data points and provides evenly-sampled views for rendering.
     * When the total number of points exceeds {@link #MAX_DISPLAY_POINTS},
     * returns an evenly-spaced subset covering the full time range.
     * <p>
     * Maintains a monotonically increasing Y-axis maximum rounded to "nice"
     * numbers to prevent graph jitter during rescaling.
     */
    static class GraphHistory {

        private final List<DataPoint> points = new ArrayList<>();
        private int stableMaxY;

        /**
         * Records a data point and updates the stable Y maximum.
         *
         * @param tick      Tick number.
         * @param organisms Number of alive organisms.
         * @param genomes   Number of unique genomes.
         */
        synchronized void addPoint(long tick, int organisms, int genomes) {
            points.add(new DataPoint(tick, organisms, genomes));

            int currentMax = Math.max(organisms, genomes);
            if (currentMax > stableMaxY) {
                // Round up to a nice number with 10% headroom
                stableMaxY = roundUpToNice((int) (currentMax * 1.1));
            }
        }

        /**
         * Returns data points sampled to at most {@link #MAX_DISPLAY_POINTS}.
         * <p>
         * If fewer points exist, returns all of them. Otherwise, returns
         * an evenly-spaced subset that always includes the first and last point.
         *
         * @return Sampled list of data points (new list, safe to iterate).
         */
        synchronized List<DataPoint> getSampledPoints() {
            int size = points.size();
            if (size <= MAX_DISPLAY_POINTS) {
                return new ArrayList<>(points);
            }

            List<DataPoint> sampled = new ArrayList<>(MAX_DISPLAY_POINTS);
            for (int i = 0; i < MAX_DISPLAY_POINTS; i++) {
                int idx = (int) ((long) i * (size - 1) / (MAX_DISPLAY_POINTS - 1));
                sampled.add(points.get(idx));
            }
            return sampled;
        }

        /**
         * Returns the stable Y-axis maximum (monotonically increasing, nice number).
         *
         * @return Stable Y maximum for rendering.
         */
        synchronized int getStableMaxY() {
            return stableMaxY;
        }

        /**
         * Returns the total number of recorded data points.
         *
         * @return Number of data points.
         */
        synchronized int size() {
            return points.size();
        }

        /**
         * Rounds a value up to a "nice" number for axis labeling.
         * Produces values like 10, 20, 50, 100, 200, 500, 1000, etc.
         *
         * @param value The value to round up.
         * @return The next nice number >= value.
         */
        static int roundUpToNice(int value) {
            if (value <= 10) return 10;
            int magnitude = (int) Math.pow(10, (int) Math.log10(value));
            int normalized = (value + magnitude - 1) / magnitude;
            if (normalized <= 1) return magnitude;
            if (normalized <= 2) return 2 * magnitude;
            if (normalized <= 5) return 5 * magnitude;
            return 10 * magnitude;
        }
    }

    /**
     * A single time-series data point.
     *
     * @param tick      Tick number.
     * @param organisms Number of alive organisms at this tick.
     * @param genomes   Number of unique genomes at this tick.
     */
    record DataPoint(long tick, int organisms, int genomes) {
    }
}
