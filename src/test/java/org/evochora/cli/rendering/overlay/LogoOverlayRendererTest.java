package org.evochora.cli.rendering.overlay;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the LogoOverlayRenderer.
 */
@Tag("unit")
public class LogoOverlayRendererTest {

    @Test
    void testRenderOntoSnapshot() {
        LogoOverlayRenderer overlay = new LogoOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder().setTickNumber(0).build();

        overlay.render(image, snapshot);

        // Verify top-right pixels were changed (logo draws there)
        int topRightPixel = image.getRGB(image.getWidth() - 10, 10);
        assertThat(topRightPixel).isNotEqualTo(0);
    }

    @Test
    void testRenderOntoDelta() {
        LogoOverlayRenderer overlay = new LogoOverlayRenderer();
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);

        TickDelta delta = TickDelta.newBuilder().setTickNumber(0).build();

        overlay.render(image, delta);

        int topRightPixel = image.getRGB(image.getWidth() - 10, 10);
        assertThat(topRightPixel).isNotEqualTo(0);
    }

    @Test
    void testRenderOntoSmallImage() {
        LogoOverlayRenderer overlay = new LogoOverlayRenderer();
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder().setTickNumber(0).build();

        // Should not throw even on small images
        overlay.render(image, snapshot);
    }

    @Test
    void testRenderOntoLargeImage() {
        LogoOverlayRenderer overlay = new LogoOverlayRenderer();
        BufferedImage image = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);

        TickData snapshot = TickData.newBuilder().setTickNumber(0).build();

        overlay.render(image, snapshot);

        int topRightPixel = image.getRGB(image.getWidth() - 20, 20);
        assertThat(topRightPixel).isNotEqualTo(0);
    }
}
