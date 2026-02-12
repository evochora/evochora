package org.evochora.cli.rendering.overlay;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Font;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for OverlayFonts utility.
 */
@Tag("unit")
public class OverlayFontsTest {

    @Test
    void testGetDataFont_returnsNonNull() {
        Font font = OverlayFonts.getDataFont(12);

        assertThat(font).isNotNull();
        assertThat(font.getSize()).isEqualTo(12);
    }

    @Test
    void testGetLogoFont_returnsNonNull() {
        Font font = OverlayFonts.getLogoFont(18);

        assertThat(font).isNotNull();
        assertThat(font.getSize()).isEqualTo(18);
    }

    @Test
    void testComputeFontSize_scalesWithWidth() {
        int small = OverlayFonts.computeFontSize(200);
        int large = OverlayFonts.computeFontSize(2000);

        assertThat(large).isGreaterThan(small);
    }

    @Test
    void testComputeFontSize_clampsToMinimum() {
        int size = OverlayFonts.computeFontSize(10);

        assertThat(size).isGreaterThanOrEqualTo(OverlayFonts.MIN_FONT_SIZE);
    }

    @Test
    void testComputeFontSize_clampsToMaximum() {
        int size = OverlayFonts.computeFontSize(10000);

        assertThat(size).isLessThanOrEqualTo(OverlayFonts.MAX_FONT_SIZE);
    }

    @Test
    void testComputeMargin_scalesWithWidth() {
        int small = OverlayFonts.computeMargin(200);
        int large = OverlayFonts.computeMargin(2000);

        assertThat(large).isGreaterThan(small);
    }
}
