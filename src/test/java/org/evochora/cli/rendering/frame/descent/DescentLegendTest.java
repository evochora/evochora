package org.evochora.cli.rendering.frame.descent;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.stream.IntStream;

import org.evochora.cli.rendering.overlay.InfoOverlayRenderer;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.node.processes.http.api.visualizer.descent.Ancestry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DescentLegend}: which lines get a cell and what the cells say, and where the
 * panel lies in a frame beside the info overlay's panel.
 */
@Tag("unit")
class DescentLegendTest {

    private static final int OTHER = DescentLegend.OTHER;

    /** The colour bits of a pixel; Java2D may write an alpha byte into an image without alpha. */
    private static final int RGB = 0xFFFFFF;

    /** The colour of every slot: the palette in rank order, the tone of other lines last. */
    private static final int[] COLOURS = IntStream.concat(IntStream.of(DescentRenderer.PALETTE),
        IntStream.of(DescentRenderer.OTHER_TONE)).toArray();

    /** Birth ticks of the eight coloured ranks. */
    private static final long[] BIRTHS = {100, 200, 300, 400, 500, 600, 700, 800};

    private static DescentLegend legend() {
        return new DescentLegend(1280, 720, 1_000_000_000L, 5000);
    }

    /** Living organisms per coloured rank, and those of the lines beyond the palette last. */
    private static int[] living(final int... perSlot) {
        final int[] living = new int[OTHER + 1];
        System.arraycopy(perSlot, 0, living, 0, perSlot.length);
        return living;
    }

    @Test
    void theFirstFourColouredLinesWithLivingOrganismsGetCellsAndTheRestFallUnderMore() {
        final DescentLegend legend = legend();

        // Ranks 0 to 5 alive: 40, 30, 10, 10, 6 and 4 of 100
        legend.update(7, 50, BIRTHS, living(40, 30, 10, 10, 6, 4), 0, 100, COLOURS);

        assertThat(legend.cellCount()).isEqualTo(4);
        for (int cell = 0; cell < 4; cell++) {
            assertThat(legend.cellRank(cell)).isEqualTo(cell);
            assertThat(legend.cellColour(cell)).isEqualTo(DescentRenderer.PALETTE[cell]);
        }
        assertThat(legend.cellShare(0)).isEqualTo("40%");
        assertThat(legend.cellShare(3)).isEqualTo("10%");
        assertThat(legend.cellBorn(1)).isEqualTo("200");
        assertThat(legend.hasMore()).isTrue();
        assertThat(legend.moreSquareCount()).isEqualTo(2);
        assertThat(legend.moreSquareColour(0)).isEqualTo(DescentRenderer.PALETTE[4]);
        assertThat(legend.moreSquareColour(1)).isEqualTo(DescentRenderer.PALETTE[5]);
        assertThat(legend.moreLines()).isEqualTo(2);
        assertThat(legend.moreText()).isEqualTo("2 more (10%)");
        assertThat(legend.rootIsAll()).isFalse();
        assertThat(legend.rootBorn()).isEqualTo("50");
    }

    @Test
    void aLineWithoutLivingOrganismsGetsNoCellAndTheNextRankMovesUp() {
        final DescentLegend legend = legend();

        legend.update(7, 50, BIRTHS, living(0, 5, 5), 0, 10, COLOURS);

        assertThat(legend.cellCount()).isEqualTo(2);
        assertThat(legend.cellRank(0)).isEqualTo(1);
        assertThat(legend.cellColour(0)).isEqualTo(DescentRenderer.PALETTE[1]);
        assertThat(legend.cellBorn(0)).isEqualTo("200");
        assertThat(legend.cellRank(1)).isEqualTo(2);
        assertThat(legend.hasMore()).as("every line alive has a cell").isFalse();
        assertThat(legend.moreText()).isNull();
    }

    @Test
    void aShareAboveZeroThatRoundsBelowOneReadsBelowOne() {
        final DescentLegend legend = legend();

        // The root and the organisms outside it count in the denominator: 1 of 300 is 0.3%
        legend.update(7, 50, BIRTHS, living(250, 1, 1, 1, 1), 0, 300, COLOURS);

        assertThat(legend.cellShare(0)).isEqualTo("83%");
        assertThat(legend.cellShare(1)).isEqualTo("<1%");
        assertThat(legend.moreText()).isEqualTo("1 more (<1%)");
    }

