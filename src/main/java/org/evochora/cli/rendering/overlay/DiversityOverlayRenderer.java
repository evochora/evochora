package org.evochora.cli.rendering.overlay;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.evochora.cli.rendering.IOverlayRenderer;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;

/**
 * Diversity overlay renderer displaying ecological diversity metrics.
 * <p>
 * Computes and displays three scientific diversity measures:
 * <ul>
 *   <li><strong>Genomes:</strong> Number of unique genome hashes among living organisms (species richness)</li>
 *   <li><strong>Shannon:</strong> Shannon entropy H' = -&Sigma;(p<sub>i</sub> &middot; ln(p<sub>i</sub>)),
 *       measuring diversity considering both richness and abundance distribution</li>
 *   <li><strong>Evenness:</strong> Pielou's J = H' / ln(S), measuring how evenly organisms
 *       are distributed across genomes (0 = one genome dominates, 1 = perfectly even)</li>
 * </ul>
 * <p>
 * Positioned at bottom-left corner with glass morphism styling matching {@link InfoOverlayRenderer}.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Use one renderer per thread.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Shannon_index">Shannon diversity index</a>
 * @see <a href="https://en.wikipedia.org/wiki/Species_evenness">Pielou's evenness index</a>
 */
public class DiversityOverlayRenderer implements IOverlayRenderer {

    // Cached rendering resources
    private int cachedImageHeight;
    private Font cachedFont;

    // Reusable computation buffer
    private final Map<Long, int[]> genomeCounts = new HashMap<>();

    // Computed metrics (updated per frame)
    private int activeGenomes;
    private double shannonIndex;
    private double evenness;

    /**
     * Creates a new diversity overlay renderer.
     */
    public DiversityOverlayRenderer() {
        // No-arg constructor for reflection-based instantiation
    }

    @Override
    public void render(BufferedImage frame, TickData snapshot) {
        computeDiversity(snapshot.getOrganismsList());
        renderOverlay(frame);
    }

    @Override
    public void render(BufferedImage frame, TickDelta delta) {
        computeDiversity(delta.getOrganismsList());
        renderOverlay(frame);
    }

    /**
     * Computes diversity metrics from the organism list.
     * Pattern follows {@code GenomeAnalyticsPlugin}: single pass to build
     * genome counts, then Shannon index and Pielou's evenness.
     *
     * @param organisms List of organisms (alive and dead).
     */
    void computeDiversity(List<OrganismState> organisms) {
        genomeCounts.clear();
        int totalAlive = 0;

        for (OrganismState org : organisms) {
            if (org.getIsDead()) continue;
            long hash = org.getGenomeHash();
            if (hash == 0L) continue;
            genomeCounts.computeIfAbsent(hash, k -> new int[1])[0]++;
            totalAlive++;
        }

        activeGenomes = genomeCounts.size();
        shannonIndex = 0.0;
        evenness = 0.0;

        if (totalAlive > 0) {
            for (int[] count : genomeCounts.values()) {
                double p = (double) count[0] / totalAlive;
                shannonIndex -= p * Math.log(p);
            }
            if (activeGenomes > 1) {
                evenness = shannonIndex / Math.log(activeGenomes);
            } else {
                evenness = 1.0;
            }
        }
    }

    /**
     * Returns the most recently computed number of active genomes.
     *
     * @return Number of unique genome hashes among living organisms.
     */
    int getActiveGenomes() {
        return activeGenomes;
    }

    /**
     * Returns the most recently computed Shannon index.
     *
     * @return Shannon entropy H' (natural log).
     */
    double getShannonIndex() {
        return shannonIndex;
    }

    /**
     * Returns the most recently computed evenness.
     *
     * @return Pielou's J (0–1).
     */
    double getEvenness() {
        return evenness;
    }

    private void renderOverlay(BufferedImage image) {
        int imgWidth = image.getWidth();
        int imgHeight = image.getHeight();

        int fontSize = OverlayFonts.computeFontSize(imgWidth);
        int margin = OverlayFonts.computeMargin(imgWidth);
        int paddingX = fontSize;
        int paddingY = fontSize / 2;
        int lineSpacing = fontSize / 4;
        int borderRadius = fontSize / 2;
        int borderWidth = Math.max(1, fontSize / 10);

        if (imgHeight != cachedImageHeight || cachedFont == null) {
            cachedImageHeight = imgHeight;
            cachedFont = OverlayFonts.getDataFont(fontSize);
        }

        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        g2d.setFont(cachedFont);
        FontMetrics fm = g2d.getFontMetrics();

        // Prepare text content
        String genomesLabel = "Genomes";
        String shannonLabel = "Shannon";
        String evennessLabel = "Evenness";

        String genomesValue = String.valueOf(activeGenomes);
        String shannonValue = String.format("%.2f", shannonIndex);
        String evennessValue = String.format("%.2f", evenness);

        // Calculate panel dimensions
        int labelWidth = Math.max(fm.stringWidth(genomesLabel),
                         Math.max(fm.stringWidth(shannonLabel), fm.stringWidth(evennessLabel)));
        int valueWidth = Math.max(fm.stringWidth(genomesValue),
                         Math.max(fm.stringWidth(shannonValue), fm.stringWidth(evennessValue)));
        int gap = fm.stringWidth("    ");

        int panelWidth = paddingX * 2 + labelWidth + gap + valueWidth;
        int lineHeight = fm.getHeight();
        int panelHeight = paddingY * 2 + lineHeight * 3 + lineSpacing * 2;

        // Position at bottom-left
        int panelX = margin;
        int panelY = imgHeight - panelHeight - margin;

        // Draw background
        g2d.setColor(OverlayFonts.BACKGROUND);
        g2d.fillRoundRect(panelX, panelY, panelWidth, panelHeight, borderRadius, borderRadius);

        // Draw border
        g2d.setColor(OverlayFonts.BORDER);
        g2d.setStroke(new BasicStroke(borderWidth));
        g2d.drawRoundRect(panelX, panelY, panelWidth, panelHeight, borderRadius, borderRadius);

        // Draw text
        int textX = panelX + paddingX;
        int valueX = panelX + panelWidth - paddingX - valueWidth;
        int textY = panelY + paddingY + fm.getAscent();

        // Line 1: Genomes
        g2d.setColor(OverlayFonts.TEXT_SECONDARY);
        g2d.drawString(genomesLabel, textX, textY);
        g2d.setColor(OverlayFonts.TEXT_PRIMARY);
        g2d.drawString(genomesValue, valueX + (valueWidth - fm.stringWidth(genomesValue)), textY);

        // Line 2: Shannon
        textY += lineHeight + lineSpacing;
        g2d.setColor(OverlayFonts.TEXT_SECONDARY);
        g2d.drawString(shannonLabel, textX, textY);
        g2d.setColor(OverlayFonts.TEXT_PRIMARY);
        g2d.drawString(shannonValue, valueX + (valueWidth - fm.stringWidth(shannonValue)), textY);

        // Line 3: Evenness
        textY += lineHeight + lineSpacing;
        g2d.setColor(OverlayFonts.TEXT_SECONDARY);
        g2d.drawString(evennessLabel, textX, textY);
        g2d.setColor(OverlayFonts.TEXT_PRIMARY);
        g2d.drawString(evennessValue, valueX + (valueWidth - fm.stringWidth(evennessValue)), textY);

        g2d.dispose();
    }
}
