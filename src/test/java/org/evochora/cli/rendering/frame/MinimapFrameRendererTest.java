package org.evochora.cli.rendering.frame;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
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
 * Smoke tests for the MinimapFrameRenderer.
 * Tests initialization, rendering, and CLI option parsing.
 */
@Tag("unit")
public class MinimapFrameRendererTest {

    private EnvironmentProperties envProps;

    @BeforeEach
    void setUp() {
        envProps = new EnvironmentProperties(new int[]{1000, 1000}, true);
    }

    @Test
    void testCommandAnnotation() {
        MinimapFrameRenderer renderer = new MinimapFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);

        assertThat(cmdLine.getCommandName()).isEqualTo("minimap");
    }

    @Test
    void testDefaultScale() {
        MinimapFrameRenderer renderer = new MinimapFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs(); // Parse with defaults

        renderer.init(envProps);

        // Default scale is 0.3, so 1000x1000 world = 300x300 output
        assertThat(renderer.getImageWidth()).isEqualTo(300);
        assertThat(renderer.getImageHeight()).isEqualTo(300);
    }

    @Test
    void testCustomScale() {
        MinimapFrameRenderer renderer = new MinimapFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs("--scale", "0.1");

        renderer.init(envProps);

        // Scale 0.1, so 1000x1000 world = 100x100 output
        assertThat(renderer.getImageWidth()).isEqualTo(100);
        assertThat(renderer.getImageHeight()).isEqualTo(100);
    }

    @Test
    void testScaleMustBeBetweenZeroAndOne() {
        MinimapFrameRenderer renderer = new MinimapFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs("--scale", "1.5");

        assertThatThrownBy(() -> renderer.init(envProps))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 0 and 1");
    }

    @Test
    void testScaleMustBePositive() {
        MinimapFrameRenderer renderer = new MinimapFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs("--scale", "0");

        assertThatThrownBy(() -> renderer.init(envProps))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 0 and 1");
    }

    @Test
    void testGetFrameReturnsBufferedImage() {
        MinimapFrameRenderer renderer = new MinimapFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs();
        renderer.init(envProps);

        BufferedImage frame = renderer.getFrame();

        assertThat(frame).isNotNull();
        assertThat(frame.getWidth()).isEqualTo(300);
        assertThat(frame.getHeight()).isEqualTo(300);
    }

    @Test
    void testRenderSnapshotReturnsPixelBuffer() {
        MinimapFrameRenderer renderer = new MinimapFrameRenderer();
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
        assertThat(pixels.length).isEqualTo(300 * 300);
    }

    @Test
    void testBgraBufferIsReusable() {
        MinimapFrameRenderer renderer = new MinimapFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs();
        renderer.init(envProps);

        byte[] buffer1 = renderer.getBgraBuffer();
        byte[] buffer2 = renderer.getBgraBuffer();

        assertThat(buffer1).isSameAs(buffer2);
        assertThat(buffer1.length).isEqualTo(300 * 300 * 4);
    }

    @Test
    void testThrowsIfNotInitialized() {
        MinimapFrameRenderer renderer = new MinimapFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs();
        // Do NOT call init()

        assertThatThrownBy(renderer::getFrame)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not initialized");
    }

    @Test
    void testCreateThreadInstance() {
        MinimapFrameRenderer renderer = new MinimapFrameRenderer();
        CommandLine cmdLine = new CommandLine(renderer);
        cmdLine.parseArgs("--scale", "0.2");
        renderer.init(envProps);

        var threadInstance = renderer.createThreadInstance();

        assertThat(threadInstance).isNotNull();
        assertThat(threadInstance).isNotSameAs(renderer);
        assertThat(threadInstance.getImageWidth()).isEqualTo(renderer.getImageWidth());
    }

    @Nested
    @DisplayName("Genome Hash Coloring")
    class GenomeHashColoringTests {

        private MinimapFrameRenderer renderer;

        @BeforeEach
        void setUp() {
            renderer = new MinimapFrameRenderer();
            new CommandLine(renderer).parseArgs("--scale", "0.5");
            renderer.init(envProps);
        }

        @Test
        @DisplayName("Organisms with same genome hash produce same glow color")
        void sameGenomeHash_sameColor() {
            // Two organisms at different positions, same genome hash
            TickData snapshot = TickData.newBuilder()
                .setTickNumber(0)
                .setCellColumns(CellDataColumns.getDefaultInstance())
                .addOrganisms(createOrganism(1, 100, 100, 111L))
                .addOrganisms(createOrganism(2, 200, 200, 111L))
                .build();

            int[] pixels = renderer.renderSnapshot(snapshot);

            // Get pixels at organism positions (scale 0.5: world 100,100 → pixel 50,50)
            int px1 = pixels[50 * 500 + 50];
            int px2 = pixels[100 * 500 + 100];

            // Both should have glow effects (not the empty background color 0x1e1e28)
            assertThat(px1).isNotEqualTo(0x1e1e28);
            assertThat(px2).isNotEqualTo(0x1e1e28);
            // Same genome hash → same glow color at center pixel
            assertThat(px1).isEqualTo(px2);
        }

        @Test
        @DisplayName("Organisms with different genome hashes produce different glow colors")
        void differentGenomeHash_differentColor() {
            // Two organisms at different positions, different genome hashes
            TickData snapshot = TickData.newBuilder()
                .setTickNumber(0)
                .setCellColumns(CellDataColumns.getDefaultInstance())
                .addOrganisms(createOrganism(1, 100, 100, 111L))
                .addOrganisms(createOrganism(2, 400, 400, 222L))
                .build();

            int[] pixels = renderer.renderSnapshot(snapshot);

            // Get pixels at organism positions (scale 0.5)
            int px1 = pixels[50 * 500 + 50];
            int px2 = pixels[200 * 500 + 200];

            // Both should have glow effects
            assertThat(px1).isNotEqualTo(0x1e1e28);
            assertThat(px2).isNotEqualTo(0x1e1e28);
            // Different genome hash → different glow color
            assertThat(px1).isNotEqualTo(px2);
        }

        @Test
        @DisplayName("First genome hash gets green palette color")
        void firstGenomeHash_getsGreen() {
            // Single organism with a genome hash
            TickData snapshot = TickData.newBuilder()
                .setTickNumber(0)
                .setCellColumns(CellDataColumns.getDefaultInstance())
                .addOrganisms(createOrganism(1, 500, 500, 12345L))
                .build();

            int[] pixels = renderer.renderSnapshot(snapshot);

            // Center pixel at organism position (scale 0.5: 500,500 → 250,250)
            int px = pixels[250 * 500 + 250];

            // Should be green-ish (palette[0] = 0x32cd32), blended with empty background
            // Extract green channel - should be dominant
            int green = (px >> 8) & 0xFF;
            int red = (px >> 16) & 0xFF;
            int blue = px & 0xFF;
            assertThat(green).isGreaterThan(red);
            assertThat(green).isGreaterThan(blue);
        }

        @Test
        @DisplayName("Dead organisms are not rendered")
        void deadOrganisms_notRendered() {
            OrganismState deadOrg = OrganismState.newBuilder()
                .setOrganismId(1)
                .setIp(createVector(500, 500))
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

            // Pixel at organism position should be empty background
            int px = pixels[250 * 500 + 250];
            assertThat(px).isEqualTo(0x1e1e28);
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
