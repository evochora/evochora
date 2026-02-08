package org.evochora.cli.rendering.frame;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke tests for the ExactFrameRenderer.
 * Tests initialization, rendering, and CLI option parsing.
 */
@Tag("unit")
public class ExactFrameRendererTest {

    private EnvironmentProperties envProps;

    @BeforeEach
    void setUp() {
        envProps = new EnvironmentProperties(new int[]{100, 100}, true);
    }

    @Test
    void testCommandAnnotation() {
        ExactFrameRenderer renderer = new ExactFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);

        assertThat(cmdLine.getCommandName()).isEqualTo("exact");
    }

    @Test
    void testDefaultScale() {
        ExactFrameRenderer renderer = new ExactFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs(); // Parse with defaults

        renderer.init(envProps);

        // Default scale is 4, so 100x100 world = 400x400 image
        assertThat(renderer.getImageWidth()).isEqualTo(400);
        assertThat(renderer.getImageHeight()).isEqualTo(400);
    }

    @Test
    void testCustomScale() {
        ExactFrameRenderer renderer = new ExactFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs("--scale", "2");

        renderer.init(envProps);

        // Scale 2, so 100x100 world = 200x200 image
        assertThat(renderer.getImageWidth()).isEqualTo(200);
        assertThat(renderer.getImageHeight()).isEqualTo(200);
    }

    @Test
    void testGetFrameReturnsBufferedImage() {
        ExactFrameRenderer renderer = new ExactFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs();
        renderer.init(envProps);

        BufferedImage frame = renderer.getFrame();

        assertThat(frame).isNotNull();
        assertThat(frame.getWidth()).isEqualTo(400);
        assertThat(frame.getHeight()).isEqualTo(400);
    }

    @Test
    void testRenderSnapshotReturnsPixelBuffer() {
        ExactFrameRenderer renderer = new ExactFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs();
        renderer.init(envProps);

        // Create minimal snapshot with empty cell data
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(0)
            .setCellColumns(CellDataColumns.getDefaultInstance())
            .build();

        int[] pixels = renderer.renderSnapshot(snapshot);

        assertThat(pixels).isNotNull();
        assertThat(pixels.length).isEqualTo(400 * 400);
    }

    @Test
    void testBgraBufferIsReusable() {
        ExactFrameRenderer renderer = new ExactFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs();
        renderer.init(envProps);

        byte[] buffer1 = renderer.getBgraBuffer();
        byte[] buffer2 = renderer.getBgraBuffer();

        assertThat(buffer1).isSameAs(buffer2);
        assertThat(buffer1.length).isEqualTo(400 * 400 * 4);
    }

    @Test
    void testThrowsIfNotInitialized() {
        ExactFrameRenderer renderer = new ExactFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs();
        // Do NOT call init()

        assertThatThrownBy(renderer::getFrame)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not initialized");
    }

    @Test
    void testCreateThreadInstance() {
        ExactFrameRenderer renderer = new ExactFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs("--scale", "2");
        renderer.init(envProps);

        var threadInstance = renderer.createThreadInstance();

        assertThat(threadInstance).isNotNull();
        assertThat(threadInstance).isNotSameAs(renderer);
        assertThat(threadInstance.getImageWidth()).isEqualTo(renderer.getImageWidth());
    }

    @Nested
    @DisplayName("Empty Cell Bug Fix")
    class EmptyCellBugFixTests {

        @Test
        @DisplayName("Removed molecule (moleculeData=0) renders as empty, not as CODE")
        void removedMolecule_rendersAsEmpty() {
            ExactFrameRenderer renderer = new ExactFrameRenderer();
            new CommandLine(renderer).parseArgs("--scale", "1");
            renderer.init(envProps);

            // Place a CODE molecule at cell (10, 20): flatIndex = 10 * 100 + 20 = 1020
            int flatIndex = 10 * 100 + 20;
            int codeMolecule = Config.TYPE_CODE | 42; // CODE type with value 42

            TickData snapshot = TickData.newBuilder()
                .setTickNumber(0)
                .setCellColumns(CellDataColumns.newBuilder()
                    .addFlatIndices(flatIndex)
                    .addMoleculeData(codeMolecule))
                .build();

            int[] pixels = renderer.renderSnapshot(snapshot);

            // Pixel at cell (10, 20) = frameBuffer[20 * 100 + 10]
            int pixelIndex = 20 * 100 + 10;
            assertThat(pixels[pixelIndex]).isEqualTo(0x3c5078); // COLOR_CODE

            // Now apply delta that removes the molecule (moleculeData=0)
            TickDelta delta = TickDelta.newBuilder()
                .setTickNumber(1)
                .setChangedCells(CellDataColumns.newBuilder()
                    .addFlatIndices(flatIndex)
                    .addMoleculeData(0))
                .build();

            int[] deltaPixels = renderer.renderDelta(delta);

            // Pixel should now be empty (black), not CODE blue
            assertThat(deltaPixels[pixelIndex]).isEqualTo(0x000000); // COLOR_EMPTY
        }

        @Test
        @DisplayName("moleculeData=0 in snapshot renders as empty")
        void zeroMoleculeData_inSnapshot_rendersAsEmpty() {
            ExactFrameRenderer renderer = new ExactFrameRenderer();
            new CommandLine(renderer).parseArgs("--scale", "1");
            renderer.init(envProps);

            // Include a cell with moleculeData=0 in the snapshot
            int flatIndex = 5 * 100 + 5;

            TickData snapshot = TickData.newBuilder()
                .setTickNumber(0)
                .setCellColumns(CellDataColumns.newBuilder()
                    .addFlatIndices(flatIndex)
                    .addMoleculeData(0))
                .build();

            int[] pixels = renderer.renderSnapshot(snapshot);

            int pixelIndex = 5 * 100 + 5;
            assertThat(pixels[pixelIndex]).isEqualTo(0x000000); // COLOR_EMPTY
        }
    }

    @Nested
    @DisplayName("Genome Hash Coloring")
    class GenomeHashColoringTests {

        private ExactFrameRenderer renderer;

        @BeforeEach
        void setUp() {
            renderer = new ExactFrameRenderer();
            new CommandLine(renderer).parseArgs("--scale", "1");
            renderer.init(envProps);
        }

        @Test
        @DisplayName("Organisms with same genome hash produce same color")
        void sameGenomeHash_sameColor() {
            TickData snapshot = TickData.newBuilder()
                .setTickNumber(0)
                .setCellColumns(CellDataColumns.getDefaultInstance())
                .addOrganisms(createOrganism(1, 20, 20, 111L))
                .addOrganisms(createOrganism(2, 60, 60, 111L))
                .build();

            int[] pixels = renderer.renderSnapshot(snapshot);

            // Check center pixels of each organism marker
            int px1 = pixels[20 * 100 + 20];
            int px2 = pixels[60 * 100 + 60];

            assertThat(px1).isNotEqualTo(0x000000);
            assertThat(px2).isNotEqualTo(0x000000);
            assertThat(px1).isEqualTo(px2);
        }

        @Test
        @DisplayName("Organisms with different genome hashes produce different colors")
        void differentGenomeHash_differentColor() {
            TickData snapshot = TickData.newBuilder()
                .setTickNumber(0)
                .setCellColumns(CellDataColumns.getDefaultInstance())
                .addOrganisms(createOrganism(1, 20, 20, 111L))
                .addOrganisms(createOrganism(2, 60, 60, 222L))
                .build();

            int[] pixels = renderer.renderSnapshot(snapshot);

            int px1 = pixels[20 * 100 + 20];
            int px2 = pixels[60 * 100 + 60];

            assertThat(px1).isNotEqualTo(0x000000);
            assertThat(px2).isNotEqualTo(0x000000);
            assertThat(px1).isNotEqualTo(px2);
        }

        @Test
        @DisplayName("First genome hash gets green palette color")
        void firstGenomeHash_getsGreen() {
            TickData snapshot = TickData.newBuilder()
                .setTickNumber(0)
                .setCellColumns(CellDataColumns.getDefaultInstance())
                .addOrganisms(createOrganism(1, 50, 50, 12345L))
                .build();

            int[] pixels = renderer.renderSnapshot(snapshot);

            // Center pixel of the organism IP marker
            int px = pixels[50 * 100 + 50];

            // Should be green (0x32cd32)
            assertThat(px).isEqualTo(0x32cd32);
        }

        @Test
        @DisplayName("Dead organisms rendered in gray, not genome hash color")
        void deadOrganisms_renderedInGray() {
            OrganismState deadOrg = OrganismState.newBuilder()
                .setOrganismId(1)
                .setIp(createVector(50, 50))
                .setDv(createVector(1, 0))
                .setGenomeHash(111L)
                .setIsDead(true)
                .build();

            TickData snapshot = TickData.newBuilder()
                .setTickNumber(0)
                .setCellColumns(CellDataColumns.getDefaultInstance())
                .addOrganisms(deadOrg)
                .build();

            int[] pixels = renderer.renderSnapshot(snapshot);

            // Dead organism marker should be COLOR_DEAD (0x555555)
            int px = pixels[50 * 100 + 50];
            assertThat(px).isEqualTo(0x555555);
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
}
