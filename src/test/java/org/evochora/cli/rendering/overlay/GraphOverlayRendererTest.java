package org.evochora.cli.rendering.overlay;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for GraphOverlayRenderer.
 * Tests rendering, data accumulation, sampling, and state sharing.
 */
@Tag("unit")
public class GraphOverlayRendererTest {

    // ========================================================================
    // Rendering smoke tests
    // ========================================================================

    @Test
    void testRenderOntoSnapshot() {
        GraphOverlayRenderer overlay = new GraphOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(100)
            .addOrganisms(createOrganism(1, 42L, false))
            .addOrganisms(createOrganism(2, 43L, false))
            .build();

        overlay.render(image, snapshot);

        // Verify bottom-center pixels were changed (graph draws there)
        int bottomCenterPixel = image.getRGB(200, image.getHeight() - 20);
        assertThat(bottomCenterPixel).isNotEqualTo(0);
    }

    @Test
    void testRenderOntoDelta() {
        GraphOverlayRenderer overlay = new GraphOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        TickDelta delta = TickDelta.newBuilder()
            .setTickNumber(100)
            .addOrganisms(createOrganism(1, 42L, false))
            .build();

        overlay.render(image, delta);

        int bottomCenterPixel = image.getRGB(200, image.getHeight() - 20);
        assertThat(bottomCenterPixel).isNotEqualTo(0);
    }

    @Test
    void testRenderOntoSmallImage() {
        GraphOverlayRenderer overlay = new GraphOverlayRenderer();
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder().setTickNumber(0).build();

        // Should not throw even on small images
        overlay.render(image, snapshot);
    }

    @Test
    void testRenderOntoLargeImage() {
        GraphOverlayRenderer overlay = new GraphOverlayRenderer();
        BufferedImage image = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(100)
            .addOrganisms(createOrganism(1, 42L, false))
            .build();

        overlay.render(image, snapshot);

        int bottomCenterPixel = image.getRGB(960, 1060);
        assertThat(bottomCenterPixel).isNotEqualTo(0);
    }

    // ========================================================================
    // Data accumulation
    // ========================================================================

    @Test
    void testHistoryAccumulates() {
        GraphOverlayRenderer overlay = new GraphOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        for (int i = 0; i < 10; i++) {
            TickData snapshot = TickData.newBuilder()
                .setTickNumber(i)
                .addOrganisms(createOrganism(1, 42L, false))
                .build();
            overlay.render(image, snapshot);
        }

        assertThat(overlay.getHistory().size()).isEqualTo(10);
    }

    @Test
    void testHistoryRecordsCorrectCounts() {
        GraphOverlayRenderer overlay = new GraphOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(42)
            .addOrganisms(createOrganism(1, 100L, false))
            .addOrganisms(createOrganism(2, 100L, false))  // same genome
            .addOrganisms(createOrganism(3, 200L, false))  // different genome
            .addOrganisms(createOrganism(4, 300L, true))   // dead — excluded
            .build();

        overlay.render(image, snapshot);

        List<GraphOverlayRenderer.DataPoint> points = overlay.getHistory().getSampledPoints();
        assertThat(points).hasSize(1);
        assertThat(points.get(0).organisms()).isEqualTo(3);  // 3 alive
        assertThat(points.get(0).genomes()).isEqualTo(2);     // 2 unique genomes
        assertThat(points.get(0).tick()).isEqualTo(42);
    }

    @Test
    void testHistoryExcludesZeroGenomeHash() {
        GraphOverlayRenderer overlay = new GraphOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .addOrganisms(createOrganism(1, 0L, false))    // zero hash — excluded from genome count
            .addOrganisms(createOrganism(2, 100L, false))
            .build();

        overlay.render(image, snapshot);

        List<GraphOverlayRenderer.DataPoint> points = overlay.getHistory().getSampledPoints();
        assertThat(points.get(0).organisms()).isEqualTo(2);  // both alive
        assertThat(points.get(0).genomes()).isEqualTo(1);     // only non-zero hash counted
    }

    // ========================================================================
    // Sampling
    // ========================================================================

    @Test
    void testSamplingWhenBelowMax() {
        GraphOverlayRenderer.GraphHistory history = new GraphOverlayRenderer.GraphHistory();

        for (int i = 0; i < 100; i++) {
            history.addPoint(i, i * 10, i);
        }

        List<GraphOverlayRenderer.DataPoint> sampled = history.getSampledPoints();
        assertThat(sampled).hasSize(100);
    }

    @Test
    void testSamplingWhenAboveMax() {
        GraphOverlayRenderer.GraphHistory history = new GraphOverlayRenderer.GraphHistory();

        for (int i = 0; i < 2000; i++) {
            history.addPoint(i, i * 10, i);
        }

        List<GraphOverlayRenderer.DataPoint> sampled = history.getSampledPoints();
        assertThat(sampled).hasSize(GraphOverlayRenderer.MAX_DISPLAY_POINTS);

        // First and last points should be preserved
        assertThat(sampled.get(0).tick()).isEqualTo(0);
        assertThat(sampled.get(sampled.size() - 1).tick()).isEqualTo(1999);
    }

