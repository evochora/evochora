package org.evochora.cli.commands;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.cli.CliResourceFactory;
import org.evochora.cli.rendering.SimulationRenderer;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.runtime.model.EnvironmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Command(name = "video", description = "Renders a simulation run to an MP4 video file using ffmpeg.")
public class RenderVideoCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(RenderVideoCommand.class);
    private static final String CONFIG_FILE_NAME = "evochora.conf";

    @Option(names = "--run-id", description = "Simulation run ID to render. Defaults to the latest run.")
    private String runId;

    @Option(names = "--out", description = "Output filename.", defaultValue = "simulation.mp4")
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

    @Option(names = "--format", description = "Output video format (mp4/avi/mov/webm). Default: mp4", defaultValue = "mp4")
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
        
        // Load config directly (without triggering CommandLineInterface.initialize() which shows logs)
        final File confFile = new File(CONFIG_FILE_NAME);
        Config config;
        try {
            if (confFile.exists()) {
                config = ConfigFactory.parseFile(confFile).withFallback(ConfigFactory.load()).resolve();
            } else {
                config = ConfigFactory.load().resolve();
            }
        } catch (com.typesafe.config.ConfigException e) {
            System.err.println("Failed to load configuration from " + confFile.getAbsolutePath() + ": " + e.getMessage());
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

        int width = envProps.getWorldShape()[0] * cellSize;
        int height = envProps.getWorldShape()[1] * cellSize;

        // Apply tick range filtering if specified (needed for overlay calculations)
        long effectiveStartTick = startTick != null ? startTick : 0;
        long effectiveEndTick = endTick != null ? endTick : Long.MAX_VALUE;

        // Determine output file extension based on format
        outputPath = outputFile.getAbsolutePath();
        if (!outputPath.toLowerCase().endsWith("." + format.toLowerCase())) {
            // Replace or append extension based on format
            int lastDot = outputPath.lastIndexOf('.');
            if (lastDot > 0) {
                outputPath = outputPath.substring(0, lastDot) + "." + format.toLowerCase();
            } else {
                outputPath = outputPath + "." + format.toLowerCase();
            }
            outputFile = new File(outputPath);
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
        
        // Video codec based on format
        if ("webm".equalsIgnoreCase(format)) {
            ffmpegArgs.add("-c:v"); ffmpegArgs.add("libvpx-vp9");
        } else {
            ffmpegArgs.add("-c:v"); ffmpegArgs.add("libx264");
        }
        
        // Preset for encoding speed/quality tradeoff
        ffmpegArgs.add("-preset"); ffmpegArgs.add(preset);
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
                // Static text - escape single quotes in run ID
                String escapedRunId = targetRunId.replace("'", "'\\''");
                filterParts.add(String.format(
                    "drawtext=text='Run: %s':x=%s:y=%d:fontsize=%d:fontcolor=%s",
                    escapedRunId, xPos, Integer.parseInt(yPos) + yOffset, overlayFontSize, overlayColor
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
                filterParts.add(String.format(
                    "drawtext=text='Tick: %%{expr:(n*%d)+%d}':x=%s:y=%d:fontsize=%d:fontcolor=%s",
                    samplingInterval, firstRenderableTick, xPos, Integer.parseInt(yPos) + yOffset, overlayFontSize, overlayColor
                ));
                yOffset += overlayFontSize + 5;
            }
            
            if (overlayTime) {
                // Time in seconds: time = (n / fps) where n is frame number (0-indexed)
                // Format as "Time: X.XX s"
                // Expression syntax: n/fps for seconds, (n%fps)*100/fps for hundredths
                filterParts.add(String.format(
                    "drawtext=text='Time: %%{expr:n/%d}\\\\.%%{expr:((n%%%d)*100)/%d} s':x=%s:y=%d:fontsize=%d:fontcolor=%s",
                    fps, fps, fps, xPos, Integer.parseInt(yPos) + yOffset, overlayFontSize, overlayColor
                ));
                yOffset += overlayFontSize + 5;
            }
            
            // Combine all filters with comma
            if (!filterParts.isEmpty() && !"webm".equalsIgnoreCase(format)) {
                String filterComplex = String.join(",", filterParts);
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

        // Start background thread to read ffmpeg output/errors in real-time (only if verbose)
        final Process finalFfmpeg = ffmpeg;
        final boolean showDebugOutput = verbose;
        java.util.concurrent.atomic.AtomicBoolean ffmpegDied = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread outputReader = new Thread(() -> {
            try (java.io.InputStream stream = finalFfmpeg.getInputStream()) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = stream.read(buffer)) != -1) {
                    if (showDebugOutput) {
                        String output = new String(buffer, 0, bytesRead);
                        System.err.print("[ffmpeg] " + output);
                    }
                }
            } catch (Exception e) {
                if (!finalFfmpeg.isAlive()) {
                    ffmpegDied.set(true);
                    if (showDebugOutput) {
                        System.err.println("[ffmpeg] Process ended unexpectedly, output stream closed");
                    }
                } else if (showDebugOutput) {
                    System.err.println("[ffmpeg] Error reading output: " + e.getMessage());
                }
            }
        }, "ffmpeg-output-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        // Calculate expected frame size
        int expectedFrameSizeBytes = width * height * 4; // BGRA = 4 bytes per pixel
        int expectedPixelCount = width * height;
        
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
                    scanResult = storage.listBatchFiles(targetRunId + "/", continuationTokenForScan, 1000, 
                        effectiveStartTick, effectiveEndTick);
                } catch (Exception e) {
                    // Fallback to unfiltered listing if tick filtering not supported
                    scanResult = storage.listBatchFiles(targetRunId + "/", continuationTokenForScan, 1000);
                }
            } else {
                scanResult = storage.listBatchFiles(targetRunId + "/", continuationTokenForScan, 1000);
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
        if (totalFrames > 0) {
            System.out.println(String.format("Total frames to render: %d (tick range: %d-%d, sampling: every %d)", 
                totalFrames, minTick >= 0 ? minTick : 0, maxTick, samplingInterval));
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
            
            String continuationToken = null;
            long processedFrames = 0;
            
            // Reuse ByteBuffer for RGB→BGRA conversion to avoid allocation overhead
            // For parallel rendering, each thread needs its own buffer
            ByteBuffer byteBuffer = ByteBuffer.allocate(expectedFrameSizeBytes);
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN); // BGRA format requires little-endian
            byte[] bufferArray = byteBuffer.array();
            
            // For parallel rendering: queue to maintain frame order
            BlockingQueue<byte[]> frameQueue = threadCount > 1 ? new LinkedBlockingQueue<>() : null;
            BlockingQueue<Long> tickNumberQueue = threadCount > 1 ? new LinkedBlockingQueue<>() : null;
            java.util.concurrent.atomic.AtomicLong framesWritten = threadCount > 1 ? new java.util.concurrent.atomic.AtomicLong(0) : null;
            
            // Writer thread for parallel rendering (maintains order)
            Thread writerThread = null;
            if (threadCount > 1) {
                writerThread = new Thread(() -> {
                    try {
                        while (true) {
                            byte[] frameData = frameQueue.take(); // Blocks until frame available
                            if (frameData == null) break; // Poison pill
                            Long tickNum = tickNumberQueue.take();
                            
                            // Write frame in order
                            ByteBuffer writeBuffer = ByteBuffer.wrap(frameData);
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
                        result = storage.listBatchFiles(targetRunId + "/", continuationToken, 100,
                            effectiveStartTick, effectiveEndTick);
                    } catch (Exception e) {
                        // Fallback to unfiltered listing
                        result = storage.listBatchFiles(targetRunId + "/", continuationToken, 100);
                    }
                } else {
                    result = storage.listBatchFiles(targetRunId + "/", continuationToken, 100);
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
                        
                        // Render frame (parallel if threadCount > 1, sequential otherwise)
                        if (threadCount > 1 && executorService != null) {
                            // Parallel rendering: render in background, write via queue
                            final TickData tickFinal = tick;
                            final long tickNumberFinal = tickNumber;
                            final int rendererIndex = (int) (processedFrames % threadCount);
                            final SimulationRenderer rendererToUse = renderers.get(rendererIndex);
                            
                            CompletableFuture.supplyAsync(() -> {
                                // Render frame in parallel
                                int[] pixelData = rendererToUse.render(tickFinal);
                                
                                // Convert RGB→BGRA
                                byte[] frameBytes = new byte[expectedFrameSizeBytes];
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
                            int bufferIndex = 0;
                            for (int i = 0; i < pixelData.length; i++) {
                                int rgb = pixelData[i];
                                // Extract RGB components (BufferedImage.TYPE_INT_RGB: 0x00RRGGBB)
                                // Write as BGRA (little-endian: B, G, R, A) using direct array access
                                bufferArray[bufferIndex++] = (byte) (rgb & 0xFF);         // B
                                bufferArray[bufferIndex++] = (byte) ((rgb >> 8) & 0xFF);  // G
                                bufferArray[bufferIndex++] = (byte) ((rgb >> 16) & 0xFF); // R
                                bufferArray[bufferIndex++] = (byte) 255;                  // A (fully opaque)
                            }
                            byteBuffer.position(0); // Reset position for writing
                            byteBuffer.limit(bufferIndex); // Set limit to bytes written
                                
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
                            int bytesWritten = channel.write(byteBuffer);
                            if (verbose && bytesWritten != expectedFrameSizeBytes) {
                                System.err.println(String.format(
                                    "\nERROR: Partial write! Expected %d bytes, wrote %d (tick: %d)", 
                                    expectedFrameSizeBytes, bytesWritten, tickNumber));
                                if (executorService != null) executorService.shutdown();
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
                    executorService.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // Signal writer thread to stop
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
