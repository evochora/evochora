package org.evochora.cli.rendering;

import java.io.File;
import java.util.List;

import picocli.CommandLine.Option;

/**
 * Shared CLI options for video rendering commands.
 * <p>
 * This mixin is included in each frame renderer subcommand so that
 * all video-related options appear in the renderer's --help output.
 */
public class VideoRenderOptions {

    @Option(names = {"-c", "--config"}, description = "Path to custom configuration file.")
    public File configFile;

    @Option(names = "--run-id", description = "Simulation run ID to render. Defaults to the latest run.")
    public String runId;

    @Option(names = "--out", description = "Output filename.", defaultValue = "simulation.mkv")
    public File outputFile;

    @Option(names = "--fps", description = "Frames per second for the output video.", defaultValue = "60")
    public int fps;

    @Option(names = "--sampling-interval", description = "Render every Nth tick.", defaultValue = "1")
    public int samplingInterval;

    @Option(names = "--verbose", description = "Show detailed debug output from ffmpeg.")
    public boolean verbose;

    @Option(names = "--storage", description = "Storage resource name to use (default: tick-storage)", defaultValue = "tick-storage")
    public String storageName;

    @Option(names = "--start-tick", description = "Start rendering from this tick number (inclusive).")
    public Long startTick;

    @Option(names = "--end-tick", description = "Stop rendering at this tick number (inclusive).")
    public Long endTick;

    @Option(names = "--preset", description = "ffmpeg encoding preset (ultrafast/fast/medium/slow). Default: fast", defaultValue = "fast")
    public String preset;

    @Option(names = "--format", description = "Output video format: mkv/mp4/avi/mov/webm. Default: mkv", defaultValue = "mkv")
    public String format;

    @Option(names = "--threads", description = "Number of threads for parallel chunk rendering. Default: 1", defaultValue = "1")
    public int threadCount;

    @Option(names = "--overlay",
            description = "Overlays to apply (comma-separated): info",
            split = ",")
    public List<String> overlayNames;
}
