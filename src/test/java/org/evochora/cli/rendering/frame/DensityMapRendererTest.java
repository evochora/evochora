package org.evochora.cli.rendering.frame;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for DensityMapRenderer.
 * Tests initialization, cell background, Viridis colormap, box blur density,
 * delta rendering, and overlay guard.
 */
@Tag("unit")
public class DensityMapRendererTest {

    private static final int EMPTY_COLOR = 0x1e1e28;
    private static final int ENERGY_COLOR = 0xffe664;

    private EnvironmentProperties envProps;

    @BeforeEach
    void setUp() {
        envProps = new EnvironmentProperties(new int[]{100, 100}, false);
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    @Test
    void testCommandAnnotation() {
        DensityMapRenderer renderer = new DensityMapRenderer();
        CommandLine cmdLine = new CommandLine(renderer);

        assertThat(cmdLine.getCommandName()).isEqualTo("density");
    }

    @Test
    void testDefaultScale() {
        DensityMapRenderer renderer = createRenderer();

        // Default scale is 0.3, so 100x100 world = 30x30 output
        assertThat(renderer.getImageWidth()).isEqualTo(30);
        assertThat(renderer.getImageHeight()).isEqualTo(30);
    }

    @Test
    void testCustomScale() {
        DensityMapRenderer renderer = createRenderer("--scale", "0.5");

        // Scale 0.5, so 100x100 world = 50x50 output
        assertThat(renderer.getImageWidth()).isEqualTo(50);
        assertThat(renderer.getImageHeight()).isEqualTo(50);
    }

    @Test
    void testScaleMustBeBetweenZeroAndOne() {
        DensityMapRenderer renderer = new DensityMapRenderer();
        new CommandLine(renderer).parseArgs("--scale", "1.5");

        assertThatThrownBy(() -> renderer.init(envProps))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 0 and 1");
    }

    @Test
    void testScaleMustBePositive() {
        DensityMapRenderer renderer = new DensityMapRenderer();
        new CommandLine(renderer).parseArgs("--scale", "0");

        assertThatThrownBy(() -> renderer.init(envProps))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 0 and 1");
    }

    @Test
    void testThrowsIfNotInitialized() {
        DensityMapRenderer renderer = new DensityMapRenderer();
        new CommandLine(renderer).parseArgs();

        assertThatThrownBy(renderer::getFrame)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not initialized");
    }

    @Test
    void testGetFrameReturnsBufferedImage() {
        DensityMapRenderer renderer = createRenderer();

        BufferedImage frame = renderer.getFrame();

        assertThat(frame).isNotNull();
        assertThat(frame.getWidth()).isEqualTo(30);
        assertThat(frame.getHeight()).isEqualTo(30);
    }

    // ========================================================================
    // Cell background rendering
    // ========================================================================

    @Test
    void testRenderSnapshot_noCells_allPixelsEmptyColor() {
        DensityMapRenderer renderer = createRenderer("--scale", "0.5", "--blur-radius", "0");

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        assertThat(pixels).isNotNull();
        assertThat(pixels.length).isEqualTo(50 * 50);

        // All pixels should be the empty cell color (dark gray #1e1e28)
        for (int px : pixels) {
            assertThat(px).isEqualTo(EMPTY_COLOR);
        }
    }

    @Test
    void testRenderSnapshot_cellBackground_energyCellsVisible() {
        DensityMapRenderer renderer = createRenderer("--scale", "0.1", "--blur-radius", "0");
        // scale 0.1: 100x100 world → 10x10 output

        // Fill a block of cells with ENERGY to dominate the output pixel
        CellDataColumns.Builder cellsBuilder = CellDataColumns.newBuilder();
        for (int wx = 0; wx < 10; wx++) {
            for (int wy = 0; wy < 10; wy++) {
                int flatIndex = wx * 100 + wy;  // column-major: x * height + y
                cellsBuilder.addFlatIndices(flatIndex);
                cellsBuilder.addMoleculeData(Config.TYPE_ENERGY | 1);
            }
        }

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(cellsBuilder.build())
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        // Output pixel (0,0) should be ENERGY_COLOR since all cells in that area are ENERGY
        assertThat(pixels[0]).isEqualTo(ENERGY_COLOR);

        // Output pixel (5,5) should still be EMPTY_COLOR (no cells there)
        assertThat(pixels[5 * 10 + 5]).isEqualTo(EMPTY_COLOR);
    }

    // ========================================================================
    // Density overlay
    // ========================================================================

    @Test
    void testRenderSnapshot_singleOrganism_blurredDensityOverBackground() {
        DensityMapRenderer renderer = createRenderer("--scale", "0.5", "--blur-radius", "3");

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .addOrganisms(createOrganism(1, 50, 50))
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        // Organism at world (50,50) → pixel (25,25) at scale 0.5
        int centerIdx = 25 * 50 + 25;

        // Center pixel should be visibly different from empty background
        assertThat(pixels[centerIdx]).isNotEqualTo(EMPTY_COLOR);

        // Blur spreads density: neighbors should also be non-empty
        int neighborIdx = 25 * 50 + 26;  // one pixel to the right
        assertThat(pixels[neighborIdx]).isNotEqualTo(EMPTY_COLOR);

        // Far away pixel should be empty background (blur radius 3)
        int farIdx = 0;  // top-left corner
        assertThat(pixels[farIdx]).isEqualTo(EMPTY_COLOR);
    }

    @Test
    void testRenderSnapshot_densityAccumulation_higherDensityBrighter() {
        DensityMapRenderer renderer = createRenderer("--scale", "0.1", "--blur-radius", "0");
        // scale 0.1: 100x100 world → 10x10 output

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            // 3 organisms at roughly the same output pixel
            .addOrganisms(createOrganism(1, 50, 50))
            .addOrganisms(createOrganism(2, 51, 50))
            .addOrganisms(createOrganism(3, 52, 50))
            // 1 organism at a different pixel
            .addOrganisms(createOrganism(4, 10, 10))
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        // Pixel with 3 organisms should be brighter than pixel with 1
        int densePixelIdx = 5 * 10 + 5;    // world (50,50) → pixel (5,5)
        int sparsePixelIdx = 1 * 10 + 1;   // world (10,10) → pixel (1,1)

        int denseColor = pixels[densePixelIdx];
        int sparseColor = pixels[sparsePixelIdx];

        // Dense pixel should be brighter (higher Viridis index = more yellow)
        assertThat(luminance(denseColor)).isGreaterThan(luminance(sparseColor));
    }

    @Test
    void testRenderSnapshot_deadOrganismsIgnored() {
        DensityMapRenderer renderer = createRenderer("--scale", "0.5", "--blur-radius", "0");

        OrganismState deadOrg = OrganismState.newBuilder()
            .setOrganismId(1)
            .setIp(createVector(50, 50))
            .setDv(createVector(1, 0))
            .setIsDead(true)
            .build();

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .addOrganisms(deadOrg)
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        // All pixels should be empty background (dead organism not counted)
        for (int px : pixels) {
            assertThat(px).isEqualTo(EMPTY_COLOR);
        }
    }

    @Test
    void testRenderSnapshot_countDps() {
        DensityMapRenderer renderer = createRenderer("--scale", "0.1", "--count-dps", "--blur-radius", "0");
        // scale 0.1: 100x100 world → 10x10 output

        // Organism with IP at (50,50) and DP at (50,50) — same pixel
        OrganismState org = OrganismState.newBuilder()
            .setOrganismId(1)
            .setIp(createVector(50, 50))
            .setDv(createVector(1, 0))
            .addDataPointers(createVector(50, 50))
            .build();

        // Also add organism without DP at different location for comparison
        OrganismState orgNoDp = OrganismState.newBuilder()
            .setOrganismId(2)
            .setIp(createVector(10, 10))
            .setDv(createVector(1, 0))
            .build();

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .addOrganisms(org)
            .addOrganisms(orgNoDp)
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        // With --count-dps, pixel at (50,50) has density 2 (IP + DP)
        // Pixel at (10,10) has density 1 (IP only)
        int dpPixelIdx = 5 * 10 + 5;
        int noDpPixelIdx = 1 * 10 + 1;

        assertThat(luminance(pixels[dpPixelIdx])).isGreaterThan(luminance(pixels[noDpPixelIdx]));
    }

    // ========================================================================
    // Delta rendering
    // ========================================================================

    @Test
    void testRenderDelta_updatesFromDeltaOrganisms() {
        DensityMapRenderer renderer = createRenderer("--scale", "0.5", "--blur-radius", "0");

        // First: render snapshot with organism at (50,50)
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .addOrganisms(createOrganism(1, 50, 50))
            .build();
        renderer.renderSnapshot(snapshot);

        // Then: render delta with organism moved to (80,80)
        TickDelta delta = TickDelta.newBuilder()
            .setTickNumber(1)
            .setChangedCells(CellDataColumns.getDefaultInstance())
            .addOrganisms(createOrganism(1, 80, 80))
            .build();
        int[] pixels = renderer.renderDelta(delta);

        // Old position should be dark now
        int oldPixelIdx = 25 * 50 + 25;  // world (50,50) → pixel (25,25)
        int newPixelIdx = 40 * 50 + 40;  // world (80,80) → pixel (40,40)

        int oldColor = pixels[oldPixelIdx];
        int newColor = pixels[newPixelIdx];

        // New position should be bright (max density), old should be dark
        assertThat(luminance(newColor)).isGreaterThan(luminance(oldColor));
    }

    // ========================================================================
    // Box blur
    // ========================================================================

    @Test
    void testBoxBlur_zeroRadius_copiesInput() {
        int[] src = {0, 1, 0, 0, 5, 0, 0, 0, 0};
        int[] dst = new int[9];
        int[] temp = new int[9];

        DensityMapRenderer.boxBlur(src, dst, temp, 3, 3, 0);

        assertThat(dst).containsExactly(src);
    }

    @Test
    void testBoxBlur_spreadsDensity() {
        // 5x5 grid with single point in center
        int[] src = new int[25];
        src[12] = 10;  // center (2,2)
        int[] dst = new int[25];
        int[] temp = new int[25];

        DensityMapRenderer.boxBlur(src, dst, temp, 5, 5, 1);

        // Center should have highest value
        assertThat(dst[12]).isGreaterThan(0);

        // Direct neighbors should also be non-zero
        assertThat(dst[11]).isGreaterThan(0);  // (1,2)
        assertThat(dst[13]).isGreaterThan(0);  // (3,2)
        assertThat(dst[7]).isGreaterThan(0);   // (2,1)
        assertThat(dst[17]).isGreaterThan(0);  // (2,3)

        // Corners (far away for radius=1) should be zero
        assertThat(dst[0]).isEqualTo(0);   // (0,0)
        assertThat(dst[4]).isEqualTo(0);   // (4,0)
        assertThat(dst[20]).isEqualTo(0);  // (0,4)
        assertThat(dst[24]).isEqualTo(0);  // (4,4)
    }

    @Test
    void testBlurRadiusOption() {
        DensityMapRenderer renderer = createRenderer("--scale", "0.1", "--blur-radius", "2");

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .addOrganisms(createOrganism(1, 50, 50))
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        // Center pixel should be non-empty
        int centerIdx = 5 * 10 + 5;
        assertThat(pixels[centerIdx]).isNotEqualTo(EMPTY_COLOR);

        // With blur radius 2, neighbor at distance 2 should also be affected
        int nearIdx = 5 * 10 + 3;  // 2 pixels left
        assertThat(pixels[nearIdx]).isNotEqualTo(EMPTY_COLOR);
    }

    // ========================================================================
    // Sampling mode (applySnapshotState → applyDeltaState → renderCurrentState)
    // ========================================================================

    @Test
    void testSamplingMode_renderCurrentState_afterApplyState() {
        DensityMapRenderer renderer = createRenderer("--scale", "0.5", "--blur-radius", "0");

        // Simulate sampling mode: applySnapshotState, then renderCurrentState
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .addOrganisms(createOrganism(1, 50, 50))
            .build();

        renderer.applySnapshotState(snapshot);
        int[] pixels = renderer.renderCurrentState();

        // Organism pixel should be visible
        int centerIdx = 25 * 50 + 25;
        assertThat(pixels[centerIdx]).isNotEqualTo(EMPTY_COLOR);
    }

    // ========================================================================
    // Thread instance
    // ========================================================================

    @Test
    void testCreateThreadInstance() {
        DensityMapRenderer renderer = createRenderer("--scale", "0.2");

        var threadInstance = renderer.createThreadInstance();

        assertThat(threadInstance).isNotNull();
        assertThat(threadInstance).isNotSameAs(renderer);
        assertThat(threadInstance.getImageWidth()).isEqualTo(renderer.getImageWidth());
        assertThat(threadInstance.getImageHeight()).isEqualTo(renderer.getImageHeight());
    }

    @Test
    void testBgraBufferIsReusable() {
        DensityMapRenderer renderer = createRenderer();

        byte[] buffer1 = renderer.getBgraBuffer();
        byte[] buffer2 = renderer.getBgraBuffer();

        assertThat(buffer1).isSameAs(buffer2);
        assertThat(buffer1.length).isEqualTo(30 * 30 * 4);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private DensityMapRenderer createRenderer(String... args) {
        DensityMapRenderer renderer = new DensityMapRenderer();
        new CommandLine(renderer).parseArgs(args);
        renderer.init(envProps);
        return renderer;
    }

    private OrganismState createOrganism(int id, int x, int y) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setIp(createVector(x, y))
            .setDv(createVector(1, 0))
            .build();
    }

    private Vector createVector(int... components) {
        Vector.Builder builder = Vector.newBuilder();
        for (int c : components) {
            builder.addComponents(c);
        }
        return builder.build();
    }

    private int luminance(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (int) (0.3 * r + 0.6 * g + 0.1 * b);
    }
}
