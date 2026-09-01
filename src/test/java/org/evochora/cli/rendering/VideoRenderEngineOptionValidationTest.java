package org.evochora.cli.rendering;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that the render options which would otherwise fail late are rejected up front.
 * <p>
 * The validation runs before configuration and storage are touched, so these cases need no
 * simulation data and no renderer.
 */
@Tag("unit")
class VideoRenderEngineOptionValidationTest {

    /**
     * Options as picocli would hand them over: the defaults declared on the annotations are applied
     * when the command line is parsed, not when the object is built directly.
     *
     * @return options that pass validation unless a test changes one of them
     */
    private VideoRenderOptions defaultOptions() {
        VideoRenderOptions options = new VideoRenderOptions();
        options.samplingInterval = 1;
        options.format = "mkv";
        return options;
    }

    private String executeAndCaptureError(VideoRenderOptions options) throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            Integer exitCode = new VideoRenderEngine(options, null).execute();
            assertThat(exitCode).isEqualTo(1);
        } finally {
            System.setErr(originalErr);
        }
        return captured.toString();
    }

    @Test
    void samplingIntervalOfZeroIsRejectedInsteadOfDividingByZero() throws Exception {
        VideoRenderOptions options = defaultOptions();
        options.samplingInterval = 0;

        assertThat(executeAndCaptureError(options)).contains("--sampling-interval");
    }

    @Test
    void negativeSamplingIntervalIsRejected() throws Exception {
        VideoRenderOptions options = defaultOptions();
        options.samplingInterval = -5;

        assertThat(executeAndCaptureError(options)).contains("--sampling-interval");
    }

    @Test
    void startTickBeyondEndTickIsRejectedInsteadOfRenderingNothing() throws Exception {
        VideoRenderOptions options = defaultOptions();
        options.startTick = 500L;
        options.endTick = 100L;

        String error = executeAndCaptureError(options);
        assertThat(error).contains("--start-tick").contains("--end-tick");
    }

    @Test
    void unknownOverlayNameIsRejectedInsteadOfBeingSkipped() throws Exception {
        VideoRenderOptions options = defaultOptions();
        options.overlayNames = java.util.List.of("info", "typo");

        String error = executeAndCaptureError(options);
        assertThat(error).contains("typo").contains("--overlay");
    }

    @Test
    void everyDocumentedOverlayNameResolves() throws Exception {
        for (String name : java.util.List.of("info", "diversity", "graph", "logo")) {
            VideoRenderOptions options = defaultOptions();
            options.overlayNames = java.util.List.of(name);

            java.io.PrintStream originalErr = System.err;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            System.setErr(new PrintStream(captured));
            try {
                new VideoRenderEngine(options, null).execute();
            } catch (Exception expected) {
                // getting past validation is what this asserts
            } finally {
                System.setErr(originalErr);
            }
            assertThat(captured.toString()).as("overlay %s", name).doesNotContain("Unknown overlay");
        }
    }

    @Test
    void unknownFormatIsRejectedInsteadOfFallingBackSilently() throws Exception {
        VideoRenderOptions options = defaultOptions();
        options.format = "mp5";

        String error = executeAndCaptureError(options);
        assertThat(error).contains("mp5").contains("--format");
    }

    @Test
    void everyDocumentedFormatIsAccepted() throws Exception {
        for (String format : java.util.List.of("mkv", "mp4", "avi", "mov", "webm")) {
            VideoRenderOptions options = defaultOptions();
            options.format = format;

            java.io.PrintStream originalErr = System.err;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            System.setErr(new PrintStream(captured));
            try {
                new VideoRenderEngine(options, null).execute();
            } catch (Exception expected) {
                // getting past validation is what this asserts
            } finally {
                System.setErr(originalErr);
            }
            assertThat(captured.toString()).as("format %s", format).doesNotContain("Unknown format");
        }
    }

    @Test
    void startTickEqualToEndTickIsAccepted() throws Exception {
        VideoRenderOptions options = defaultOptions();
        options.startTick = 100L;
        options.endTick = 100L;

        // Passes validation and fails later for want of a configuration, not because of the range.
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            new VideoRenderEngine(options, null).execute();
        } catch (Exception expected) {
            // reaching configuration loading is what this asserts
        } finally {
            System.setErr(originalErr);
        }
        assertThat(captured.toString()).doesNotContain("--start-tick");
    }
}
