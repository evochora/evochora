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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for LineageRenderer.
 * Tests HSL conversion, lineage-aware color assignment, environment background,
 * organism glow rendering, and sampling mode overlay support.
 */
@Tag("unit")
public class LineageRendererTest {

    private static final int EMPTY_COLOR = EnvironmentBackgroundLayer.CELL_COLORS[EnvironmentBackgroundLayer.TYPE_EMPTY];
    private static final int ENERGY_COLOR = EnvironmentBackgroundLayer.CELL_COLORS[EnvironmentBackgroundLayer.TYPE_ENERGY];

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
        LineageRenderer renderer = new LineageRenderer();
        CommandLine cmdLine = new CommandLine(renderer);

        assertThat(cmdLine.getCommandName()).isEqualTo("lineage");
    }

    @Test
    void testDefaultScale() {
        LineageRenderer renderer = createRenderer();

        assertThat(renderer.getImageWidth()).isEqualTo(30);
        assertThat(renderer.getImageHeight()).isEqualTo(30);
    }

    @Test
    void testCustomScale() {
        LineageRenderer renderer = createRenderer("--scale", "0.5");

        assertThat(renderer.getImageWidth()).isEqualTo(50);
        assertThat(renderer.getImageHeight()).isEqualTo(50);
    }

    @Test
    void testScaleMustBeBetweenZeroAndOne() {
        LineageRenderer renderer = new LineageRenderer();
        new CommandLine(renderer).parseArgs("--scale", "1.5");

        assertThatThrownBy(() -> renderer.init(envProps))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 0 and 1");
    }

    @Test
    void testThrowsIfNotInitialized() {
        LineageRenderer renderer = new LineageRenderer();
        new CommandLine(renderer).parseArgs();

        assertThatThrownBy(renderer::getFrame)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not initialized");
    }

    // ========================================================================
    // HSL → RGB conversion
    // ========================================================================

    @Test
    void testHslToRgb_pureRed() {
        int rgb = LineageRenderer.hslToRgb(0, 1.0f, 0.5f);

        assertThat((rgb >> 16) & 0xFF).isEqualTo(255);
        assertThat((rgb >> 8) & 0xFF).isEqualTo(0);
        assertThat(rgb & 0xFF).isEqualTo(0);
    }

    @Test
    void testHslToRgb_pureGreen() {
        int rgb = LineageRenderer.hslToRgb(120, 1.0f, 0.5f);

        assertThat((rgb >> 16) & 0xFF).isEqualTo(0);
        assertThat((rgb >> 8) & 0xFF).isEqualTo(255);
        assertThat(rgb & 0xFF).isEqualTo(0);
    }

    @Test
    void testHslToRgb_pureBlue() {
        int rgb = LineageRenderer.hslToRgb(240, 1.0f, 0.5f);

        assertThat((rgb >> 16) & 0xFF).isEqualTo(0);
        assertThat((rgb >> 8) & 0xFF).isEqualTo(0);
        assertThat(rgb & 0xFF).isEqualTo(255);
    }

    @Test
    void testHslToRgb_white() {
        int rgb = LineageRenderer.hslToRgb(0, 0.0f, 1.0f);

        assertThat(rgb).isEqualTo(0xFFFFFF);
    }

    @Test
    void testHslToRgb_black() {
        int rgb = LineageRenderer.hslToRgb(0, 0.0f, 0.0f);

        assertThat(rgb).isEqualTo(0);
    }

    // ========================================================================
    // Environment background (via EnvironmentBackgroundLayer)
    // ========================================================================

    @Test
    void testRenderSnapshot_noCells_noOrganisms_allEmpty() {
        LineageRenderer renderer = createRenderer("--scale", "0.5");

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        for (int px : pixels) {
            assertThat(px).isEqualTo(EMPTY_COLOR);
        }
    }

    @Test
    void testRenderSnapshot_energyCells_showEnergyColor() {
        LineageRenderer renderer = createRenderer("--scale", "0.1");

        // Fill top-left block with ENERGY
        CellDataColumns.Builder cells = CellDataColumns.newBuilder();
        for (int wx = 0; wx < 10; wx++) {
            for (int wy = 0; wy < 10; wy++) {
                cells.addFlatIndices(wx * 100 + wy);
                cells.addMoleculeData(Config.TYPE_ENERGY | 1);
                cells.addOwnerIds(0);
            }
        }

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(cells.build())
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        // Pixel (0,0) should show ENERGY_COLOR
        assertThat(pixels[0]).isEqualTo(ENERGY_COLOR);
        // Pixel (5,5) should be empty
        assertThat(pixels[5 * 10 + 5]).isEqualTo(EMPTY_COLOR);
    }

    // ========================================================================
    // Organism glow rendering
    // ========================================================================

    @Test
    void testRenderSnapshot_singleOrganism_glowVisibleOnBackground() {
        LineageRenderer renderer = createRenderer("--scale", "0.5");

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .addOrganisms(createOrganism(1, 50, 50, 42L))
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        // Organism at world (50,50) → pixel (25,25) at scale 0.5
        int centerIdx = 25 * 50 + 25;

        // Center pixel should differ from empty background (glow rendered)
        assertThat(pixels[centerIdx]).isNotEqualTo(EMPTY_COLOR);
    }

    @Test
    void testRenderSnapshot_deadOrganismIgnored() {
        LineageRenderer renderer = createRenderer("--scale", "0.5");

        OrganismState deadOrg = OrganismState.newBuilder()
            .setOrganismId(1)
            .setIp(createVector(50, 50))
            .setDv(createVector(1, 0))
            .setGenomeHash(42L)
            .setIsDead(true)
            .build();

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .addOrganisms(deadOrg)
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        // All pixels should be empty (dead organism has no glow)
        for (int px : pixels) {
            assertThat(px).isEqualTo(EMPTY_COLOR);
        }
    }

    @Test
    void testRenderSnapshot_twoUnrelatedGenomes_differentColors() {
        LineageRenderer renderer = createRenderer("--scale", "0.5");

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .addOrganisms(createOrganism(1, 20, 20, 100L))
            .addOrganisms(createOrganism(2, 80, 80, 200L))
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        // Pixel near org1 (10,10) and near org2 (40,40)
        int pixel1 = pixels[10 * 50 + 10];
        int pixel2 = pixels[40 * 50 + 40];

        // Both should be visible
        assertThat(pixel1).isNotEqualTo(EMPTY_COLOR);
        assertThat(pixel2).isNotEqualTo(EMPTY_COLOR);

        // Unrelated genomes should have different colors (golden ratio spacing)
        assertThat(pixel1).isNotEqualTo(pixel2);
    }

    // ========================================================================
    // Lineage color assignment
    // ========================================================================

    @Test
    void testLineageHueShift_childHasSimilarColor() {
        LineageRenderer renderer = createRenderer("--scale", "0.5", "--hue-shift", "3");

        OrganismState parent = createOrganism(1, 20, 20, 100L);
        OrganismState child = OrganismState.newBuilder()
            .setOrganismId(2)
            .setIp(createVector(80, 80))
            .setDv(createVector(1, 0))
            .setGenomeHash(200L)
            .setParentId(1)
            .build();

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .addOrganisms(parent)
            .addOrganisms(child)
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        int parentPixel = pixels[10 * 50 + 10];
        int childPixel = pixels[40 * 50 + 40];

        // Both visible
        assertThat(parentPixel).isNotEqualTo(EMPTY_COLOR);
        assertThat(childPixel).isNotEqualTo(EMPTY_COLOR);

        // Colors should be different but similar (small hue shift)
        assertThat(childPixel).isNotEqualTo(parentPixel);

        // Channel differences should be small for 3° hue shift
        assertThat(Math.abs(((parentPixel >> 16) & 0xFF) - ((childPixel >> 16) & 0xFF))).isLessThan(40);
        assertThat(Math.abs(((parentPixel >> 8) & 0xFF) - ((childPixel >> 8) & 0xFF))).isLessThan(40);
        assertThat(Math.abs((parentPixel & 0xFF) - (childPixel & 0xFF))).isLessThan(40);
    }

    @Test
    void testTwoPassProcessing_childBeforeParent_stillGetsLineageColor() {
        LineageRenderer renderer = createRenderer("--scale", "0.5", "--hue-shift", "3");

        OrganismState parent = createOrganism(1, 20, 20, 100L);
        OrganismState child = OrganismState.newBuilder()
            .setOrganismId(2)
            .setIp(createVector(80, 80))
            .setDv(createVector(1, 0))
            .setGenomeHash(200L)
            .setParentId(1)
            .build();

        // Child BEFORE parent in the list — regression test for ordering bug
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .addOrganisms(child)
            .addOrganisms(parent)
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        int parentPixel = pixels[10 * 50 + 10];
        int childPixel = pixels[40 * 50 + 40];

        // Both visible
        assertThat(parentPixel).isNotEqualTo(EMPTY_COLOR);
        assertThat(childPixel).isNotEqualTo(EMPTY_COLOR);

        // Child should still get a similar color (not golden-ratio-separated)
        assertThat(Math.abs(((parentPixel >> 16) & 0xFF) - ((childPixel >> 16) & 0xFF))).isLessThan(40);
        assertThat(Math.abs(((parentPixel >> 8) & 0xFF) - ((childPixel >> 8) & 0xFF))).isLessThan(40);
        assertThat(Math.abs((parentPixel & 0xFF) - (childPixel & 0xFF))).isLessThan(40);
    }

    // ========================================================================
    // Delta rendering
    // ========================================================================

    @Test
    void testDelta_organismMoves_glowFollows() {
        LineageRenderer renderer = createRenderer("--scale", "0.5");

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .addOrganisms(createOrganism(1, 20, 20, 42L))
            .build();
        renderer.renderSnapshot(snapshot);

        // Organism moves to (80,80)
        TickDelta delta = TickDelta.newBuilder()
            .setTickNumber(1)
            .setChangedCells(CellDataColumns.getDefaultInstance())
            .addOrganisms(createOrganism(1, 80, 80, 42L))
            .build();
        int[] pixels = renderer.renderDelta(delta);

        int oldPixel = pixels[10 * 50 + 10];
        int newPixel = pixels[40 * 50 + 40];

        // New position should have glow, old should be empty background
        assertThat(newPixel).isNotEqualTo(EMPTY_COLOR);
        assertThat(oldPixel).isEqualTo(EMPTY_COLOR);
    }

    // ========================================================================
    // Generation reset
    // ========================================================================

    @Test
    void testNewSnapshot_resetsBackground() {
        LineageRenderer renderer = createRenderer("--scale", "0.1");

        // Snapshot 1: ENERGY cells in top-left
        CellDataColumns.Builder cells1 = CellDataColumns.newBuilder();
        for (int wx = 0; wx < 10; wx++) {
            for (int wy = 0; wy < 10; wy++) {
                cells1.addFlatIndices(wx * 100 + wy);
                cells1.addMoleculeData(Config.TYPE_ENERGY | 1);
                cells1.addOwnerIds(0);
            }
        }
        TickData snapshot1 = TickData.newBuilder()
            .setTickNumber(0).setCellColumns(cells1.build()).build();
        renderer.renderSnapshot(snapshot1);

        // Snapshot 2: empty
        TickData snapshot2 = TickData.newBuilder()
            .setTickNumber(1).setCellColumns(CellDataColumns.getDefaultInstance()).build();
        int[] pixels = renderer.renderSnapshot(snapshot2);

        // Old ENERGY should be gone
        assertThat(pixels[0]).isEqualTo(EMPTY_COLOR);
    }

    // ========================================================================
    // Sampling mode
    // ========================================================================

    @Test
    void testSamplingMode_renderCurrentState() {
        LineageRenderer renderer = createRenderer("--scale", "0.5");

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .addOrganisms(createOrganism(1, 50, 50, 42L))
            .build();

        renderer.applySnapshotState(snapshot);
        int[] pixels = renderer.renderCurrentState();

        int centerIdx = 25 * 50 + 25;
        assertThat(pixels[centerIdx]).isNotEqualTo(EMPTY_COLOR);
    }

    // ========================================================================
    // Thread instance
    // ========================================================================

    @Test
    void testCreateThreadInstance() {
        LineageRenderer renderer = createRenderer("--scale", "0.2");

        var threadInstance = renderer.createThreadInstance();

        assertThat(threadInstance).isNotNull();
        assertThat(threadInstance).isNotSameAs(renderer);
        assertThat(threadInstance.getImageWidth()).isEqualTo(renderer.getImageWidth());
        assertThat(threadInstance.getImageHeight()).isEqualTo(renderer.getImageHeight());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private LineageRenderer createRenderer(String... args) {
        LineageRenderer renderer = new LineageRenderer();
        new CommandLine(renderer).parseArgs(args);
        renderer.init(envProps);
        return renderer;
    }

    private OrganismState createOrganism(int id, int x, int y, long genomeHash) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setIp(createVector(x, y))
            .setDv(createVector(1, 0))
            .setGenomeHash(genomeHash)
            .build();
    }

    private Vector createVector(int... components) {
        Vector.Builder builder = Vector.newBuilder();
        for (int c : components) {
            builder.addComponents(c);
        }
        return builder.build();
    }
}