    @Test
    void testSamplingDistributionIsEven() {
        GraphOverlayRenderer.GraphHistory history = new GraphOverlayRenderer.GraphHistory();

        int total = 5000;
        for (int i = 0; i < total; i++) {
            history.addPoint(i, 0, 0);
        }

        List<GraphOverlayRenderer.DataPoint> sampled = history.getSampledPoints();

        // Check that points are roughly evenly spaced
        long firstTick = sampled.get(0).tick();
        long lastTick = sampled.get(sampled.size() - 1).tick();
        double expectedStep = (double) (lastTick - firstTick) / (sampled.size() - 1);

        for (int i = 1; i < sampled.size(); i++) {
            long gap = sampled.get(i).tick() - sampled.get(i - 1).tick();
            // Each gap should be within 2x of expected step (allowing for integer rounding)
            assertThat(gap).isLessThanOrEqualTo((long) (expectedStep * 2) + 1);
        }
    }

    // ========================================================================
    // State sharing via initFromOriginal
    // ========================================================================

    @Test
    void testInitFromOriginalSharesHistory() {
        GraphOverlayRenderer original = new GraphOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        // Record some data on original
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(1)
            .addOrganisms(createOrganism(1, 42L, false))
            .build();
        original.render(image, snapshot);

        // Create copy and init from original
        GraphOverlayRenderer copy = new GraphOverlayRenderer();
        copy.initFromOriginal(original);

        // Both should share the same history
        assertThat(copy.getHistory().size()).isEqualTo(1);

        // Adding to copy should be visible from original
        TickData snapshot2 = TickData.newBuilder()
            .setTickNumber(2)
            .addOrganisms(createOrganism(1, 42L, false))
            .build();
        copy.render(image, snapshot2);

        assertThat(original.getHistory().size()).isEqualTo(2);
        assertThat(copy.getHistory().size()).isEqualTo(2);
    }

    // ========================================================================
    // Stable Y-axis (monotonic, nice numbers)
    // ========================================================================

    @Test
    void testStableMaxY_monotonicallyIncreasing() {
        GraphOverlayRenderer.GraphHistory history = new GraphOverlayRenderer.GraphHistory();

        history.addPoint(0, 50, 5);
        int max1 = history.getStableMaxY();

        history.addPoint(1, 100, 10);
        int max2 = history.getStableMaxY();

        // Max should only go up (or stay the same)
        assertThat(max2).isGreaterThanOrEqualTo(max1);

        // Even if values drop, max should not decrease
        history.addPoint(2, 10, 2);
        int max3 = history.getStableMaxY();
        assertThat(max3).isGreaterThanOrEqualTo(max2);
    }

    @Test
    void testStableMaxY_roundsToNiceNumbers() {
        // roundUpToNice should produce values like 10, 20, 50, 100, 200, 500, 1000
        assertThat(GraphOverlayRenderer.GraphHistory.roundUpToNice(7)).isEqualTo(10);
        assertThat(GraphOverlayRenderer.GraphHistory.roundUpToNice(15)).isEqualTo(20);
        assertThat(GraphOverlayRenderer.GraphHistory.roundUpToNice(35)).isEqualTo(50);
        assertThat(GraphOverlayRenderer.GraphHistory.roundUpToNice(80)).isEqualTo(100);
        assertThat(GraphOverlayRenderer.GraphHistory.roundUpToNice(150)).isEqualTo(200);
        assertThat(GraphOverlayRenderer.GraphHistory.roundUpToNice(350)).isEqualTo(500);
        assertThat(GraphOverlayRenderer.GraphHistory.roundUpToNice(800)).isEqualTo(1000);
        assertThat(GraphOverlayRenderer.GraphHistory.roundUpToNice(1500)).isEqualTo(2000);
        assertThat(GraphOverlayRenderer.GraphHistory.roundUpToNice(3500)).isEqualTo(5000);
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    void testRenderWithNoOrganisms() {
        GraphOverlayRenderer overlay = new GraphOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder().setTickNumber(0).build();
        overlay.render(image, snapshot);

        List<GraphOverlayRenderer.DataPoint> points = overlay.getHistory().getSampledPoints();
        assertThat(points).hasSize(1);
        assertThat(points.get(0).organisms()).isEqualTo(0);
        assertThat(points.get(0).genomes()).isEqualTo(0);
    }

    @Test
    void testRenderWithSinglePoint_noLineDrawn() {
        // With only one data point, no line should be drawn (need >= 2)
        // But the overlay should still render without error
        GraphOverlayRenderer overlay = new GraphOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .addOrganisms(createOrganism(1, 42L, false))
            .build();
        overlay.render(image, snapshot);
        // No assertion needed — just verify no exception
    }

    @Test
    void testMultipleFrameRendering() {
        GraphOverlayRenderer overlay = new GraphOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        // Render many frames to build up history
        for (int i = 0; i < 100; i++) {
            TickData.Builder builder = TickData.newBuilder().setTickNumber(i);
            // Add organisms with growing population
            for (int j = 0; j <= i % 20; j++) {
                builder.addOrganisms(createOrganism(j, 100L + (j % 3), false));
            }
            overlay.render(image, builder.build());
        }

        assertThat(overlay.getHistory().size()).isEqualTo(100);

        // Final render should show the full graph
        int bottomCenterPixel = image.getRGB(200, image.getHeight() - 20);
        assertThat(bottomCenterPixel).isNotEqualTo(0);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private OrganismState createOrganism(int id, long genomeHash, boolean dead) {
        OrganismState.Builder builder = OrganismState.newBuilder()
            .setOrganismId(id)
            .setGenomeHash(genomeHash);
        if (dead) {
            builder.setIsDead(true);
        }
        return builder.build();
    }
}
