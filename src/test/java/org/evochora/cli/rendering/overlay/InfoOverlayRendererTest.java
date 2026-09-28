package org.evochora.cli.rendering.overlay;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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

        // The overlay is larger than the frame it is drawn on.
        assertThatCode(() -> overlay.render(image, snapshot)).doesNotThrowAnyException();
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
    void warmFontCacheRendersIdenticallyToColdCache() {
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(1)
            .build();

        InfoOverlayRenderer cold = new InfoOverlayRenderer();
        BufferedImage fromColdCache = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        cold.render(fromColdCache, snapshot);

        InfoOverlayRenderer warm = new InfoOverlayRenderer();
        BufferedImage scratch = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 10; i++) {
            warm.render(scratch, snapshot);
        }
        BufferedImage fromWarmCache = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        warm.render(fromWarmCache, snapshot);

        assertThat(pixelsOf(fromWarmCache))
            .as("A cached font renders the same frame as a font built for this frame")
            .isEqualTo(pixelsOf(fromColdCache));
    }

    @Test
    void numbersTakeTheSignsOfTheUsLocaleOnEveryMachine() {
        final Locale before = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            InfoOverlayRenderer overlay = new InfoOverlayRenderer();

            assertThat(overlay.formatNumber(123_456)).isEqualTo("123,456");
            assertThat(overlay.formatNumber(1_234_567)).isEqualTo("1.23M");
            assertThat(overlay.formatNumber(12_345_678)).isEqualTo("12.3M");
            assertThat(overlay.formatNumber(192_750_000)).isEqualTo("193M");
        } finally {
            Locale.setDefault(before);
        }
    }

    private static int[] pixelsOf(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
