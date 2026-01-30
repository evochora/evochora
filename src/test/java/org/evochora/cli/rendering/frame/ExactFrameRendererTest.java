package org.evochora.cli.rendering.frame;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.TickData;
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
}
