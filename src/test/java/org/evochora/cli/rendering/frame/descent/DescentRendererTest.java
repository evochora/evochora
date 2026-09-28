package org.evochora.cli.rendering.frame.descent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Rectangle;
import java.util.List;

import org.evochora.cli.commands.RenderVideoCommand;
import org.evochora.cli.rendering.frame.shared.EnvironmentBackgroundLayer;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import picocli.CommandLine;

/**
 * Tests for {@link DescentRenderer}: the root of every frame, the ranking of the lines, the
 * unfolding after a root jump, a held root, and frames rendered from a synthetic run held in
 * memory.
 * <p>
 * The run used throughout, in a world of 100 × 100 cells:
 * <pre>
 *   1 ── 3 ── 6 ── 9
 *   │    └── 7 ── 10
 *   │         └── 11
 *   ├── 4 ── 8
 *   └── 5
 *   2
 * </pre>
 * Founder 2 dies at tick 2, which makes 1 the root; 4, 5 and 8 die out at tick 4, which makes 3
 * the root. Ticks 5 to 9 repeat the living of tick 4. Organisms 4, 6 and 10 change their parent's
 * genome, every other organism with a parent keeps it; genomes are given at an organism's first
 * appearance, where the pre-pass reads them.
 */
@Tag("unit")
class DescentRendererTest {

    private static final String[] TICKS = {
        "1:0@10,10#100 2:0@90,90#200",
        "1:0@10,10 2:0@90,90 3:1@20,20#100^100 4:1@80,20#400^100 5:1@20,80#100^100",
        "1:0@10,10 2:0@90,90+ 3:1@20,20 4:1@80,20 5:1@20,80",
        "1:0@10,10+ 3:1@20,20 4:1@80,20 5:1@20,80 6:3@30,30#600^100 7:3@70,70#100^100 8:4@80,40#400^400",
        "3:1@20,20+ 4:1@80,20+ 5:1@20,80+ 6:3@30,30 7:3@70,70 8:4@80,40+ 9:6@30,50#600^600 "
            + "10:7@70,50#1000^100 11:7@50,70#100^100",
        "6:3@30,30 7:3@70,70 9:6@30,50 10:7@70,50 11:7@50,70",
        "6:3@30,30 7:3@70,70 9:6@30,50 10:7@70,50 11:7@50,70",
        "6:3@30,30 7:3@70,70 9:6@30,50 10:7@70,50 11:7@50,70",
        "6:3@30,30 7:3@70,70 9:6@30,50 10:7@70,50 11:7@50,70",
        "6:3@30,30 7:3@70,70 9:6@30,50 10:7@70,50 11:7@50,70"
    };

