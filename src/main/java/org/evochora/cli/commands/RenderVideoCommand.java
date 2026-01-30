package org.evochora.cli.commands;

import java.util.concurrent.Callable;

import org.evochora.cli.rendering.frame.ExactFrameRenderer;
import org.evochora.cli.rendering.frame.MinimapFrameRenderer;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Container command for video rendering subcommands.
 * <p>
 * This command groups all video frame renderers as subcommands.
 * Each renderer (exact, minimap, etc.) handles its own CLI options
 * and rendering logic via the {@link org.evochora.cli.rendering.VideoRenderEngine}.
 * <p>
 * <strong>Usage:</strong>
 * <pre>
 *   evochora video exact --scale 4 --overlay info -o video.mkv
 *   evochora video minimap --scale 0.3 -o minimap.mkv
 * </pre>
 */
@Command(name = "video", description = "Renders a simulation run to a video file using ffmpeg. "
    + "Defaults to MKV for resilience against interruptions.",
    subcommands = {
        ExactFrameRenderer.class,
        MinimapFrameRenderer.class
    })
public class RenderVideoCommand implements Callable<Integer> {

    /**
     * Called when no subcommand is specified. Shows error and help.
     *
     * @return Exit code 1 to indicate error.
     */
    @Override
    public Integer call() {
        System.err.println("Error: No renderer specified. Use: video exact, video minimap, etc.");
        CommandLine.usage(this, System.out);
        return 1;
    }
}
