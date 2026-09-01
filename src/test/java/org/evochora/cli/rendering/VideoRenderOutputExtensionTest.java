package org.evochora.cli.rendering;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks how the output file name and the chosen format are reconciled.
 */
@Tag("unit")
class VideoRenderOutputExtensionTest {

    @Test
    void matchingExtensionIsKept() {
        File result = VideoRenderEngine.withExtension(new File("/tmp/run.mp4"), "mp4");

        assertThat(result.getName()).isEqualTo("run.mp4");
    }

    @Test
    void matchingExtensionIsRecognisedRegardlessOfCase() {
        File result = VideoRenderEngine.withExtension(new File("/tmp/run.MP4"), "mp4");

        assertThat(result.getName()).isEqualTo("run.MP4");
    }

    @Test
    void anotherVideoExtensionIsReplacedRatherThanStacked() {
        // The defaults are simulation.mkv and mkv, so changing only --format hits this case.
        File result = VideoRenderEngine.withExtension(new File("/tmp/simulation.mkv"), "mp4");

        assertThat(result.getName()).isEqualTo("simulation.mp4");
    }

    @Test
    void unknownExtensionIsKeptAndTheFormatAppended() {
        File result = VideoRenderEngine.withExtension(new File("/tmp/my.video"), "mp4");

        assertThat(result.getName()).isEqualTo("my.video.mp4");
    }

    @Test
    void nameWithoutExtensionGetsOne() {
        File result = VideoRenderEngine.withExtension(new File("/tmp/run"), "webm");

        assertThat(result.getName()).isEqualTo("run.webm");
    }

    @Test
    void directoryIsPreserved() {
        File result = VideoRenderEngine.withExtension(new File("/tmp/videos/run.mkv"), "mp4");

        assertThat(result.getPath()).isEqualTo(new File("/tmp/videos/run.mp4").getPath());
    }
}
