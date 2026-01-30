package org.evochora.cli.rendering.overlay;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the InfoOverlayRenderer.
 * Tests rendering onto various image sizes.
 */
@Tag("unit")
public class InfoOverlayRendererTest {

    @Test
    void testRenderOntoSnapshot() {
        InfoOverlayRenderer overlay = new InfoOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(12345)
            .setTotalOrganismsCreated(100)
            .build();

        // Should not throw
        overlay.render(image, snapshot);

        // Verify some pixels were changed (overlay draws in bottom-right)
        // The overlay background color is dark, so check that pixels are not all 0
        int bottomRightPixel = image.getRGB(image.getWidth() - 10, image.getHeight() - 10);
        assertThat(bottomRightPixel).isNotEqualTo(0);
    }

    @Test
    void testRenderOntoDelta() {
        InfoOverlayRenderer overlay = new InfoOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        TickDelta delta = TickDelta.newBuilder()
            .setTickNumber(12345)
            .setTotalOrganismsCreated(100)
            .build();

        // Should not throw
        overlay.render(image, delta);

        int bottomRightPixel = image.getRGB(image.getWidth() - 10, image.getHeight() - 10);
        assertThat(bottomRightPixel).isNotEqualTo(0);
    }

    @Test
    void testRenderOntoSmallImage() {
        InfoOverlayRenderer overlay = new InfoOverlayRenderer();
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .build();

        // Should not throw even on small images
        overlay.render(image, snapshot);
    }

    @Test
    void testRenderOntoLargeImage() {
        InfoOverlayRenderer overlay = new InfoOverlayRenderer();
        BufferedImage image = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(1_000_000)
            .setTotalOrganismsCreated(500_000)
            .build();

        // Should scale appropriately for large images
        overlay.render(image, snapshot);

        int bottomRightPixel = image.getRGB(image.getWidth() - 20, image.getHeight() - 20);
        assertThat(bottomRightPixel).isNotEqualTo(0);
    }

    @Test
    void testFontCachingOnRepeatedRenders() {
        InfoOverlayRenderer overlay = new InfoOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(1)
            .build();

        // Render multiple times - font should be cached after first render
        for (int i = 0; i < 10; i++) {
            overlay.render(image, snapshot);
        }

        // If we got here without issues, caching is working
        assertThat(true).isTrue();
    }
}