    @Test
    void linesBeyondThePaletteFallUnderMoreWithTheOtherTone() {
        final DescentLegend legend = legend();

        // Ranks 0 and 2 alive, and 12 organisms in 3 lines beyond the palette
        legend.update(7, 50, BIRTHS, living(40, 0, 48, 0, 0, 0, 0, 0, 12), 3, 100, COLOURS);

        assertThat(legend.cellCount()).isEqualTo(2);
        assertThat(legend.moreSquareCount()).as("one square for all lines beyond the palette").isEqualTo(1);
        assertThat(legend.moreSquareColour(0)).isEqualTo(DescentRenderer.OTHER_TONE);
        assertThat(legend.moreLines()).isEqualTo(3);
        assertThat(legend.moreText()).isEqualTo("3 more (12%)");
    }

    @Test
    void colouredLinesWithoutACellAndLinesBeyondThePaletteAreCountedTogether() {
        final DescentLegend legend = legend();

        legend.update(7, 50, BIRTHS, living(10, 10, 10, 10, 0, 10, 0, 10, 20), 4, 100, COLOURS);

        assertThat(legend.moreSquareCount()).isEqualTo(3);
        assertThat(legend.moreSquareColour(0)).isEqualTo(DescentRenderer.PALETTE[5]);
        assertThat(legend.moreSquareColour(1)).isEqualTo(DescentRenderer.PALETTE[7]);
        assertThat(legend.moreSquareColour(2)).isEqualTo(DescentRenderer.OTHER_TONE);
        assertThat(legend.moreText()).isEqualTo("6 more (40%)");
    }

    @Test
    void theVirtualRootReadsAll() {
        final DescentLegend legend = legend();

        legend.update(Ancestry.NO_PARENT, Long.MIN_VALUE, BIRTHS, living(3, 1), 0, 4, COLOURS);

        assertThat(legend.rootIsAll()).isTrue();
        assertThat(legend.rootBorn()).isNull();
        assertThat(legend.cellShare(0)).isEqualTo("75%");
    }

    @Test
    void ticksBelowAMillionAreWrittenInFullAndFromAMillionOnInMillions() {
        assertThat(DescentLegend.formatTick(0)).isEqualTo("0");
        assertThat(DescentLegend.formatTick(999)).isEqualTo("999");
        assertThat(DescentLegend.formatTick(123_456)).isEqualTo("123,456");
        assertThat(DescentLegend.formatTick(999_999)).isEqualTo("999,999");
        assertThat(DescentLegend.formatTick(1_000_000)).isEqualTo("1.00M");
        assertThat(DescentLegend.formatTick(192_750_000)).isEqualTo("192.75M");
        assertThat(DescentLegend.formatTick(12_345_678_901L)).isEqualTo("12345.68M");
    }

    @Test
    void theCellTextsFollowTheBirthTicksOfTheFrame() {
        final DescentLegend legend = legend();
        legend.update(7, 1_500_000, BIRTHS, living(1), 0, 1, COLOURS);
        assertThat(legend.rootBorn()).isEqualTo("1.50M");

        legend.update(9, 2_000_000, new long[]{3_000_000}, living(1), 0, 1, COLOURS);

        assertThat(legend.rootBorn()).isEqualTo("2.00M");
        assertThat(legend.cellBorn(0)).isEqualTo("3.00M");
    }

    @Test
    void thePanelIsTwoColumnsWideOnlyWhenTheSecondColumnHoldsACell() {
        final DescentLegend legend = legend();

        legend.update(7, 50, BIRTHS, living(5, 5), 0, 10, COLOURS);
        assertThat(legend.hasSecondColumn()).as("root and two line cells").isFalse();
        final Rectangle narrow = legend.panel();

        legend.update(7, 50, BIRTHS, living(5, 5, 5), 0, 15, COLOURS);
        assertThat(legend.hasSecondColumn()).as("a third line cell").isTrue();
        final Rectangle wide = legend.panel();

        legend.update(7, 50, BIRTHS, living(5, 0, 5, 0, 0, 0, 0, 0, 5), 2, 15, COLOURS);
        assertThat(legend.hasSecondColumn()).as("two line cells and the more cell").isTrue();
        assertThat(legend.panel()).isEqualTo(wide);

        legend.update(7, 50, BIRTHS, living(5), 0, 5, COLOURS);
        assertThat(legend.hasSecondColumn()).isFalse();
        assertThat(legend.panel()).isEqualTo(narrow);

        assertThat(narrow.width).isLessThan(wide.width);
        assertThat(narrow.x).isEqualTo(wide.x);
        assertThat(narrow.y).isEqualTo(wide.y);
        assertThat(narrow.height).isEqualTo(wide.height);
    }