    private static final int GREEN = DescentRenderer.PALETTE[0];
    private static final int MAGENTA = DescentRenderer.PALETTE[1];
    private static final int CYAN = DescentRenderer.PALETTE[2];
    private static final int ORANGE = DescentRenderer.PALETTE[3];
    private static final int BLUE = DescentRenderer.PALETTE[4];
    private static final int YELLOW = DescentRenderer.PALETTE[5];

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, false);

    /** A world large enough for a legend panel at scale 0.64: frames of 640 × 640 pixels. */
    private static final EnvironmentProperties WIDE_ENV = new EnvironmentProperties(new int[]{1000, 1000}, false);

    /** A renderer with the given options, prepared on the whole run. */
    private static DescentRenderer prepared(final String... args) throws Exception {
        return prepared(ENV, args);
    }

    /** A renderer in a world with the given options, prepared on the whole run. */
    private static DescentRenderer prepared(final EnvironmentProperties env, final String... args) throws Exception {
        final DescentRenderer renderer = new DescentRenderer();
        new CommandLine(renderer).parseArgs(args);
        renderer.init(env);
        final DescentRunFixture run = new DescentRunFixture()
            .batch(0, TICKS[0], TICKS[1], TICKS[2], TICKS[3], TICKS[4])
            .batch(5, TICKS[5], TICKS[6], TICKS[7], TICKS[8], TICKS[9]);
        renderer.prepare(run.storage(), run.paths, "run", 0, Long.MAX_VALUE, 1);
        return renderer;
    }

    /** The pixel an organism at world (x, y) is centred on, at scale 0.5. */
    private static int pixelAt(final int[] pixels, final int x, final int y) {
        return pixels[(y / 2) * 50 + x / 2];
    }

    private static int channel(final int rgb, final int shift) {
        return (rgb >> shift) & 0xFF;
    }

    /** Euclidean distance of two colours in RGB. */
    private static double distance(final int a, final int b) {
        double sum = 0;
        for (final int shift : new int[]{16, 8, 0}) {
            final int d = channel(a, shift) - channel(b, shift);
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    @Test
    void isRegisteredAsTheVideoSubcommandDescent() {
        assertThat(new CommandLine(new DescentRenderer()).getCommandName()).isEqualTo("descent");
        assertThat(new CommandLine(new RenderVideoCommand()).getSubcommands()).containsKey("descent");
    }

    @Test
    void aTickOfAChunkOutsideTheRangeIsDrawnAsBackgroundAlone() throws Exception {
        final DescentRenderer renderer = new DescentRenderer();
        new CommandLine(renderer).parseArgs("--scale", "0.5");
        renderer.init(ENV);
        final DescentRunFixture run = new DescentRunFixture()
            .batch(0, TICKS[0], TICKS[1], TICKS[2], TICKS[3], TICKS[4]);
        renderer.prepare(run.storage(), run.paths, "run", 2, 3, 1);

        // The engine renders the whole chunk 0..4 and writes the frames of ticks 2 and 3
        final int[] before = renderer.renderSnapshot(DescentRunFixture.snapshot(0, TICKS[0])).clone();
        renderer.renderDelta(DescentRunFixture.delta(1, TICKS[1]));
        final int[] within = renderer.renderDelta(DescentRunFixture.delta(2, TICKS[2])).clone();
        renderer.renderDelta(DescentRunFixture.delta(3, TICKS[3]));
        final int[] after = renderer.renderDelta(DescentRunFixture.delta(4, TICKS[4])).clone();

        assertThat(pixelAt(before, 10, 10)).as("organism 1 at tick 0").isEqualTo(EnvironmentBackgroundLayer.COLOR_EMPTY);
        assertThat(pixelAt(within, 20, 20)).as("organism 3 at tick 2").isNotEqualTo(EnvironmentBackgroundLayer.COLOR_EMPTY);
        assertThat(pixelAt(after, 20, 20)).as("organism 3 at tick 4").isEqualTo(EnvironmentBackgroundLayer.COLOR_EMPTY);
    }

    @Test
    void aSampleTickIsShownByTheRecordedTickBeforeTheRange() throws Exception {
        final DescentRenderer renderer = new DescentRenderer();
        new CommandLine(renderer).parseArgs("--scale", "0.5");
        renderer.init(ENV);
        // Recorded ticks 0, 10 and 20; the range begins at the sample tick 15, shown by tick 10
        final DescentRunFixture run = new DescentRunFixture();
        run.batches.add(List.of(TickDataChunk.newBuilder()
            .setFirstTick(0).setLastTick(20).setTickCount(3)
            .setSnapshot(DescentRunFixture.snapshot(0, TICKS[0]))
            .addDeltas(DescentRunFixture.delta(10, TICKS[1]))
            .addDeltas(DescentRunFixture.delta(20, TICKS[2]))
            .build()));
        run.paths.add(StoragePath.of("run/raw/batch_0_20.pb"));
        renderer.prepare(run.storage(), run.paths, "run", 15, 20, 5);

        renderer.applySnapshotState(DescentRunFixture.snapshot(0, TICKS[0]));
        renderer.applyDeltaState(DescentRunFixture.delta(10, TICKS[1]));
        final int[] frame = renderer.renderCurrentState();

        assertThat(pixelAt(frame, 20, 20)).as("organism 3 at tick 10").isNotEqualTo(EnvironmentBackgroundLayer.COLOR_EMPTY);
    }

    @Test
    void theRootOfEveryFrameFollowsTheLiving() throws Exception {
        final DescentRenderer renderer = prepared();

        final int[] roots = new int[TICKS.length];
        for (int tick = 0; tick < TICKS.length; tick++) {
            roots[tick] = renderer.rootOf(tick);
        }
        assertThat(roots).containsExactly(0, 0, 1, 1, 3, 3, 3, 3, 3, 3);
    }

    @Test
    void theLinesTakeThePaletteByTheirDescendantsOverTheRun() throws Exception {
        final DescentRenderer renderer = prepared("--root", "1");

        // Line 3 has six members, line 4 two, line 5 one
        assertThat(renderer.colourOf(3, 6)).isEqualTo(GREEN);
        assertThat(renderer.colourOf(3, 3)).isEqualTo(GREEN);
        assertThat(renderer.colourOf(3, 8)).isEqualTo(MAGENTA);
        assertThat(renderer.colourOf(3, 5)).isEqualTo(CYAN);
    }

    @Test
    void aHeldRootStaysForEveryFrameAndLeavesTheRestOutside() throws Exception {
        final DescentRenderer renderer = prepared("--root", "1");

        for (int tick = 0; tick < TICKS.length; tick++) {
            assertThat(renderer.rootOf(tick)).isEqualTo(1);
        }
        assertThat(renderer.colourOf(0, 2)).isEqualTo(DescentRenderer.OUTSIDE_TONE);
        assertThat(renderer.colourOf(0, 1)).as("the root itself").isEqualTo(DescentRenderer.ROOT_TONE);
        assertThat(renderer.colourOf(9, 9)).as("no unfolding under a held root").isEqualTo(GREEN);
    }

    @Test
    void theLinesOfANewRootUnfoldFromTheFieldOverTheWindow() throws Exception {
        final DescentRenderer renderer = prepared("--unfold-frames", "4");

        // Under root 3 (from tick 4) line 7 is the largest, line 6 the second; the field is green
        assertThat(renderer.colourOf(4, 6)).as("at the jump").isEqualTo(GREEN);
        assertThat(renderer.colourOf(4, 7)).isEqualTo(GREEN);
        final int middle = renderer.colourOf(6, 6);
        for (final int shift : new int[]{16, 8, 0}) {
            assertThat(channel(middle, shift)).as("channel %d between field and target", shift)
                .isStrictlyBetween(Math.min(channel(GREEN, shift), channel(MAGENTA, shift)),
                    Math.max(channel(GREEN, shift), channel(MAGENTA, shift)));
        }
        assertThat(renderer.colourOf(8, 6)).as("at the end of the window").isEqualTo(MAGENTA);
        assertThat(renderer.colourOf(9, 6)).isEqualTo(MAGENTA);
        assertThat(renderer.colourOf(9, 7)).isEqualTo(GREEN);
    }

    @Test
    void rendersTheTwoLargestLinesInTheFirstTwoPaletteColours() throws Exception {
        final DescentRenderer renderer = prepared("--root", "1", "--scale", "0.5", "--glow-size", "8");

        final int[] pixels = renderer.renderSnapshot(DescentRunFixture.snapshot(3, TICKS[3]));

        final int line3 = pixelAt(pixels, 20, 20);
        assertThat(channel(line3, 8)).as("green").isGreaterThan(channel(line3, 16) + 40)
            .isGreaterThan(channel(line3, 0) + 40);
        final int line4 = pixelAt(pixels, 80, 20);
        assertThat(channel(line4, 16)).as("magenta").isGreaterThan(channel(line4, 8) + 40);
        assertThat(channel(line4, 0)).isGreaterThan(channel(line4, 8) + 40);
        assertThat(pixelAt(pixels, 10, 10)).as("the dead root is not drawn")
            .isEqualTo(EnvironmentBackgroundLayer.COLOR_EMPTY);
    }

    @Test
    void rendersAColourBetweenFieldAndTargetDuringTheUnfolding() throws Exception {
        final DescentRenderer renderer = prepared("--unfold-frames", "4", "--scale", "0.5", "--glow-size", "8");

        final int atJump = pixelAt(renderer.renderSnapshot(DescentRunFixture.snapshot(4, TICKS[4])), 30, 30);
        final int during = pixelAt(renderer.renderSnapshot(DescentRunFixture.snapshot(6, TICKS[6])), 30, 30);
        final int after = pixelAt(renderer.renderSnapshot(DescentRunFixture.snapshot(8, TICKS[8])), 30, 30);

        assertThat(atJump).isNotEqualTo(after);
        for (final int shift : new int[]{16, 8, 0}) {
            assertThat(channel(during, shift)).as("channel %d", shift)
                .isStrictlyBetween(Math.min(channel(atJump, shift), channel(after, shift)),
                    Math.max(channel(atJump, shift), channel(after, shift)));
        }
    }

    @Test
    void threadInstancesColourTheSameWay() throws Exception {
        final DescentRenderer renderer = prepared("--unfold-frames", "4");

        final DescentRenderer copy = (DescentRenderer) renderer.createThreadInstance();

        assertThat(copy.colourOf(6, 6)).isEqualTo(renderer.colourOf(6, 6));
        assertThat(copy.rootOf(4)).isEqualTo(3);
    }

    @Test
    void aPalettePairTurnsThePaletteByTwoColours() {
        assertThat(DescentRenderer.paletteOfPair(0)).containsExactly(DescentRenderer.PALETTE);
        final int[] second = DescentRenderer.paletteOfPair(1);
        assertThat(second).hasSize(DescentRenderer.PALETTE.length);
        assertThat(second[0]).as("starts with cyan").isEqualTo(CYAN);
        assertThat(second[second.length - 1]).as("ends with magenta").isEqualTo(MAGENTA);
        assertThat(DescentRenderer.paletteOfPair(4)).as("wraps round").containsExactly(DescentRenderer.paletteOfPair(0));
    }

    @Test
    void everyRootTakesThePaletteOnePairFurtherWithShiftPalette() throws Exception {
        // One frame of unfolding, so that every line has its colour on the second frame of a root
        final DescentRenderer renderer = prepared("--shift-palette", "--unfold-frames", "1");

        // Root all (ticks 0 and 1) takes pair 0: line 1 is the largest, line 2 the second
        assertThat(renderer.colourOf(0, 1)).isEqualTo(GREEN);
        assertThat(renderer.colourOf(0, 2)).isEqualTo(MAGENTA);
        // Root 1 (ticks 2 and 3) takes pair 1: lines 3, 4, 5 by size
        assertThat(renderer.rootOf(3)).isEqualTo(1);
        assertThat(renderer.colourOf(3, 3)).isEqualTo(CYAN);
        assertThat(renderer.colourOf(3, 8)).isEqualTo(ORANGE);
        assertThat(renderer.colourOf(3, 5)).isEqualTo(BLUE);
        // Root 3 (from tick 4) takes pair 2: line 7 is the largest, line 6 the second
        assertThat(renderer.colourOf(9, 10)).isEqualTo(BLUE);
        assertThat(renderer.colourOf(9, 9)).isEqualTo(YELLOW);
    }

    @Test
    void theLargestLineKeepsTheFieldAndTheOthersTakeTheShiftedPalette() throws Exception {
        final DescentRenderer renderer = prepared("--shift-palette", "--keep-field-colour", "--unfold-frames", "1");

        // The field of root 1 is green: line 1 of root all, which takes pair 0 unshifted. Green is
        // not the first colour of pair 1, so line 4 takes that first colour, cyan.
        assertThat(renderer.colourOf(3, 3)).as("field").isEqualTo(GREEN);
        assertThat(renderer.colourOf(3, 8)).isEqualTo(CYAN);
        assertThat(renderer.colourOf(3, 5)).isEqualTo(ORANGE);
        // Line 3 kept green, so the field of root 3 is green again; pair 2 starts with blue
        assertThat(renderer.colourOf(9, 10)).as("field").isEqualTo(GREEN);
        assertThat(renderer.colourOf(9, 9)).isEqualTo(BLUE);
    }

    @Test
    void theLegendChangesTheFrameOnlyInsideItsPanel() throws Exception {
        final DescentRenderer plain = prepared(WIDE_ENV, "--scale", "0.64", "--glow-size", "4");
        final DescentRenderer withLegend = prepared(WIDE_ENV, "--scale", "0.64", "--glow-size", "4", "--legend");

        final int[] without = plain.renderSnapshot(DescentRunFixture.snapshot(3, TICKS[3])).clone();
        final int[] with = withLegend.renderSnapshot(DescentRunFixture.snapshot(3, TICKS[3]));

        assertThat(plain.legend()).as("no legend without --legend").isNull();
        final DescentLegend legend = withLegend.legend();
        final Rectangle outline = legend.panel();
        outline.grow(legend.borderWidth(), legend.borderWidth());
        final int width = withLegend.getImageWidth();
        int differing = 0;
        for (int i = 0; i < with.length; i++) {
            final int x = i % width;
            final int y = i / width;
            if (outline.contains(x, y)) {
                assertThat(without[i]).as("nothing drawn at %d,%d without the legend", x, y)
                    .isEqualTo(EnvironmentBackgroundLayer.COLOR_EMPTY);
            }
            if (with[i] != without[i]) {
                assertThat(outline.contains(x, y)).as("pixel %d,%d outside the panel %s", x, y, outline).isTrue();
                differing++;
            }
        }
        assertThat(differing).as("the legend is drawn").isPositive();
        // Root 1 at tick 3: lines 3, 4 and 5 alive, one organism each, beside the root
        assertThat(legend.rootBorn()).isEqualTo("0");
        assertThat(legend.cellCount()).isEqualTo(3);
        assertThat(legend.cellShare(0)).isEqualTo("50%");
        assertThat(legend.cellBorn(0)).isEqualTo("1");
        assertThat(legend.hasMore()).isFalse();
    }

    @Test
    void aThreadInstanceDrawsItsOwnLegend() throws Exception {
        final DescentRenderer renderer = prepared(WIDE_ENV, "--scale", "0.64", "--legend");

        final DescentRenderer copy = (DescentRenderer) renderer.createThreadInstance();

        assertThat(copy.legend()).isNotNull().isNotSameAs(renderer.legend());
        assertThat(copy.renderSnapshot(DescentRunFixture.snapshot(3, TICKS[3])))
            .isEqualTo(renderer.renderSnapshot(DescentRunFixture.snapshot(3, TICKS[3])));
    }

    @Test
    void theLegendIsOneColumnWideOnAFrameWhoseSecondColumnHoldsNothing() throws Exception {
        final DescentRenderer plain = prepared(WIDE_ENV, "--scale", "0.64", "--glow-size", "4");
        final DescentRenderer withLegend = prepared(WIDE_ENV, "--scale", "0.64", "--glow-size", "4", "--legend");
        final DescentLegend legend = withLegend.legend();
        withLegend.renderSnapshot(DescentRunFixture.snapshot(3, TICKS[3]));
        assertThat(legend.hasSecondColumn()).as("three line cells at tick 3").isTrue();
        final int wide = legend.panel().width;

        final int[] without = plain.renderSnapshot(DescentRunFixture.snapshot(9, TICKS[9])).clone();
        final int[] with = withLegend.renderSnapshot(DescentRunFixture.snapshot(9, TICKS[9]));

        // Root 3 at tick 9: lines 7 and 6 alive, both in the first column
        assertThat(legend.cellCount()).isEqualTo(2);
        assertThat(legend.hasSecondColumn()).isFalse();
        final Rectangle outline = legend.panel();
        assertThat(outline.width).isLessThan(wide);
        outline.grow(legend.borderWidth(), legend.borderWidth());
        final int width = withLegend.getImageWidth();
        for (int i = 0; i < with.length; i++) {
            if (with[i] != without[i]) {
                assertThat(outline.contains(i % width, i / width)).as("pixel %d,%d outside the panel %s",
                    i % width, i / width, outline).isTrue();
            }
        }
    }

    @Test
    void withShadesTwoOrganismsOfOneLineWithDifferentGenomesDifferLessThanFromOtherLines() throws Exception {
        final DescentRenderer renderer = prepared("--shades", "--unfold-frames", "1");

        // Under root 3 line 7 is green: 11 keeps 7's genome, 10 changed it; line 6 is magenta
        final int unchanged = renderer.colourOf(9, 11);
        final int changed = renderer.colourOf(9, 10);
        assertThat(unchanged).isEqualTo(GREEN);
        assertThat(changed).isEqualTo(LineShade.colour(GREEN, LineShade.drift((byte) 0, 1000))).isNotEqualTo(GREEN);
        final int line6 = renderer.colourOf(9, 6);
        assertThat(line6).isEqualTo(LineShade.colour(MAGENTA, LineShade.drift((byte) 0, 600)));
        assertThat(renderer.colourOf(9, 9)).as("9 keeps 6's genome").isEqualTo(line6);
        final double within = distance(changed, unchanged);
        for (final int other : new int[]{line6, MAGENTA}) {
            assertThat(distance(changed, other)).isGreaterThan(within);
            assertThat(distance(unchanged, other)).isGreaterThan(within);
        }
    }

    @Test
    void withoutShadesEveryOrganismOfALineHasTheLinesColour() throws Exception {
        final DescentRenderer plain = prepared("--unfold-frames", "1", "--scale", "0.5", "--glow-size", "8");
        final DescentRenderer shaded = prepared("--unfold-frames", "1", "--scale", "0.5", "--glow-size", "8",
            "--shades");

        assertThat(plain.colourOf(9, 10)).isEqualTo(GREEN);
        assertThat(plain.colourOf(9, 11)).isEqualTo(GREEN);
        assertThat(plain.colourOf(9, 6)).isEqualTo(MAGENTA);
        final int[] without = plain.renderSnapshot(DescentRunFixture.snapshot(9, TICKS[9])).clone();
        final int[] with = shaded.renderSnapshot(DescentRunFixture.snapshot(9, TICKS[9]));
        assertThat(pixelAt(with, 70, 50)).as("10, changed").isNotEqualTo(pixelAt(without, 70, 50));
        assertThat(pixelAt(with, 50, 70)).as("11, unchanged").isEqualTo(pixelAt(without, 50, 70));
    }

    @Test
    void withShadesTheLegendsSquaresKeepTheLinesColour() throws Exception {
        final DescentRenderer renderer = prepared(WIDE_ENV, "--scale", "0.64", "--legend", "--shades",
            "--unfold-frames", "1");

        renderer.renderSnapshot(DescentRunFixture.snapshot(9, TICKS[9]));

        assertThat(renderer.legend().cellColour(0)).isEqualTo(GREEN);
        assertThat(renderer.legend().cellColour(1)).isEqualTo(MAGENTA);
    }

    @Test
    void withShadesTheRootAndTheOutsideAreNotShaded() throws Exception {
        final DescentRenderer plain = prepared("--root", "4", "--scale", "0.5", "--glow-size", "8");
        final DescentRenderer shaded = prepared("--root", "4", "--scale", "0.5", "--glow-size", "8", "--shades");

        // 4 changed its parent's genome, 8 keeps 4's, 6 outside the root changed 3's
        assertThat(shaded.colourOf(3, 4)).isEqualTo(DescentRenderer.ROOT_TONE);
        assertThat(shaded.colourOf(3, 6)).isEqualTo(DescentRenderer.OUTSIDE_TONE);
        assertThat(shaded.colourOf(3, 8)).isEqualTo(LineShade.colour(GREEN, LineShade.drift((byte) 0, 400)))
            .isNotEqualTo(GREEN);
        final int[] without = plain.renderSnapshot(DescentRunFixture.snapshot(3, TICKS[3])).clone();
        final int[] with = shaded.renderSnapshot(DescentRunFixture.snapshot(3, TICKS[3]));
        assertThat(pixelAt(with, 80, 20)).as("the root").isEqualTo(pixelAt(without, 80, 20));
        assertThat(pixelAt(with, 30, 30)).as("outside").isEqualTo(pixelAt(without, 30, 30));
        assertThat(pixelAt(with, 80, 40)).as("the line").isNotEqualTo(pixelAt(without, 80, 40));
    }

    @Test
    void anUnknownRootIdIsRejectedBeforeTheFirstFrame() {
        assertThatThrownBy(() -> prepared("--root", "99"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--root 99");
    }

    @Test
    void malformedOptionsAreRejected() {
        for (final String bad : new String[]{"x", "0", "-3", "ALL"}) {
            final DescentRenderer renderer = new DescentRenderer();
            new CommandLine(renderer).parseArgs("--root", bad);
            assertThatThrownBy(() -> renderer.init(ENV)).as(bad)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--root");
        }
        final DescentRenderer noWindow = new DescentRenderer();
        new CommandLine(noWindow).parseArgs("--unfold-frames", "0");
        assertThatThrownBy(() -> noWindow.init(ENV))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--unfold-frames");
        final DescentRenderer overwriteAlone = new DescentRenderer();
        new CommandLine(overwriteAlone).parseArgs("--ancestry-overwrite");
        assertThatThrownBy(() -> overwriteAlone.init(ENV))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs --ancestry");
        final DescentRenderer wideScale = new DescentRenderer();
        new CommandLine(wideScale).parseArgs("--scale", "1.5");
        assertThatThrownBy(() -> wideScale.init(ENV))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("between 0 and 1");
    }

    @Test
    void aFrameBeforePrepareIsRejected() {
        final DescentRenderer renderer = new DescentRenderer();
        new CommandLine(renderer).parseArgs();
        renderer.init(ENV);

        assertThatThrownBy(() -> renderer.renderSnapshot(DescentRunFixture.snapshot(0, TICKS[0])))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("prepare");
    }
}
