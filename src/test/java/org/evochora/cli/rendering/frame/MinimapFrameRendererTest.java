package org.evochora.cli.rendering.frame;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeEach;
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
}