    @Test
    void aOneColumnPanelIsDrawnWithinItsNarrowOutline() {
        final DescentLegend legend = new DescentLegend(1280, 720, 1_000_000_000L, 5000);
        legend.update(7, 192_750_000, BIRTHS, living(60, 40), 0, 100, COLOURS);
        final BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);

        legend.draw(image);

        final Rectangle outline = legend.panel();
        outline.grow(legend.borderWidth(), legend.borderWidth());
        final Rectangle changed = changedPixels(image);
        assertThat(outline.contains(changed)).as("%s changed, outline %s", changed, outline).isTrue();
        assertThat(changed.width).as("the panel is drawn to its right edge")
            .isGreaterThanOrEqualTo(legend.panel().width);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Drawing
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void theLegendLiesInTheBottomLeftCornerLevelWithTheInfoPanelOnAFullHdFrame() {
        assertLevelWithTheInfoPanel(2304, 1296);
    }

    @Test
    void theLegendLiesInTheBottomLeftCornerLevelWithTheInfoPanelOnASmallFrame() {
        assertLevelWithTheInfoPanel(640, 360);
    }

    private static void assertLevelWithTheInfoPanel(final int width, final int height) {
        final DescentLegend legend = new DescentLegend(width, height, 250_000_000L, 12_000);
        legend.update(7, 192_750_000, BIRTHS, living(40, 30, 10, 10, 6, 0, 0, 0, 4), 9, 100, COLOURS);
        final BufferedImage withLegend = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final BufferedImage withInfo = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        legend.draw(withLegend);
        new InfoOverlayRenderer().render(withInfo, TickData.newBuilder()
            .setTickNumber(250_000_000L).setTotalOrganismsCreated(1_234_567).build());

        final Rectangle changed = changedPixels(withLegend);
        final Rectangle info = changedPixels(withInfo);
        final Rectangle panel = legend.panel();
        final int margin = Math.max(5, (int) (width * 0.015));
        assertThat(panel.x).as("left margin").isEqualTo(margin);
        assertThat(panel.y + panel.height).as("bottom margin").isEqualTo(height - margin);
        final Rectangle outline = new Rectangle(panel);
        outline.grow(legend.borderWidth(), legend.borderWidth());
        assertThat(outline.contains(changed)).as("%s changed, panel %s", changed, panel).isTrue();
        assertThat(changed.y).as("top row").isEqualTo(info.y);
        assertThat(changed.y + changed.height).as("bottom row").isEqualTo(info.y + info.height);
        assertThat(changed.x + changed.width).as("clear of the info panel").isLessThan(info.x);
        assertThat(containsColour(withLegend, changed, DescentRenderer.PALETTE[0])).as("the first square").isTrue();
        assertThat(containsColour(withLegend, changed, DescentRenderer.OTHER_TONE)).as("the other lines").isTrue();
    }

    /** The bounds of every pixel that is not black. */
    private static Rectangle changedPixels(final BufferedImage image) {
        final int[] pixels = pixelsOf(image);
        final int width = image.getWidth();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int i = 0; i < pixels.length; i++) {
            if ((pixels[i] & RGB) != 0) {
                final int x = i % width;
                final int y = i / width;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        assertThat(maxX).as("something was drawn").isNotNegative();
        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static boolean containsColour(final BufferedImage image, final Rectangle area, final int rgb) {
        final int[] pixels = pixelsOf(image);
        for (int y = area.y; y < area.y + area.height; y++) {
            for (int x = area.x; x < area.x + area.width; x++) {
                if ((pixels[y * image.getWidth() + x] & RGB) == rgb) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int[] pixelsOf(final BufferedImage image) {
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }
}
