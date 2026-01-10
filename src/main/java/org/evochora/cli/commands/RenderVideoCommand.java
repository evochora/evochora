package org.evochora.cli.commands;

import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.evochora.cli.CliResourceFactory;
import org.evochora.cli.rendering.SimulationRenderer;
import org.evochora.cli.rendering.StatisticsBarRenderer;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.runtime.model.EnvironmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "video", description = "Renders a simulation run to a video file using ffmpeg. "
    + "Defaults to MKV for resilience against interruptions.")
public class RenderVideoCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(RenderVideoCommand.class);
    private static final String CONFIG_FILE_NAME = "evochora.conf";

    @Option(names = {"-c", "--config"}, description = "Path to custom configuration file.")
    private File configFile;

    @Option(names = "--run-id", description = "Simulation run ID to render. Defaults to the latest run.")
    private String runId;

    @Option(names = "--out", description = "Output filename.", defaultValue = "simulation.mkv")
    private File outputFile;

    @Option(names = "--fps", description = "Frames per second for the output video.", defaultValue = "60")
    private int fps;

    @Option(names = "--sampling-interval", description = "Render every Nth tick.", defaultValue = "1")
    private int samplingInterval;

    @Option(names = "--cell-size", description = "Size of each cell in pixels.", defaultValue = "4")
    private int cellSize;

    @Option(names = "--verbose", description = "Show detailed debug output from ffmpeg.")
    private boolean verbose;

    @Option(names = "--storage", description = "Storage resource name to use (default: tick-storage)", defaultValue = "tick-storage")
    private String storageName;

    @Option(names = "--start-tick", description = "Start rendering from this tick number (inclusive).")
    private Long startTick;

    @Option(names = "--end-tick", description = "Stop rendering at this tick number (inclusive).")
    private Long endTick;

    @Option(names = "--preset", description = "ffmpeg encoding preset (ultrafast/fast/medium/slow). Default: fast", defaultValue = "fast")
    private String preset;

    @Option(names = "--format", description = "Output video format: mkv/mp4/avi/mov/webm. Default: mkv", defaultValue = "mkv")
    private String format;

    @Option(names = "--overlay-tick", description = "Show tick number overlay in video.")
    private boolean overlayTick;

    @Option(names = "--overlay-time", description = "Show timestamp overlay in video.")
    private boolean overlayTime;

    @Option(names = "--overlay-run-id", description = "Show run ID overlay in video.")
    private boolean overlayRunId;

    @Option(names = "--overlay-position", description = "Overlay position (top-left/top-right/bottom-left/bottom-right). Default: top-left", defaultValue = "top-left")
    private String overlayPosition;

    @Option(names = "--overlay-font-size", description = "Overlay font size in pixels. Default: 24", defaultValue = "24")
    private int overlayFontSize;

    @Option(names = "--overlay-color", description = "Overlay text color (e.g., white, yellow, #FF0000). Default: white", defaultValue = "white")
    private String overlayColor;

    @Option(names = "--threads", description = "Number of threads for parallel frame rendering. Default: 1 (no parallelism)", defaultValue = "1")
    private int threadCount;

    @Option(names = "--overlay-stats", description = "Show organism statistics bar on the right side of the video.")
    private boolean overlayStats;

    /**
     * Combines a simulation frame with a statistics bar on the right side.
     *
     * @param simulationFrame The simulation frame as BGRA bytes (baseWidth × height).
     * @param statsBar The statistics bar as RGB int array (statsBarWidth × height).
     * @param baseWidth Width of the simulation frame.
     * @param height Height of both frames.
     * @param statsBarWidth Width of the statistics bar.
     * @return Combined frame as BGRA bytes (baseWidth + statsBarWidth) × height.
     */
    private byte[] combineFrameWithStatsBar(byte[] simulationFrame, int[] statsBar, 
                                             int baseWidth, int height, int statsBarWidth) {
        int combinedWidth = baseWidth + statsBarWidth;
        byte[] combinedFrame = new byte[combinedWidth * height * 4]; // BGRA = 4 bytes per pixel
        
        // Copy simulation frame (left side)
        for (int y = 0; y < height; y++) {
            int srcOffset = y * baseWidth * 4;
            int dstOffset = y * combinedWidth * 4;
            System.arraycopy(simulationFrame, srcOffset, combinedFrame, dstOffset, baseWidth * 4);
        }
        
        // Copy statistics bar (right side) - convert RGB to BGRA
        for (int y = 0; y < height; y++) {
            int barIndex = y * statsBarWidth;
            int dstOffset = y * combinedWidth * 4 + baseWidth * 4;
            for (int x = 0; x < statsBarWidth; x++) {
                int rgb = statsBar[barIndex + x];
                combinedFrame[dstOffset++] = (byte) (rgb & 0xFF);         // B
                combinedFrame[dstOffset++] = (byte) ((rgb >> 8) & 0xFF);  // G
                combinedFrame[dstOffset++] = (byte) ((rgb >> 16) & 0xFF); // R
                combinedFrame[dstOffset++] = (byte) 255;                  // A
            }
        }
        
        return combinedFrame;
    }

    /**
     * Formats milliseconds into a human-readable time string (HH:MM:SS or MM:SS).
     */
    private String formatTime(long milliseconds) {
        if (milliseconds < 0) return "?";
        long seconds = milliseconds / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, secs);
        } else {
            return String.format("%d:%02d", minutes, secs);
        }
    }

    @Override
    public Integer call() throws Exception {
        // Normalize output file path: expand ~ to home directory
        String outputPath = outputFile.getPath();
        if (outputPath.startsWith("~/") || outputPath.equals("~")) {
            String homeDir = System.getProperty("user.home");
            outputPath = outputPath.replaceFirst("^~", homeDir);
            outputFile = new File(outputPath);
        } else if (!outputFile.isAbsolute()) {
            // Relative path: resolve relative to current working directory
            outputFile = outputFile.getAbsoluteFile();
        }
        // Absolute paths are used as-is
        
        // Load config using a robust, prioritized mechanism
        Config config;
        try {
            if (this.configFile != null) {
                if (!this.configFile.exists()) {
                    System.err.println("Configuration file specified via --config was not found: " + this.configFile.getAbsolutePath());
                    return 1;
                }
                System.out.println("Using specified configuration file: " + this.configFile.getAbsolutePath());
                config = ConfigFactory.systemProperties()
                    .withFallback(ConfigFactory.systemEnvironment())
                    .withFallback(ConfigFactory.parseFile(this.configFile))
                    .withFallback(ConfigFactory.load())
                    .resolve();
            } else {
                final File defaultConfFile = new File(CONFIG_FILE_NAME);
                if (defaultConfFile.exists()) {
                    System.out.println("Using configuration file found in current directory: " + defaultConfFile.getAbsolutePath());
                    config = ConfigFactory.systemProperties()
                        .withFallback(ConfigFactory.systemEnvironment())
                        .withFallback(ConfigFactory.parseFile(defaultConfFile))
                        .withFallback(ConfigFactory.load())
                        .resolve();
                } else {
                    System.out.println("Warning: No 'evochora.conf' found in current directory. Using default configuration from classpath.");
                    config = ConfigFactory.systemProperties()
                        .withFallback(ConfigFactory.systemEnvironment())
                        .withFallback(ConfigFactory.load())
                        .resolve();
                }
            }
        } catch (com.typesafe.config.ConfigException e) {
            System.err.println("Failed to load or parse configuration: " + e.getMessage());
            return 1;
        }

        // Build config path dynamically
        String storageConfigPath = "pipeline.resources." + storageName;

        if (!config.hasPath(storageConfigPath)) {
            System.err.println("Storage resource '" + storageName + "' is not configured in evochora.conf.");
            return 1;
        }
        Config storageConfig = config.getConfig(storageConfigPath);

        IBatchStorageRead storage = CliResourceFactory.create("cli-video-renderer-storage", IBatchStorageRead.class, storageConfig);
        
        // Always show which storage is being used
        System.out.println("Using storage resource: " + storageName + " (" + storage.getClass().getSimpleName() + ")");


        String targetRunId = runId;
        if (targetRunId == null) {
            System.out.println("No run-id specified, discovering the latest run...");
            List<String> runIds = storage.listRunIds(java.time.Instant.EPOCH);
            if (runIds.isEmpty()) {
                System.err.println("No simulation runs found in storage.");
                return 1;
            }
            targetRunId = runIds.get(runIds.size() - 1);
            System.out.println("Found latest run: " + targetRunId);
        }

        System.out.println("Reading metadata...");

        // Find metadata file path using the storage abstraction
        // This method is compression-transparent and works across all storage backends
        java.util.Optional<StoragePath> metadataPathOpt;
        try {
            metadataPathOpt = storage.findMetadataPath(targetRunId);
        } catch (java.io.IOException e) {
            System.err.println("Failed to search for metadata file: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }

        if (metadataPathOpt.isEmpty()) {
            System.err.println("Failed to find metadata file for run ID: " + targetRunId);
            System.err.println("Please ensure a simulation has been run and the data exists.");
            return 1;
        }

        StoragePath metadataPath = metadataPathOpt.get();
        log.info("Found metadata file at: {}", metadataPath.asString());

        SimulationMetadata metadata;
        try {
            metadata = storage.readMessage(metadataPath, SimulationMetadata.parser());
        } catch (java.io.IOException e) {
            System.err.println("Failed to read or parse metadata file: " + metadataPath.asString());
            e.printStackTrace();
            return 1;
        }

        EnvironmentProperties envProps = new EnvironmentProperties(
            metadata.getEnvironment().getShapeList().stream().mapToInt(Integer::intValue).toArray(),
            metadata.getEnvironment().getToroidalList().stream().allMatch(b -> b)
        );

        int baseWidth = envProps.getWorldShape()[0] * cellSize;
        int height = envProps.getWorldShape()[1] * cellSize;
        
        // Add statistics bar width if enabled
        int statsBarWidth = overlayStats ? 60 : 0;
        int width = baseWidth + statsBarWidth;

        // Apply tick range filtering if specified (needed for overlay calculations)
        long effectiveStartTick = startTick != null ? startTick : 0;
        long effectiveEndTick = endTick != null ? endTick : Long.MAX_VALUE;

        // Determine output format and filename extension
        outputPath = outputFile.getAbsolutePath();
        String outPathLower = outputPath.toLowerCase();
        String formatLower = format.toLowerCase();
        
        // 1. Get extension from output path, if it exists
        String currentExtension = "";
        int lastDot = outPathLower.lastIndexOf('.');
        if (lastDot > 0 && lastDot < outPathLower.length() - 1) {
            currentExtension = outPathLower.substring(lastDot + 1);
        }
        
        // 2. Decide the final format. `--format` parameter takes precedence.
        // If the current extension is valid, but doesn't match the format param,
        // we override the format.
        if (!currentExtension.equals(formatLower)) {
            format = formatLower; // Use the format from the --format option
            if (lastDot > 0) {
                // Replace existing extension
                outputPath = outputPath.substring(0, lastDot) + "." + format;
            } else {
                // Append extension
                outputPath = outputPath + "." + format;
            }
            outputFile = new File(outputPath);
        } else {
            // Extension from --out matches the format, or --format was not specified
            // and we infer it from the --out extension.
            format = currentExtension;
        }

        // Build ffmpeg command with quality and format options
        java.util.List<String> ffmpegArgs = new java.util.ArrayList<>();
        ffmpegArgs.add("ffmpeg");
        ffmpegArgs.add("-y"); // Overwrite output file if it exists
        ffmpegArgs.add("-f"); ffmpegArgs.add("rawvideo");
        ffmpegArgs.add("-vcodec"); ffmpegArgs.add("rawvideo");
        ffmpegArgs.add("-s"); ffmpegArgs.add(width + "x" + height);
        ffmpegArgs.add("-pix_fmt"); ffmpegArgs.add("bgra");
        ffmpegArgs.add("-r"); ffmpegArgs.add(String.valueOf(fps));
        ffmpegArgs.add("-i"); ffmpegArgs.add("-"); // Input from stdin
        
        // Codec and quality settings based on format
        // https://trac.ffmpeg.org/wiki/Encode/H.264
        // https://trac.ffmpeg.org/wiki/Encode/VP9
        switch (format.toLowerCase()) {
            case "mp4":
                ffmpegArgs.add("-c:v"); ffmpegArgs.add("libx264");
                ffmpegArgs.add("-preset"); ffmpegArgs.add(preset);
                ffmpegArgs.add("-crf"); ffmpegArgs.add("18"); // Visually lossless
                ffmpegArgs.add("-movflags"); ffmpegArgs.add("+frag_keyframe+empty_moov");
                break;
            case "webm":
                ffmpegArgs.add("-c:v"); ffmpegArgs.add("libvpx-vp9");
                ffmpegArgs.add("-crf"); ffmpegArgs.add("10"); // Visually lossless
                ffmpegArgs.add("-b:v"); ffmpegArgs.add("0"); // Constant quality mode
                break;
            default: // mkv, avi, mov etc.
                ffmpegArgs.add("-c:v"); ffmpegArgs.add("libx264");
                ffmpegArgs.add("-preset"); ffmpegArgs.add(preset);
                break;
        }

        // Preset for encoding speed/quality tradeoff - now handled in switch
        //ffmpegArgs.add("-preset"); ffmpegArgs.add(preset);
        ffmpegArgs.add("-pix_fmt"); ffmpegArgs.add("yuv420p");
        
        // Build overlay filter if any overlay is requested
        if (overlayTick || overlayTime || overlayRunId) {
            // Calculate overlay position
            String xPos, yPos;
            switch (overlayPosition.toLowerCase()) {
                case "top-right":
                    xPos = "main_w-text_w-10";
                    yPos = "10";
                    break;
                case "bottom-left":
                    xPos = "10";
                    yPos = "main_h-text_h-10";
                    break;
                case "bottom-right":
                    xPos = "main_w-text_w-10";
                    yPos = "main_h-text_h-10";
                    break;
                default: // top-left
                    xPos = "10";
                    yPos = "10";
                    break;
            }
            
            // Build overlay text - use multiple drawtext filters for flexibility
            // Note: ffmpeg drawtext expressions use 'n' for frame number (0-indexed)
            java.util.List<String> filterParts = new java.util.ArrayList<>();
            int yOffset = 0;
            
            if (overlayRunId) {
                // Static text - escape single quotes by doubling them for ffmpeg
                String runText = "Run: " + targetRunId;
                String escapedRunText = runText.replace("'", "''");
                filterParts.add(String.format(
                    "drawtext=text='%s':x=%s:y=%d:fontsize=%d:fontcolor=%s",
                    escapedRunText, xPos, Integer.parseInt(yPos) + yOffset, overlayFontSize, overlayColor
                ));
                yOffset += overlayFontSize + 5; // Add spacing between lines
            }
            
            if (overlayTick) {
                // Calculate tick from frame number: tick = (n * samplingInterval) + firstRenderableTick
                // First renderable tick is first tick >= effectiveStartTick that matches samplingInterval
                long firstRenderableTick = ((effectiveStartTick / samplingInterval) * samplingInterval);
                if (firstRenderableTick < effectiveStartTick) {
                    firstRenderableTick += samplingInterval;
                }
                // Expression: (n * samplingInterval) + firstRenderableTick
                // According to ffmpeg docs: %{expr:...} should be used without quotes
                // Colons must be escaped: Tick: becomes Tick\: and %{expr: becomes %{expr\:
                // In Java, \\\\ produces a single \ in the output string
                String tickText = String.format("Tick\\\\:%%{expr\\\\:(n*%d)+%d}", samplingInterval, firstRenderableTick);
                filterParts.add(String.format(
                    "drawtext=text=%s:x=%s:y=%d:fontsize=%d:fontcolor=%s",
                    tickText, xPos, Integer.parseInt(yPos) + yOffset, overlayFontSize, overlayColor
                ));
                yOffset += overlayFontSize + 5;
            }
            
            if (overlayTime) {
                // Time in seconds: time = (n / fps) where n is frame number (0-indexed)
                // Format as "Time: X.XX s"
                // Expression syntax: n/fps for seconds, (n%fps)*100/fps for hundredths
                // According to ffmpeg docs: %{expr:...} should be used without quotes
                // Colons must be escaped: Time: becomes Time\: and %{expr: becomes %{expr\:
                // In Java, \\\\ produces a single \ in the output string
                String timeText = String.format("Time\\\\: %%{expr\\\\:n/%d}\\\\.%%{expr\\\\:((n%%%d)*100)/%d} s", fps, fps, fps);
                filterParts.add(String.format(
                    "drawtext=text=%s:x=%s:y=%d:fontsize=%d:fontcolor=%s",
                    timeText, xPos, Integer.parseInt(yPos) + yOffset, overlayFontSize, overlayColor
                ));
                yOffset += overlayFontSize + 5;
            }
            
            // Combine all filters with comma
            if (!filterParts.isEmpty() && !"webm".equalsIgnoreCase(format)) {
                String filterComplex = String.join(",", filterParts);
                // DEBUG: Print the exact filter string to diagnose parsing issues
                //System.err.println("DEBUG: ffmpeg filter string: " + filterComplex);
                ffmpegArgs.add("-vf");
                ffmpegArgs.add(filterComplex);
            }
        }
        
        ffmpegArgs.add(outputFile.getAbsolutePath());
        
        ProcessBuilder pb = new ProcessBuilder(ffmpegArgs);
        
        // Redirect stderr to stdout to capture error messages
        pb.redirectErrorStream(true);
        
        Process ffmpeg;
        try {
            ffmpeg = pb.start();
        } catch (Exception e) {
            System.err.println("Failed to start ffmpeg. Please ensure it is installed and in your PATH.");
            System.err.println("Error: " + e.getMessage());
            return 1;
        }

        // Start background thread to read ffmpeg output/errors in real-time
        // Collect last lines for error reporting even in non-verbose mode
        final Process finalFfmpeg = ffmpeg;
        final boolean showDebugOutput = verbose;
        java.util.concurrent.atomic.AtomicBoolean ffmpegDied = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<String> lastFfmpegOutput = new java.util.concurrent.atomic.AtomicReference<>("");
        Thread outputReader = new Thread(() -> {
            try (java.io.InputStream stream = finalFfmpeg.getInputStream()) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                StringBuilder outputBuffer = new StringBuilder();
                while ((bytesRead = stream.read(buffer)) != -1) {
                    String output = new String(buffer, 0, bytesRead);
                    if (showDebugOutput) {
                        System.err.print("[ffmpeg] " + output);
                    }
                    // Always collect last output for error reporting
                    outputBuffer.append(output);
                    // Keep only last 2000 characters to avoid memory issues
                    if (outputBuffer.length() > 2000) {
                        outputBuffer.delete(0, outputBuffer.length() - 2000);
                    }
                    lastFfmpegOutput.set(outputBuffer.toString());
                }
            } catch (Exception e) {
                if (!finalFfmpeg.isAlive()) {
                    ffmpegDied.set(true);
                    // Always show error if process died, even in non-verbose mode
                    System.err.println("\n[ffmpeg] Process ended unexpectedly");
                    String lastOutput = lastFfmpegOutput.get();
                    if (!lastOutput.isEmpty()) {
                        System.err.println("Last ffmpeg output:");
                        System.err.println(lastOutput);
                    }
                } else if (showDebugOutput) {
                    System.err.println("[ffmpeg] Error reading output: " + e.getMessage());
                }
            }
        }, "ffmpeg-output-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        // Calculate expected frame size
        int expectedFrameSizeBytes = width * height * 4; // BGRA = 4 bytes per pixel (includes stats bar if enabled)
        int expectedPixelCount = baseWidth * height; // Simulation frame only (without stats bar)
        
        if (startTick != null || endTick != null) {
            System.out.println(String.format("Tick range filter: %d-%d (inclusive)", 
                effectiveStartTick, effectiveEndTick == Long.MAX_VALUE ? "unlimited" : String.valueOf(effectiveEndTick)));
        }

        // Calculate total frames by finding max tick first - show progress during scan
        // Use tick filtering if specified for better performance
        System.out.print("Scanning batch files... ");
        long minTick = Long.MAX_VALUE;
        long maxTick = -1;
        int scannedBatches = 0;
        String continuationTokenForScan = null;
        do {
            // Use listBatchFiles with tick filtering if available and range is specified
            BatchFileListResult scanResult;
            if (startTick != null || endTick != null) {
                try {
                    // Use tick-filtered listing for better performance
                    scanResult = storage.listBatchFiles(targetRunId + "/raw/", continuationTokenForScan, 1000, 
                        effectiveStartTick, effectiveEndTick);
                } catch (Exception e) {
                    // Fallback to unfiltered listing if tick filtering not supported
                    scanResult = storage.listBatchFiles(targetRunId + "/raw/", continuationTokenForScan, 1000);
                }
            } else {
                scanResult = storage.listBatchFiles(targetRunId + "/raw/", continuationTokenForScan, 1000);
            }
            for (StoragePath path : scanResult.getFilenames()) {
                scannedBatches++;
                String filename = path.asString();
                // Extract end tick from batch filename: batch_STARTICK_ENDTICK.pb[.compression]
                int batchIdx = filename.lastIndexOf("/batch_");
                if (batchIdx >= 0) {
                    String batchName = filename.substring(batchIdx + 7); // Skip "/batch_"
                    int firstUnderscore = batchName.indexOf('_');
                    int dotPbIdx = batchName.indexOf(".pb");
                    if (firstUnderscore > 0 && dotPbIdx > firstUnderscore) {
                        try {
                            long startTick = Long.parseLong(batchName.substring(0, firstUnderscore));
                            long endTick = Long.parseLong(batchName.substring(firstUnderscore + 1, dotPbIdx));
                            if (startTick < minTick) minTick = startTick;
                            if (endTick > maxTick) maxTick = endTick;
                        } catch (NumberFormatException e) {
                            // Skip invalid filenames
                        }
                    }
                }
                // Show progress during scan (simple approach)
                if (scannedBatches % 100 == 0) {
                    System.out.print(String.format("\rScanning batch files... %d files, range: %s-%s", 
                        scannedBatches,
                        minTick < Long.MAX_VALUE ? String.valueOf(minTick) : "?",
                        maxTick >= 0 ? String.valueOf(maxTick) : "?"));
                    System.out.flush();
                }
            }
            continuationTokenForScan = scanResult.getNextContinuationToken();
        } while (continuationTokenForScan != null);
        System.out.println(String.format("\rScanning batch files... %d files found, tick range: %d-%d", 
            scannedBatches, minTick < Long.MAX_VALUE ? minTick : 0, maxTick));
        
        // Calculate total frames that will be rendered (considering tick range filter)
        long totalFrames = 0;
        if (maxTick >= 0) {
            // Count frames that match sampling interval within the tick range
            long actualMinTick = Math.max(minTick < Long.MAX_VALUE ? minTick : 0, effectiveStartTick);
            long actualMaxTick = Math.min(maxTick, effectiveEndTick);
            if (actualMaxTick >= actualMinTick) {
                // Count frames where tick % samplingInterval == 0 in the range
                // Start from first tick that matches sampling interval >= actualMinTick
                long firstRenderableTick = ((actualMinTick / samplingInterval) * samplingInterval);
                if (firstRenderableTick < actualMinTick) {
                    firstRenderableTick += samplingInterval;
                }
                // Count from firstRenderableTick to actualMaxTick
                if (firstRenderableTick <= actualMaxTick) {
                    totalFrames = ((actualMaxTick - firstRenderableTick) / samplingInterval) + 1;
                }
            }
        }
        
        // Video resolution: Environment size × cell size
        // Example: 800×600 environment with cellSize=4 → 3200×2400 video
        System.out.println(String.format("Video resolution: %dx%d (environment: %dx%d, cell size: %dpx)", 
            width, height, envProps.getWorldShape()[0], envProps.getWorldShape()[1], cellSize));
        long frameSizeMB = (long)width * height * 4 / (1024 * 1024);
        if (frameSizeMB > 100) {
            System.out.println(String.format("WARNING: Large frame size (%d MB/frame). This may cause ffmpeg to run out of memory.", frameSizeMB));
            System.out.println("Consider using a smaller --cell-size or --preset=ultrafast for better stability.");
        }
        if (totalFrames > 0) {
            System.out.println(String.format("Total frames to render: %d (tick range: %d-%d, sampling: every %d)", 
                totalFrames, minTick >= 0 ? minTick : 0, maxTick, samplingInterval));
        }
        
        // Find max organism_id in last tick for statistics bar scaling
        int maxOrganismId = 0;
        if (overlayStats && maxTick >= 0) {
            System.out.print("Finding max organism ID in last tick... ");
            try {
                long lastTickToCheck = Math.min(maxTick, effectiveEndTick);
                // Find the batch containing the last tick
                String continuationTokenForMax = null;
                StoragePath lastBatchPath = null;
                do {
                    BatchFileListResult maxResult = storage.listBatchFiles(targetRunId + "/raw/", continuationTokenForMax, 1000);
                    for (StoragePath path : maxResult.getFilenames()) {
                        String filename = path.asString();
                        int batchIdx = filename.lastIndexOf("/batch_");
                        if (batchIdx >= 0) {
                            String batchName = filename.substring(batchIdx + 7);
                            int firstUnderscore = batchName.indexOf('_');
                            int dotPbIdx = batchName.indexOf(".pb");
                            if (firstUnderscore > 0 && dotPbIdx > firstUnderscore) {
                                try {
                                    long batchEndTick = Long.parseLong(batchName.substring(firstUnderscore + 1, dotPbIdx));
                                    if (batchEndTick >= lastTickToCheck) {
                                        lastBatchPath = path;
                                    }
                                } catch (NumberFormatException e) {
                                    // Skip invalid filenames
                                }
                            }
                        }
                    }
                    continuationTokenForMax = maxResult.getNextContinuationToken();
                } while (continuationTokenForMax != null);
                
                if (lastBatchPath != null) {
                    List<TickData> lastBatch = storage.readBatch(lastBatchPath);
                    // Find the last tick in the batch that matches our criteria
                    for (int i = lastBatch.size() - 1; i >= 0; i--) {
                        TickData tick = lastBatch.get(i);
                        if (tick.getTickNumber() <= lastTickToCheck && 
                            tick.getTickNumber() % samplingInterval == 0) {
                            // Find max organism_id in this tick
                            for (var org : tick.getOrganismsList()) {
                                if (org.getOrganismId() > maxOrganismId) {
                                    maxOrganismId = org.getOrganismId();
                                }
                            }
                            break;
                        }
                    }
                }
                System.out.println(String.format("max organism ID: %d", maxOrganismId));
            } catch (Exception e) {
                System.err.println("Warning: Failed to find max organism ID: " + e.getMessage());
                System.err.println("Statistics bar will use dynamic scaling.");
                maxOrganismId = 0; // Will use dynamic scaling
            }
        }
        
        if (verbose) {
            log.info("Starting video rendering: {}x{} pixels, {} bytes per frame, {} fps", 
                width, height, expectedFrameSizeBytes, fps);
        }

        // Shutdown flag for graceful handling of Ctrl-C
        final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
        Thread shutdownHook = null;

        try (OutputStream ffmpegInput = ffmpeg.getOutputStream();
             WritableByteChannel channel = Channels.newChannel(ffmpegInput)) {

            // Create thread pool for parallel rendering if threadCount > 1
            ExecutorService executorService = null;
            if (threadCount > 1) {
                executorService = Executors.newFixedThreadPool(threadCount);
            }
            
            // Create renderer (thread-safe for rendering, but not for concurrent access)
            // For parallel rendering, each thread needs its own renderer instance
            SimulationRenderer renderer = new SimulationRenderer(envProps, cellSize);
            List<SimulationRenderer> renderers = new java.util.ArrayList<>();
            if (threadCount > 1) {
                for (int i = 0; i < threadCount; i++) {
                    renderers.add(new SimulationRenderer(envProps, cellSize));
                }
            }
            
            // Create statistics bar renderer if enabled
            StatisticsBarRenderer statsBarRenderer = overlayStats ? new StatisticsBarRenderer(statsBarWidth, height) : null;
            int currentMaxAlive = 0; // Track max alive for dynamic scaling if maxOrganismId not found
            
            String continuationToken = null;
            long processedFrames = 0;
            
            // Reuse ByteBuffer for RGB→BGRA conversion to avoid allocation overhead
            // For parallel rendering, each thread needs its own buffer
            // Note: expectedFrameSizeBytes already includes stats bar width if overlayStats is enabled
            ByteBuffer byteBuffer = ByteBuffer.allocate(expectedFrameSizeBytes);
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN); // BGRA format requires little-endian
            // For parallel rendering: queue to maintain frame order
            BlockingQueue<byte[]> frameQueue = threadCount > 1 ? new LinkedBlockingQueue<>() : null;
            BlockingQueue<Long> tickNumberQueue = threadCount > 1 ? new LinkedBlockingQueue<>() : null;
            BlockingQueue<Integer> aliveCountQueue = threadCount > 1 && overlayStats ? new LinkedBlockingQueue<>() : null;
            BlockingQueue<Integer> deadCountQueue = threadCount > 1 && overlayStats ? new LinkedBlockingQueue<>() : null;
            java.util.concurrent.atomic.AtomicLong framesWritten = threadCount > 1 ? new java.util.concurrent.atomic.AtomicLong(0) : null;
            
            // Writer thread for parallel rendering (maintains order)
            Thread writerThread = null;
            if (threadCount > 1) {
                final StatisticsBarRenderer finalStatsBarRenderer = statsBarRenderer;
                final int finalBaseWidth = baseWidth;
                final int finalStatsBarWidth = statsBarWidth;
                final int finalMaxOrganismId = maxOrganismId;
                final java.util.concurrent.atomic.AtomicInteger finalCurrentMaxAlive = new java.util.concurrent.atomic.AtomicInteger(currentMaxAlive);
                writerThread = new Thread(() -> {
                    try {
                        while (true) {
                            byte[] frameData = frameQueue.take(); // Blocks until frame available
                            if (frameData == null) break; // Poison pill
                            // Add statistics bar if enabled
                            byte[] finalFrameData = frameData;
                            if (overlayStats && finalStatsBarRenderer != null && aliveCountQueue != null && deadCountQueue != null) {
                                int alive = aliveCountQueue.take();
                                int dead = deadCountQueue.take();
                                int effectiveMax = finalMaxOrganismId > 0 ? finalMaxOrganismId : 
                                    Math.max(finalCurrentMaxAlive.get(), alive + dead);
                                if (alive > finalCurrentMaxAlive.get()) {
                                    finalCurrentMaxAlive.set(alive);
                                }
                                int[] statsBar = finalStatsBarRenderer.render(alive, dead, effectiveMax);
                                finalFrameData = combineFrameWithStatsBar(frameData, statsBar, finalBaseWidth, height, finalStatsBarWidth);
                            }
                            
                            // Write frame in order
                            ByteBuffer writeBuffer = ByteBuffer.wrap(finalFrameData);
                            writeBuffer.order(ByteOrder.LITTLE_ENDIAN);
                            channel.write(writeBuffer);
                            
                            // Update progress counter
                            framesWritten.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        System.err.println("Writer thread error: " + e.getMessage());
                    }
                }, "frame-writer");
                writerThread.start();
            }
            
            // Register shutdown hook for graceful Ctrl-C handling
            // Note: Variables must be final or effectively final for lambda
            final ExecutorService finalExecutorService = executorService;
            final Thread finalWriterThread = writerThread;
            final BlockingQueue<byte[]> finalFrameQueue = frameQueue;
            
            shutdownHook = new Thread(() -> {
                System.out.println("\n\nShutdown requested (Ctrl-C)... Finishing current frames...");
                shutdownRequested.set(true);
                
                // Wait for parallel rendering tasks to complete
                if (finalExecutorService != null) {
                    finalExecutorService.shutdown();
                    try {
                        if (!finalExecutorService.awaitTermination(30, TimeUnit.SECONDS)) {
                            System.err.println("Warning: Rendering tasks did not complete within 30 seconds. Forcing shutdown.");
                            finalExecutorService.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        finalExecutorService.shutdownNow();
                    }
                }
                
                // Signal writer thread to finish and wait for it
                if (finalWriterThread != null && finalFrameQueue != null) {
                    try {
                        finalFrameQueue.put(new byte[0]); // Poison pill (empty array signals end)
                        finalWriterThread.join(10000); // Wait up to 10 seconds
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "video-shutdown-hook");
            
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            
            // Track timing for ETA calculation
            long startTime = System.currentTimeMillis();
            long lastProgressUpdate = startTime;
            
            do {
                // Use tick filtering if specified for better performance
                BatchFileListResult result;
                if (startTick != null || endTick != null) {
                    try {
                        result = storage.listBatchFiles(targetRunId + "/raw/", continuationToken, 100,
                            effectiveStartTick, effectiveEndTick);
                    } catch (Exception e) {
                        // Fallback to unfiltered listing
                        result = storage.listBatchFiles(targetRunId + "/raw/", continuationToken, 100);
                    }
                } else {
                    result = storage.listBatchFiles(targetRunId + "/raw/", continuationToken, 100);
                }
                
                for (StoragePath path : result.getFilenames()) {
                    if (shutdownRequested.get()) {
                        System.out.println("\nShutdown requested. Stopping new frame processing...");
                        break; // Break out of file loop
                    }
                    
                    List<TickData> batch = storage.readBatch(path);
                    for (TickData tick : batch) {
                        long tickNumber = tick.getTickNumber();
                        
                        // Check for shutdown before processing each tick
                        if (shutdownRequested.get()) {
                            break; // Break out of tick loop, but process pending frames
                        }
                        
                        // Apply tick range filter
                        if (tickNumber < effectiveStartTick || tickNumber > effectiveEndTick) {
                            continue;
                        }
                        
                        // Apply sampling interval filter
                        if (tickNumber % samplingInterval != 0) {
                            continue;
                        }
                        
                        // Check if ffmpeg is still alive before writing
                        if (!ffmpeg.isAlive() || ffmpegDied.get()) {
                            int exitCode = ffmpeg.isAlive() ? 0 : ffmpeg.exitValue();
                            System.err.println(String.format(
                                "\nERROR: ffmpeg process died unexpectedly with exit code: %d (tick: %d)", 
                                exitCode, tickNumber));
                            if (executorService != null) executorService.shutdown();
                            if (writerThread != null) {
                                frameQueue.offer(null); // Poison pill
                                try { writerThread.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            }
                            return 1;
                        }
                        
                        // Collect statistics for statistics bar
                        int aliveCount = 0;
                        int deadCount = 0;
                        int currentMaxOrganismId = 0;
                        if (overlayStats && statsBarRenderer != null) {
                            for (var org : tick.getOrganismsList()) {
                                if (org.getIsDead()) {
                                    deadCount++;
                                } else {
                                    aliveCount++;
                                }
                                if (org.getOrganismId() > currentMaxOrganismId) {
                                    currentMaxOrganismId = org.getOrganismId();
                                }
                            }
                            // Update dynamic max if needed
                            if (maxOrganismId == 0 && aliveCount > currentMaxAlive) {
                                currentMaxAlive = aliveCount;
                            }
                        }
                        
                        // Render frame (parallel if threadCount > 1, sequential otherwise)
                        if (threadCount > 1 && executorService != null) {
                            // Parallel rendering: render in background, write via queue
                            final TickData tickFinal = tick;
                            final long tickNumberFinal = tickNumber;
                            final int rendererIndex = (int) (processedFrames % threadCount);
                            final SimulationRenderer rendererToUse = renderers.get(rendererIndex);
                            
                            final int finalAliveCount = aliveCount;
                            final int finalDeadCount = deadCount;
                            CompletableFuture.supplyAsync(() -> {
                                // Render frame in parallel
                                int[] pixelData = rendererToUse.render(tickFinal);
                                
                                // Convert RGB→BGRA (simulation frame only, stats bar added in writer thread)
                                byte[] frameBytes = new byte[baseWidth * height * 4];
                                int bufferIndex = 0;
                                for (int i = 0; i < pixelData.length; i++) {
                                    int rgb = pixelData[i];
                                    frameBytes[bufferIndex++] = (byte) (rgb & 0xFF);         // B
                                    frameBytes[bufferIndex++] = (byte) ((rgb >> 8) & 0xFF);  // G
                                    frameBytes[bufferIndex++] = (byte) ((rgb >> 16) & 0xFF); // R
                                    frameBytes[bufferIndex++] = (byte) 255;                  // A
                                }
                                return frameBytes;
                            }, executorService).thenAccept(frameBytes -> {
                                try {
                                    frameQueue.put(frameBytes);
                                    tickNumberQueue.put(tickNumberFinal);
                                    if (overlayStats && aliveCountQueue != null && deadCountQueue != null) {
                                        aliveCountQueue.put(finalAliveCount);
                                        deadCountQueue.put(finalDeadCount);
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
                            
                            // Don't wait - writer thread handles ordering
                            processedFrames++;
                        } else {
                            // Sequential rendering (original implementation)
                            int[] pixelData = renderer.render(tick);
                            
                            // Validate frame size (only in verbose mode)
                            if (verbose && pixelData.length != expectedPixelCount) {
                                System.err.println(String.format(
                                    "\nERROR: Frame size mismatch! Expected %d pixels, got %d (tick: %d)", 
                                    expectedPixelCount, pixelData.length, tickNumber));
                                if (executorService != null) executorService.shutdown();
                                return 1;
                            }
                                
                            // Convert RGB ints to BGRA bytes - optimized with direct array access
                            // Reuse byteBuffer (allocated once above) for better performance
                            byte[] simulationFrameBytes = new byte[baseWidth * height * 4];
                            int bufferIndex = 0;
                            for (int i = 0; i < pixelData.length; i++) {
                                int rgb = pixelData[i];
                                // Extract RGB components (BufferedImage.TYPE_INT_RGB: 0x00RRGGBB)
                                // Write as BGRA (little-endian: B, G, R, A) using direct array access
                                simulationFrameBytes[bufferIndex++] = (byte) (rgb & 0xFF);         // B
                                simulationFrameBytes[bufferIndex++] = (byte) ((rgb >> 8) & 0xFF);  // G
                                simulationFrameBytes[bufferIndex++] = (byte) ((rgb >> 16) & 0xFF); // R
                                simulationFrameBytes[bufferIndex++] = (byte) 255;                  // A (fully opaque)
                            }
                            
                            // Add statistics bar if enabled
                            byte[] finalFrameBytes = simulationFrameBytes;
                            if (overlayStats && statsBarRenderer != null) {
                                int effectiveMax = maxOrganismId > 0 ? maxOrganismId : Math.max(currentMaxAlive, currentMaxOrganismId);
                                int[] statsBar = statsBarRenderer.render(aliveCount, deadCount, effectiveMax);
                                finalFrameBytes = combineFrameWithStatsBar(simulationFrameBytes, statsBar, baseWidth, height, statsBarWidth);
                            }
                            
                            byteBuffer = ByteBuffer.wrap(finalFrameBytes);
                            byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
                            byteBuffer.position(0);
                            byteBuffer.limit(finalFrameBytes.length);
                                
                            // Validate buffer state (only in verbose mode)
                            if (verbose && bufferIndex != expectedFrameSizeBytes) {
                                System.err.println(String.format(
                                    "\nERROR: Buffer size mismatch! Expected %d bytes, got %d (tick: %d)", 
                                    expectedFrameSizeBytes, bufferIndex, tickNumber));
                                if (executorService != null) executorService.shutdown();
                                return 1;
                            }
                            if (verbose && byteBuffer.remaining() != expectedFrameSizeBytes) {
                                System.err.println(String.format(
                                    "\nERROR: Buffer size mismatch! Expected %d bytes, got %d (tick: %d)", 
                                    expectedFrameSizeBytes, byteBuffer.remaining(), tickNumber));
                                if (executorService != null) executorService.shutdown();
                                return 1;
                            }
                                
                            // Write frame and validate bytes written (only in verbose mode)
                            try {
                                int bytesWritten = channel.write(byteBuffer);
                                if (verbose && bytesWritten != expectedFrameSizeBytes) {
                                    System.err.println(String.format(
                                        "\nERROR: Partial write! Expected %d bytes, wrote %d (tick: %d)", 
                                        expectedFrameSizeBytes, bytesWritten, tickNumber));
                                    if (executorService != null) executorService.shutdown();
                                    return 1;
                                }
                            } catch (java.io.IOException e) {
                                // Check if ffmpeg died during write
                                if (!ffmpeg.isAlive() || ffmpegDied.get()) {
                                    int exitCode = ffmpeg.isAlive() ? 0 : ffmpeg.exitValue();
                                    System.err.println(String.format(
                                        "\nERROR: ffmpeg process died during write (exit code: %d, tick: %d). Error: %s", 
                                        exitCode, tickNumber, e.getMessage()));
                                    // Try to read ffmpeg error output
                                    String lastOutput = lastFfmpegOutput.get();
                                    if (!lastOutput.isEmpty()) {
                                        System.err.println("Last ffmpeg output:");
                                        System.err.println(lastOutput);
                                    } else {
                                        // Fallback: try to read from stream
                                        try (java.io.InputStream errorStream = ffmpeg.getInputStream()) {
                                            byte[] errorBytes = errorStream.readAllBytes();
                                            if (errorBytes.length > 0) {
                                                System.err.println("ffmpeg output/errors:");
                                                System.err.println(new String(errorBytes));
                                            }
                                        } catch (Exception readEx) {
                                            // Ignore - process may be dead
                                        }
                                    }
                                } else {
                                    System.err.println(String.format(
                                        "\nERROR: Failed to write frame (tick: %d). Error: %s", 
                                        tickNumber, e.getMessage()));
                                }
                                if (executorService != null) executorService.shutdown();
                                if (writerThread != null) {
                                    frameQueue.offer(null); // Poison pill
                                    try { writerThread.join(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                }
                                return 1;
                            }
                            
                            processedFrames++;
                        }
                        
                        // Calculate progress, ETA, and throughput (for both sequential and parallel)
                        long currentProcessedFrames = threadCount > 1 && framesWritten != null ? 
                            framesWritten.get() : processedFrames;
                        long currentTime = System.currentTimeMillis();
                        long elapsedTime = currentTime - startTime;
                        double fpsRendered = currentProcessedFrames > 0 ? (currentProcessedFrames * 1000.0) / elapsedTime : 0;
                        long estimatedTotalTime = totalFrames > 0 && fpsRendered > 0 ? 
                            (long) ((totalFrames * 1000.0) / fpsRendered) : 0;
                        long remainingTime = estimatedTotalTime > 0 ? estimatedTotalTime - elapsedTime : 0;
                        
                        // Update progress display (every frame for first 10, then every 10th frame, or every second)
                        boolean shouldUpdate = currentProcessedFrames <= 10 || 
                            currentProcessedFrames % 10 == 0 || 
                            (currentTime - lastProgressUpdate) >= 1000; // At least every second
                        
                        if (shouldUpdate) {
                            lastProgressUpdate = currentTime;
                            
                            // Get latest tick number (for parallel: approximate)
                            long displayTickNumber = tickNumber;
                            
                            if (totalFrames > 0) {
                                int percentage = (int) ((currentProcessedFrames * 100) / totalFrames);
                                int barWidth = 40;
                                int filled = (int) ((currentProcessedFrames * barWidth) / totalFrames);
                                StringBuilder bar = new StringBuilder("[");
                                for (int i = 0; i < barWidth; i++) {
                                    bar.append(i < filled ? "=" : " ");
                                }
                                bar.append("]");
                                
                                // Format remaining time
                                String remainingTimeStr = formatTime(remainingTime);
                                String elapsedTimeStr = formatTime(elapsedTime);
                                
                                System.out.print(String.format("\r%s %d%% | Frame %d/%d (tick: %d) | %.1f fps | Elapsed: %s | ETA: %s", 
                                    bar.toString(), percentage, currentProcessedFrames, totalFrames, 
                                    displayTickNumber, fpsRendered, elapsedTimeStr, remainingTimeStr));
                            } else {
                                // Fallback if totalFrames unknown
                                String elapsedTimeStr = formatTime(elapsedTime);
                                System.out.print(String.format("\rRendering... Frame %d (tick: %d) | %.1f fps | Elapsed: %s", 
                                    currentProcessedFrames, displayTickNumber, fpsRendered, elapsedTimeStr));
                            }
                            System.out.flush(); // Ensure output is visible immediately
                        }
                    }
                    
                    if (shutdownRequested.get()) {
                        break; // Break out of batch loop
                    }
                }
                
                if (shutdownRequested.get()) {
                    break; // Break out of do-while loop
                }
                
                continuationToken = result.getNextContinuationToken();
            } while (continuationToken != null && !shutdownRequested.get());
            
            // For parallel rendering: wait for all frames to be written
            if (shutdownRequested.get()) {
                System.out.println("\nProcessing remaining frames from parallel rendering...");
            }
            
            if (threadCount > 1 && executorService != null) {
                // Wait for all rendering tasks to complete
                executorService.shutdown();
                try {
                    // Wait indefinitely for all tasks to finish after shutdown has been called.
                    // This will only be interrupted by an InterruptedException.
                    executorService.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    System.err.println("Rendering was interrupted. Forcing shutdown of rendering tasks...");
                    Thread.currentThread().interrupt();
                }
                
                // Signal writer thread to stop by sending the poison pill
                if (writerThread != null && frameQueue != null) {
                    try {
                        frameQueue.put(null); // Poison pill
                        writerThread.join(10000); // Wait up to 10 seconds for writer to finish
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            // Remove shutdown hook if we completed normally
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException e) {
                    // Shutdown hook is already running - ignore
                }
            }

            if (shutdownRequested.get()) {
                System.out.println("\nShutdown complete. Closing input stream...");
            } else {
                System.out.println("\nFinished rendering. Closing input stream...");
            }
            // Close the stream explicitly to signal end of input to ffmpeg
        } catch (Exception e) {
            // Cleanup on error
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // Ignore if hook is already running
                }
            }
            throw e;
        }

        // Wait for output reader to finish (with timeout)
        try {
            outputReader.join(1000); // Wait max 1 second
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int exitCode = ffmpeg.waitFor();
        if (exitCode == 0) {
            System.out.println("Video successfully created: " + outputFile.getAbsolutePath());
        } else {
            System.err.println("ffmpeg failed with exit code " + exitCode);
            // Read output/error stream (redirected to stdout)
            try (java.io.InputStream errorStream = ffmpeg.getInputStream()) {
                byte[] errorBytes = errorStream.readAllBytes();
                if (errorBytes.length > 0) {
                    System.err.println("ffmpeg output/errors:");
            System.err.println(new String(errorBytes));
                }
            }
            return 1;
        }

        return 0;
    }
}
