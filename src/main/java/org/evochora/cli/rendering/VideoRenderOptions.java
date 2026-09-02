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

    /**
     * Configuration file to read instead of the one found by the standard discovery cascade.
     * <p>
     * When {@code null}, the cascade applies: the {@code config.file} system property, then
     * {@code config/evochora.conf} below the working directory, then the same path below the
     * installation directory, and finally the defaults bundled with the application.
     */
    @Option(names = {"-c", "--config"}, description = "Path to custom configuration file.")
    public File configFile;

    /**
     * Identifier of the simulation run whose recorded ticks are rendered.
     * <p>
     * When {@code null}, the storage is queried for all known run identifiers and the last one
     * is used; rendering stops with a failure exit code if the storage holds no run at all.
     */
    @Option(names = "--run-id", description = "Simulation run ID to render. Defaults to the latest run.")
    public String runId;

    /**
     * File the encoded video is written to, {@code simulation.mkv} by default; a relative path
     * is resolved against the working directory and a leading {@code ~} is expanded to the
     * user's home directory.
     * <p>
     * An existing file at that path is overwritten without a prompt.
     */
    @Option(names = "--out", description = "Output filename.", defaultValue = "simulation.mkv")
    public File outputFile;

    /**
     * Playback rate of the produced video in frames per second, 60 by default.
     * <p>
     * Every rendered tick becomes one frame, so with a sampling interval of {@code n} one
     * second of video spans {@code fps * n} simulation ticks.
     */
    @Option(names = "--fps", description = "Frames per second for the output video.", defaultValue = "60")
    public int fps;

    /**
     * Renders only those ticks whose tick number is a multiple of this value, so 1, the default,
     * renders every tick. Only values of 1 or greater are supported.
     * <p>
     * Above 1 the engine skips batch files that contain no matching tick and reconstructs each
     * sampled tick from the nearest preceding accumulated delta rather than replaying every
     * intermediate tick.
     */
    @Option(names = "--sampling-interval", description = "Render every Nth tick.", defaultValue = "1")
    public int samplingInterval;

    /**
     * Prints the output of the ffmpeg process to standard error while encoding.
     * <p>
     * That output is always drained so the process cannot block on a full pipe; this flag only
     * decides whether it is shown.
     */
    @Option(names = "--verbose", description = "Show detailed debug output from ffmpeg.")
    public boolean verbose;

    /**
     * Name of the storage resource below {@code pipeline.resources} in the configuration that
     * supplies the recorded ticks, {@code tick-storage} by default.
     * <p>
     * Rendering stops with a failure exit code if the configuration contains no resource of
     * that name.
     */
    @Option(names = "--storage", description = "Storage resource name to use (default: tick-storage)", defaultValue = "tick-storage")
    public String storageName;

    /**
     * First simulation tick to render, inclusive.
     * <p>
     * When {@code null}, rendering begins at tick 0 and therefore at the earliest tick the
     * storage holds for the run.
     */
    @Option(names = "--start-tick", description = "Start rendering from this tick number (inclusive).")
    public Long startTick;

    /**
     * Last simulation tick to render, inclusive.
     * <p>
     * When {@code null}, rendering continues up to the latest tick the storage holds for the
     * run.
     */
    @Option(names = "--end-tick", description = "Stop rendering at this tick number (inclusive).")
    public Long endTick;

    /**
     * Encoder preset passed through unchanged to ffmpeg's {@code -preset} argument for the
     * libx264 formats, {@code fast} by default.
     * <p>
     * The value is not checked here, so an unknown preset surfaces as an ffmpeg failure. WebM
     * output is encoded with libvpx-vp9 and ignores this option.
     */
    @Option(names = "--preset", description = "ffmpeg encoding preset (ultrafast/fast/medium/slow). Default: fast", defaultValue = "fast")
    public String preset;

    /**
     * Selects the encoder settings, {@code mkv} by default.
     * <p>
     * The value is compared case-insensitively: {@code mp4} encodes with libx264 and adds the
     * fragmented-MP4 flags, {@code webm} encodes with libvpx-vp9, and every other value
     * encodes with plain libx264 settings. The value is not checked against a list of known
     * formats, and it decides only the encoder arguments; ffmpeg derives the container from
     * the name of {@link #outputFile}, so the two should agree.
     */
    @Option(names = "--format", description = "Output video format: mkv/mp4/avi/mov/webm. Default: mkv", defaultValue = "mkv")
    public String format;

    /**
     * Quality setting for the libx264 formats, handed to ffmpeg's {@code -crf} argument, where 0
     * is lossless and 51 the worst quality; 18 by default.
     * <p>
     * WebM output ignores this value and is always encoded at a constant rate factor of 10
     * with unconstrained bitrate.
     */
    @Option(names = "--crf", description = "H.264 constant rate factor for mkv/mp4/avi/mov output "
            + "(0 = lossless, 51 = worst; default: ${DEFAULT-VALUE}). WebM uses its own fixed quality.",
            defaultValue = "18")
    public int crf;

    /**
     * Number of worker threads rendering chunks in parallel, 1 by default.
     * <p>
     * A value of 1 selects the single-threaded path, which derives each frame incrementally
     * from the previous one. Higher values give every thread its own renderer instance, and
     * the number of chunks in flight is additionally capped so that the buffered frames stay
     * within half of the maximum heap; the effective thread count is reported on startup.
     */
    @Option(names = "--threads", description = "Number of threads for parallel chunk rendering. Default: 1", defaultValue = "1")
    public int threadCount;

    /**
     * Names of the overlays drawn on top of every frame, given as a comma-separated list.
     * <p>
     * Each name is resolved to the class {@code <Name>OverlayRenderer} in the overlay package,
     * with the first letter upper-cased and the rest lower-cased. A name that cannot be
     * resolved or instantiated produces a warning on standard error and is skipped, so
     * rendering continues without that overlay. {@code null} or an empty list means no
     * overlay is drawn.
     */
    @Option(names = "--overlay",
            description = "Overlays to apply (comma-separated): info, diversity, graph, logo",
            split = ",")
    public List<String> overlayNames;
}
