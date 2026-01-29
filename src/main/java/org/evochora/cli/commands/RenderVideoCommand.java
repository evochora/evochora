package org.evochora.cli.commands;

import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.cli.CliResourceFactory;
import org.evochora.cli.rendering.SimulationRenderer;
import org.evochora.cli.rendering.StatisticsBarRenderer;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.utils.MetadataConfigHelper;
import org.evochora.runtime.model.EnvironmentProperties;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Renders a simulation run to a video file using ffmpeg.
 * <p>
 * Uses delta compression for optimal performance: each chunk is rendered incrementally,
 * with only changed cells being redrawn between frames within a chunk.
 * <p>
 * Supports parallel rendering where each thread processes complete chunks independently.
 * This combines the benefits of incremental rendering (within chunks) with parallelism
 * (across chunks).
 */
@Command(name = "video", description = "Renders a simulation run to a video file using ffmpeg. "
    + "Defaults to MKV for resilience against interruptions.")
public class RenderVideoCommand implements Callable<Integer> {

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

    @Option(names = "--threads", description = "Number of threads for parallel chunk rendering. Default: 1", defaultValue = "1")
    private int threadCount;

    @Option(names = "--overlay-stats", description = "Show organism statistics bar on the right side of the video.")
    private boolean overlayStats;

    /**
     * Represents a rendered chunk with all its frames ready to be written.
     */
    private static class RenderedChunk {
        final List<RenderedFrame> frames;
        
        RenderedChunk(List<RenderedFrame> frames) {
            this.frames = frames;
        }
    }

    /**
     * Represents a single rendered frame.
     */
    private static class RenderedFrame {
        final byte[] frameData;
        final int aliveCount;
        final int deadCount;
        
        RenderedFrame(byte[] frameData, int aliveCount, int deadCount) {
            this.frameData = frameData;
            this.aliveCount = aliveCount;
            this.deadCount = deadCount;
        }
    }

    /**
     * Combines a simulation frame with a statistics bar on the right side.
     */
    private byte[] combineFrameWithStatsBar(byte[] simulationFrame, int[] statsBar, 
                                             int baseWidth, int height, int statsBarWidth) {
        int combinedWidth = baseWidth + statsBarWidth;
        byte[] combinedFrame = new byte[combinedWidth * height * 4];
        
        for (int y = 0; y < height; y++) {
            int srcOffset = y * baseWidth * 4;
            int dstOffset = y * combinedWidth * 4;
            System.arraycopy(simulationFrame, srcOffset, combinedFrame, dstOffset, baseWidth * 4);
        }
        
        for (int y = 0; y < height; y++) {
            int barIndex = y * statsBarWidth;
            int dstOffset = y * combinedWidth * 4 + baseWidth * 4;
            for (int x = 0; x < statsBarWidth; x++) {
                int rgb = statsBar[barIndex + x];
                combinedFrame[dstOffset++] = (byte) (rgb & 0xFF);
                combinedFrame[dstOffset++] = (byte) ((rgb >> 8) & 0xFF);
                combinedFrame[dstOffset++] = (byte) ((rgb >> 16) & 0xFF);
                combinedFrame[dstOffset++] = (byte) 255;
            }
        }
        
        return combinedFrame;
    }

    /**
     * Formats milliseconds into a human-readable time string.
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

    /**
     * Converts pixel data (RGB int array) to BGRA byte array.
     */
    private byte[] convertToBgra(int[] pixelData) {
        byte[] frameBytes = new byte[pixelData.length * 4];
        int bufferIndex = 0;
        for (int rgb : pixelData) {
            frameBytes[bufferIndex++] = (byte) (rgb & 0xFF);
            frameBytes[bufferIndex++] = (byte) ((rgb >> 8) & 0xFF);
            frameBytes[bufferIndex++] = (byte) ((rgb >> 16) & 0xFF);
            frameBytes[bufferIndex++] = (byte) 255;
        }
        return frameBytes;
    }

    /**
     * Process a batch of chunks in parallel, then write results in order.
     * Returns total frames written, or -1 on error.
     */
    private long processBatchParallel(
            List<TickDataChunk> chunks, 
            long startIndex,
            List<SimulationRenderer> renderers,
            ExecutorService executor,
            long effectiveStartTick, long effectiveEndTick, int samplingInterval,
            WritableByteChannel channel,
            StatisticsBarRenderer statsBarRenderer,
            int maxOrganismId, int currentMaxAlive,
            int baseWidth, int height, int statsBarWidth,
            Process ffmpeg, AtomicBoolean ffmpegDied) throws Exception {
        
        int batchSize = chunks.size();
        
        // Array to hold results in order
        @SuppressWarnings("unchecked")
        java.util.concurrent.Future<RenderedChunk>[] futures = new java.util.concurrent.Future[batchSize];
        
        // Submit all chunks for parallel rendering
        for (int i = 0; i < batchSize; i++) {
            final TickDataChunk chunk = chunks.get(i);
            final SimulationRenderer renderer = renderers.get(i % renderers.size());
            final long chunkIndex = startIndex + i;
            
            futures[i] = executor.submit(() -> {
                return renderChunk(chunk, chunkIndex, renderer, 
                    effectiveStartTick, effectiveEndTick, samplingInterval);
            });
        }
        
        // Wait for all to complete and write in order
        long totalWritten = 0;
        for (int i = 0; i < batchSize; i++) {
            RenderedChunk rendered = futures[i].get();
            
            for (RenderedFrame frame : rendered.frames) {
                if (!ffmpeg.isAlive() || ffmpegDied.get()) {
                    System.err.println("\nffmpeg died");
                    return -1;
                }
                
                byte[] finalFrameData = frame.frameData;
                if (statsBarRenderer != null) {
                    int effectiveMax = maxOrganismId > 0 ? maxOrganismId : 
                        Math.max(currentMaxAlive, frame.aliveCount + frame.deadCount);
                    int[] statsBar = statsBarRenderer.render(frame.aliveCount, frame.deadCount, effectiveMax);
                    finalFrameData = combineFrameWithStatsBar(frame.frameData, statsBar, baseWidth, height, statsBarWidth);
                }
                
                ByteBuffer buffer = ByteBuffer.wrap(finalFrameData);
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                channel.write(buffer);
                totalWritten++;
            }
        }
        
        return totalWritten;
    }

    /**
     * Collects organism statistics.
     */
    private int[] collectOrganismStats(List<OrganismState> organisms) {
        int aliveCount = 0;
        int deadCount = 0;
        int maxOrgId = 0;
        for (OrganismState org : organisms) {
            if (org.getIsDead()) {
                deadCount++;
            } else {
                aliveCount++;
            }
            if (org.getOrganismId() > maxOrgId) {
                maxOrgId = org.getOrganismId();
            }
        }
        return new int[]{aliveCount, deadCount, maxOrgId};
    }

    /**
     * Renders a single chunk incrementally and writes frames directly to the channel.
     * Used for single-threaded rendering to avoid memory accumulation.
     *
     * @return number of frames written
     */
    private long renderAndWriteChunkDirect(TickDataChunk chunk, 
                                            SimulationRenderer renderer,
                                            WritableByteChannel channel,
                                            long effectiveStartTick, long effectiveEndTick,
                                            int samplingInterval,
                                            StatisticsBarRenderer statsBarRenderer,
                                            int maxOrganismId, int currentMaxAlive,
                                            int baseWidth, int height, int statsBarWidth) throws java.io.IOException {
        long framesWritten = 0;
        
        // Process snapshot first
        TickData snapshot = chunk.getSnapshot();
        long snapshotTick = snapshot.getTickNumber();
        
        // Initialize renderer with snapshot
        int[] pixelData = renderer.renderSnapshot(snapshot);
        
        // Check if snapshot should be included
        if (snapshotTick >= effectiveStartTick && 
            snapshotTick <= effectiveEndTick &&
            snapshotTick % samplingInterval == 0) {
            
            int[] stats = collectOrganismStats(snapshot.getOrganismsList());
            byte[] frameData = convertToBgra(pixelData);
            
            if (statsBarRenderer != null) {
                int effectiveMax = maxOrganismId > 0 ? maxOrganismId : 
                    Math.max(currentMaxAlive, stats[0] + stats[1]);
                int[] statsBar = statsBarRenderer.render(stats[0], stats[1], effectiveMax);
                frameData = combineFrameWithStatsBar(frameData, statsBar, baseWidth, height, statsBarWidth);
            }
            
            ByteBuffer buffer = ByteBuffer.wrap(frameData);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            channel.write(buffer);
            framesWritten++;
        }
        
        // Process deltas
        for (TickDelta delta : chunk.getDeltasList()) {
            long deltaTick = delta.getTickNumber();
            
            // Always apply delta to keep state consistent
            pixelData = renderer.renderDelta(delta);
            
            // Check if this tick should be included in output
            if (deltaTick >= effectiveStartTick && 
                deltaTick <= effectiveEndTick &&
                deltaTick % samplingInterval == 0) {
                
                int[] stats = collectOrganismStats(delta.getOrganismsList());
                byte[] frameData = convertToBgra(pixelData);
                
                if (statsBarRenderer != null) {
                    int effectiveMax = maxOrganismId > 0 ? maxOrganismId : 
                        Math.max(currentMaxAlive, stats[0] + stats[1]);
                    int[] statsBar = statsBarRenderer.render(stats[0], stats[1], effectiveMax);
                    frameData = combineFrameWithStatsBar(frameData, statsBar, baseWidth, height, statsBarWidth);
                }
                
                ByteBuffer buffer = ByteBuffer.wrap(frameData);
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                channel.write(buffer);
                framesWritten++;
            }
        }
        
        return framesWritten;
    }

    /**
     * Renders a single chunk incrementally and returns all frames.
     * Used for multi-threaded rendering where frames need to be buffered.
     */
    private RenderedChunk renderChunk(TickDataChunk chunk, long chunkIndex, 
                                       SimulationRenderer renderer,
                                       long effectiveStartTick, long effectiveEndTick,
                                       int samplingInterval) {
        List<RenderedFrame> frames = new ArrayList<>();
        
        // Process snapshot first
        TickData snapshot = chunk.getSnapshot();
        long snapshotTick = snapshot.getTickNumber();
        
        // Initialize renderer with snapshot
        int[] pixelData = renderer.renderSnapshot(snapshot);
        
        // Check if snapshot should be included
        if (snapshotTick >= effectiveStartTick && 
            snapshotTick <= effectiveEndTick &&
            snapshotTick % samplingInterval == 0) {
            
            int[] stats = collectOrganismStats(snapshot.getOrganismsList());
            byte[] frameData = convertToBgra(pixelData);
            frames.add(new RenderedFrame(frameData, stats[0], stats[1]));
        }
        
        // Process deltas
        for (TickDelta delta : chunk.getDeltasList()) {
            long deltaTick = delta.getTickNumber();
            
            // Always apply delta to keep state consistent
            pixelData = renderer.renderDelta(delta);
            
            // Check if this tick should be included in output
            if (deltaTick >= effectiveStartTick && 
                deltaTick <= effectiveEndTick &&
                deltaTick % samplingInterval == 0) {
                
                int[] stats = collectOrganismStats(delta.getOrganismsList());
                byte[] frameData = convertToBgra(pixelData);
                frames.add(new RenderedFrame(frameData, stats[0], stats[1]));
            }
        }
        
        return new RenderedChunk(frames);
    }

    @Override
    public Integer call() throws Exception {
        // Normalize output file path
        String outputPath = outputFile.getPath();
        if (outputPath.startsWith("~/") || outputPath.equals("~")) {
            String homeDir = System.getProperty("user.home");
            outputPath = outputPath.replaceFirst("^~", homeDir);
            outputFile = new File(outputPath);
        } else if (!outputFile.isAbsolute()) {
            outputFile = outputFile.getAbsoluteFile();
        }
        
        // Load config
        Config config;
        try {
            if (this.configFile != null) {
                if (!this.configFile.exists()) {
                    System.err.println("Configuration file not found: " + this.configFile.getAbsolutePath());
                    return 1;
                }
                System.out.println("Using configuration file: " + this.configFile.getAbsolutePath());
                config = ConfigFactory.systemProperties()
                    .withFallback(ConfigFactory.systemEnvironment())
                    .withFallback(ConfigFactory.parseFile(this.configFile))
                    .withFallback(ConfigFactory.load())
                    .resolve();
            } else {
                final File defaultConfFile = new File(CONFIG_FILE_NAME);
                if (defaultConfFile.exists()) {
                    System.out.println("Using configuration file: " + defaultConfFile.getAbsolutePath());
                    config = ConfigFactory.systemProperties()
                        .withFallback(ConfigFactory.systemEnvironment())
                        .withFallback(ConfigFactory.parseFile(defaultConfFile))
                        .withFallback(ConfigFactory.load())
                        .resolve();
                } else {
                    System.out.println("Warning: No 'evochora.conf' found. Using defaults.");
                    config = ConfigFactory.systemProperties()
                        .withFallback(ConfigFactory.systemEnvironment())
                        .withFallback(ConfigFactory.load())
                        .resolve();
                }
            }
        } catch (com.typesafe.config.ConfigException e) {
            System.err.println("Failed to load configuration: " + e.getMessage());
            return 1;
        }

        String storageConfigPath = "pipeline.resources." + storageName;
        if (!config.hasPath(storageConfigPath)) {
            System.err.println("Storage resource '" + storageName + "' not configured.");
            return 1;
        }
        Config storageConfig = config.getConfig(storageConfigPath);

        IBatchStorageRead storage = CliResourceFactory.create("cli-video-renderer", IBatchStorageRead.class, storageConfig);
        System.out.println("Using storage: " + storageName + " (" + storage.getClass().getSimpleName() + ")");

        String targetRunId = runId;
        if (targetRunId == null) {
            System.out.println("No run-id specified, discovering latest run...");
            List<String> runIds = storage.listRunIds(java.time.Instant.EPOCH);
            if (runIds.isEmpty()) {
                System.err.println("No simulation runs found.");
                return 1;
            }
            targetRunId = runIds.get(runIds.size() - 1);
            System.out.println("Found latest run: " + targetRunId);
        }

        System.out.println("Reading metadata...");
        java.util.Optional<StoragePath> metadataPathOpt = storage.findMetadataPath(targetRunId);
        if (metadataPathOpt.isEmpty()) {
            System.err.println("Metadata not found for run: " + targetRunId);
            return 1;
        }

        SimulationMetadata metadata = storage.readMessage(metadataPathOpt.get(), SimulationMetadata.parser());
        EnvironmentProperties envProps = new EnvironmentProperties(
            MetadataConfigHelper.getEnvironmentShape(metadata),
            MetadataConfigHelper.isEnvironmentToroidal(metadata)
        );

        int baseWidth = envProps.getWorldShape()[0] * cellSize;
        int height = envProps.getWorldShape()[1] * cellSize;
        int statsBarWidth = overlayStats ? 60 : 0;
        int width = baseWidth + statsBarWidth;

        long effectiveStartTick = startTick != null ? startTick : 0;
        long effectiveEndTick = endTick != null ? endTick : Long.MAX_VALUE;

        // Determine output format
        outputPath = outputFile.getAbsolutePath();
        String outPathLower = outputPath.toLowerCase();
        String formatLower = format.toLowerCase();
        String currentExtension = "";
        int lastDot = outPathLower.lastIndexOf('.');
        if (lastDot > 0 && lastDot < outPathLower.length() - 1) {
            currentExtension = outPathLower.substring(lastDot + 1);
        }
        if (!currentExtension.equals(formatLower)) {
            format = formatLower;
            if (lastDot > 0) {
                outputPath = outputPath.substring(0, lastDot) + "." + format;
            } else {
                outputPath = outputPath + "." + format;
            }
            outputFile = new File(outputPath);
        } else {
            format = currentExtension;
        }

        // Build ffmpeg command
        java.util.List<String> ffmpegArgs = new java.util.ArrayList<>();
        ffmpegArgs.add("ffmpeg");
        ffmpegArgs.add("-y");
        ffmpegArgs.add("-f"); ffmpegArgs.add("rawvideo");
        ffmpegArgs.add("-vcodec"); ffmpegArgs.add("rawvideo");
        ffmpegArgs.add("-s"); ffmpegArgs.add(width + "x" + height);
        ffmpegArgs.add("-pix_fmt"); ffmpegArgs.add("bgra");
        ffmpegArgs.add("-r"); ffmpegArgs.add(String.valueOf(fps));
        ffmpegArgs.add("-i"); ffmpegArgs.add("-");
        
        switch (format.toLowerCase()) {
            case "mp4":
                ffmpegArgs.add("-c:v"); ffmpegArgs.add("libx264");
                ffmpegArgs.add("-preset"); ffmpegArgs.add(preset);
                ffmpegArgs.add("-crf"); ffmpegArgs.add("18");
                ffmpegArgs.add("-movflags"); ffmpegArgs.add("+frag_keyframe+empty_moov");
                break;
            case "webm":
                ffmpegArgs.add("-c:v"); ffmpegArgs.add("libvpx-vp9");
                ffmpegArgs.add("-crf"); ffmpegArgs.add("10");
                ffmpegArgs.add("-b:v"); ffmpegArgs.add("0");
                break;
            default:
                ffmpegArgs.add("-c:v"); ffmpegArgs.add("libx264");
                ffmpegArgs.add("-preset"); ffmpegArgs.add(preset);
                break;
        }
        ffmpegArgs.add("-pix_fmt"); ffmpegArgs.add("yuv420p");
        
        // Overlay filters
        if (overlayTick || overlayTime || overlayRunId) {
            String xPos, yPos;
            switch (overlayPosition.toLowerCase()) {
                case "top-right": xPos = "main_w-text_w-10"; yPos = "10"; break;
                case "bottom-left": xPos = "10"; yPos = "main_h-text_h-10"; break;
                case "bottom-right": xPos = "main_w-text_w-10"; yPos = "main_h-text_h-10"; break;
                default: xPos = "10"; yPos = "10"; break;
            }
            
            java.util.List<String> filterParts = new java.util.ArrayList<>();
            int yOffset = 0;
            
            if (overlayRunId) {
                String runText = "Run: " + targetRunId;
                String escapedRunText = runText.replace("'", "''");
                filterParts.add(String.format(
                    "drawtext=text='%s':x=%s:y=%d:fontsize=%d:fontcolor=%s",
                    escapedRunText, xPos, Integer.parseInt(yPos) + yOffset, overlayFontSize, overlayColor
                ));
                yOffset += overlayFontSize + 5;
            }
            
            if (overlayTick) {
                long firstRenderableTick = ((effectiveStartTick / samplingInterval) * samplingInterval);
                if (firstRenderableTick < effectiveStartTick) {
                    firstRenderableTick += samplingInterval;
                }
                String tickText = String.format("Tick\\\\:%%{expr\\\\:(n*%d)+%d}", samplingInterval, firstRenderableTick);
                filterParts.add(String.format(
                    "drawtext=text=%s:x=%s:y=%d:fontsize=%d:fontcolor=%s",
                    tickText, xPos, Integer.parseInt(yPos) + yOffset, overlayFontSize, overlayColor
                ));
                yOffset += overlayFontSize + 5;
            }
            
            if (overlayTime) {
                String timeText = String.format("Time\\\\: %%{expr\\\\:n/%d}\\\\.%%{expr\\\\:((n%%%d)*100)/%d} s", fps, fps, fps);
                filterParts.add(String.format(
                    "drawtext=text=%s:x=%s:y=%d:fontsize=%d:fontcolor=%s",
                    timeText, xPos, Integer.parseInt(yPos) + yOffset, overlayFontSize, overlayColor
                ));
            }
            
            if (!filterParts.isEmpty() && !"webm".equalsIgnoreCase(format)) {
                ffmpegArgs.add("-vf");
                ffmpegArgs.add(String.join(",", filterParts));
            }
        }
        
        ffmpegArgs.add(outputFile.getAbsolutePath());
        
        ProcessBuilder pb = new ProcessBuilder(ffmpegArgs);
        pb.redirectErrorStream(true);
        
        Process ffmpeg;
        try {
            ffmpeg = pb.start();
        } catch (Exception e) {
            System.err.println("Failed to start ffmpeg: " + e.getMessage());
            return 1;
        }

        // FFmpeg output reader
        final Process finalFfmpeg = ffmpeg;
        final boolean showDebugOutput = verbose;
        AtomicBoolean ffmpegDied = new AtomicBoolean(false);
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
                    outputBuffer.append(output);
                    if (outputBuffer.length() > 2000) {
                        outputBuffer.delete(0, outputBuffer.length() - 2000);
                    }
                    lastFfmpegOutput.set(outputBuffer.toString());
                }
            } catch (Exception e) {
                if (!finalFfmpeg.isAlive()) {
                    ffmpegDied.set(true);
                }
            }
        }, "ffmpeg-output-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        // Scan batch files
        System.out.print("Scanning batch files... ");
        long minTick = Long.MAX_VALUE;
        long maxTick = -1;
        List<StoragePath> allBatchPaths = new ArrayList<>();
        String continuationToken = null;
        do {
            BatchFileListResult scanResult = storage.listBatchFiles(targetRunId + "/raw/", continuationToken, 1000);
            for (StoragePath path : scanResult.getFilenames()) {
                allBatchPaths.add(path);
                String filename = path.asString();
                int batchIdx = filename.lastIndexOf("/batch_");
                if (batchIdx >= 0) {
                    String batchName = filename.substring(batchIdx + 7);
                    int firstUnderscore = batchName.indexOf('_');
                    int dotPbIdx = batchName.indexOf(".pb");
                    if (firstUnderscore > 0 && dotPbIdx > firstUnderscore) {
                        try {
                            long batchStartTick = Long.parseLong(batchName.substring(0, firstUnderscore));
                            long batchEndTick = Long.parseLong(batchName.substring(firstUnderscore + 1, dotPbIdx));
                            if (batchStartTick < minTick) minTick = batchStartTick;
                            if (batchEndTick > maxTick) maxTick = batchEndTick;
                        } catch (NumberFormatException e) {
                            // Skip
                        }
                    }
                }
            }
            continuationToken = scanResult.getNextContinuationToken();
        } while (continuationToken != null);
        System.out.println(String.format("%d files found, tick range: %d-%d", 
            allBatchPaths.size(), minTick < Long.MAX_VALUE ? minTick : 0, maxTick));

        // Calculate total frames
        long totalFrames = 0;
        if (maxTick >= 0) {
            long actualMinTick = Math.max(minTick < Long.MAX_VALUE ? minTick : 0, effectiveStartTick);
            long actualMaxTick = Math.min(maxTick, effectiveEndTick);
            if (actualMaxTick >= actualMinTick) {
                long firstRenderableTick = ((actualMinTick / samplingInterval) * samplingInterval);
                if (firstRenderableTick < actualMinTick) {
                    firstRenderableTick += samplingInterval;
                }
                if (firstRenderableTick <= actualMaxTick) {
                    totalFrames = ((actualMaxTick - firstRenderableTick) / samplingInterval) + 1;
                }
            }
        }

        System.out.println(String.format("Video: %dx%d, %d frames, %d fps", width, height, totalFrames, fps));
        System.out.println(String.format("Rendering: %d thread(s), incremental (delta-optimized)", threadCount));

        // Find max organism ID for stats bar
        int maxOrganismId = 0;
        // (simplified - will use dynamic scaling)

        final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
        Thread shutdownHook = null;

        try (OutputStream ffmpegInput = ffmpeg.getOutputStream();
             WritableByteChannel channel = Channels.newChannel(ffmpegInput)) {

            // Statistics bar renderer
            StatisticsBarRenderer statsBarRenderer = overlayStats ? new StatisticsBarRenderer(statsBarWidth, height) : null;
            int currentMaxAlive = 0;
            
            AtomicLong framesWritten = new AtomicLong(0);
            long startTime = System.currentTimeMillis();
            long lastProgressUpdate = startTime;
            
            if (threadCount == 1) {
                // ============================================================
                // SINGLE-THREADED: Direct rendering and writing (no buffering)
                // ============================================================
                SimulationRenderer renderer = new SimulationRenderer(envProps, cellSize);
                
                shutdownHook = new Thread(() -> {
                    System.out.println("\n\nShutdown requested...");
                    shutdownRequested.set(true);
                }, "video-shutdown-hook");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                
                for (StoragePath batchPath : allBatchPaths) {
                    if (shutdownRequested.get()) break;
                    
                    List<TickDataChunk> chunks = storage.readChunkBatch(batchPath);
                    
                    for (TickDataChunk chunk : chunks) {
                        if (shutdownRequested.get()) break;
                        
                        // Check if chunk overlaps with our tick range
                        if (chunk.getLastTick() < effectiveStartTick || chunk.getFirstTick() > effectiveEndTick) {
                            continue;
                        }
                        
                        // Check if ffmpeg is still alive
                        if (!ffmpeg.isAlive() || ffmpegDied.get()) {
                            System.err.println("\nffmpeg died unexpectedly");
                            return 1;
                        }
                        
                        // Render and write directly
                        long written = renderAndWriteChunkDirect(chunk, renderer, channel,
                            effectiveStartTick, effectiveEndTick, samplingInterval,
                            statsBarRenderer, maxOrganismId, currentMaxAlive,
                            baseWidth, height, statsBarWidth);
                        framesWritten.addAndGet(written);
                        
                        // Update progress
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastProgressUpdate >= 500) {
                            lastProgressUpdate = currentTime;
                            long totalWritten = framesWritten.get();
                            long elapsed = currentTime - startTime;
                            double fpsRendered = totalWritten > 0 ? (totalWritten * 1000.0) / elapsed : 0;
                            long remaining = totalFrames > 0 && fpsRendered > 0 ? 
                                (long) (((totalFrames - totalWritten) * 1000.0) / fpsRendered) : 0;
                            
                            if (totalFrames > 0) {
                                int percentage = (int) ((totalWritten * 100) / totalFrames);
                                int barWidth = 40;
                                int filled = (int) ((totalWritten * barWidth) / totalFrames);
                                StringBuilder bar = new StringBuilder("[");
                                for (int i = 0; i < barWidth; i++) {
                                    bar.append(i < filled ? "=" : " ");
                                }
                                bar.append("]");
                                System.out.print(String.format("\r%s %d%% | Frame %d/%d | %.1f fps | Elapsed: %s | ETA: %s",
                                    bar, percentage, totalWritten, totalFrames, fpsRendered, formatTime(elapsed), formatTime(remaining)));
                            } else {
                                System.out.print(String.format("\rFrame %d | %.1f fps | Elapsed: %s", 
                                    totalWritten, fpsRendered, formatTime(elapsed)));
                            }
                            System.out.flush();
                        }
                    }
                }
            } else {
                // ============================================================
                // MULTI-THREADED: Batch-parallel rendering
                // Process chunks in batches of threadCount, render parallel, write in order
                // ============================================================
                
                ExecutorService executor = Executors.newFixedThreadPool(threadCount);
                
                // Create renderer pool (one per thread index)
                List<SimulationRenderer> renderers = new ArrayList<>();
                for (int i = 0; i < threadCount; i++) {
                    renderers.add(new SimulationRenderer(envProps, cellSize));
                }
                
                final ExecutorService finalExecutor = executor;
                shutdownHook = new Thread(() -> {
                    System.out.println("\n\nShutdown requested...");
                    shutdownRequested.set(true);
                    finalExecutor.shutdownNow();
                }, "video-shutdown-hook");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                
                // Collect chunks in batches
                List<TickDataChunk> chunkBatch = new ArrayList<>(threadCount);
                long globalChunkIndex = 0;
                
                for (StoragePath batchPath : allBatchPaths) {
                    if (shutdownRequested.get()) break;
                    
                    List<TickDataChunk> chunks = storage.readChunkBatch(batchPath);
                    
                    for (TickDataChunk chunk : chunks) {
                        if (shutdownRequested.get()) break;
                        
                        // Check if chunk overlaps with our tick range
                        if (chunk.getLastTick() < effectiveStartTick || chunk.getFirstTick() > effectiveEndTick) {
                            continue;
                        }
                        
                        chunkBatch.add(chunk);
                        
                        // When batch is full, render it
                        if (chunkBatch.size() >= threadCount) {
                            long written = processBatchParallel(chunkBatch, globalChunkIndex, renderers, executor,
                                effectiveStartTick, effectiveEndTick, samplingInterval,
                                channel, statsBarRenderer, maxOrganismId, currentMaxAlive,
                                baseWidth, height, statsBarWidth, ffmpeg, ffmpegDied);
                            
                            if (written < 0) {
                                executor.shutdownNow();
                                return 1; // Error
                            }
                            
                            framesWritten.addAndGet(written);
                            globalChunkIndex += chunkBatch.size();
                            chunkBatch.clear();
                            
                            // Update progress
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastProgressUpdate >= 500) {
                                lastProgressUpdate = currentTime;
                                long totalWritten = framesWritten.get();
                                long elapsed = currentTime - startTime;
                                double fpsRendered = totalWritten > 0 ? (totalWritten * 1000.0) / elapsed : 0;
                                long remaining = totalFrames > 0 && fpsRendered > 0 ? 
                                    (long) (((totalFrames - totalWritten) * 1000.0) / fpsRendered) : 0;
                                
                                if (totalFrames > 0) {
                                    int percentage = (int) ((totalWritten * 100) / totalFrames);
                                    int barWidth = 40;
                                    int filled = (int) ((totalWritten * barWidth) / totalFrames);
                                    StringBuilder bar = new StringBuilder("[");
                                    for (int i = 0; i < barWidth; i++) {
                                        bar.append(i < filled ? "=" : " ");
                                    }
                                    bar.append("]");
                                System.out.print(String.format("\r%s %d%% | Frame %d/%d | %.1f fps | Elapsed: %s | ETA: %s",
                                    bar, percentage, totalWritten, totalFrames, fpsRendered, formatTime(elapsed), formatTime(remaining)));
                                } else {
                                    System.out.print(String.format("\rFrame %d | %.1f fps | Elapsed: %s", 
                                        totalWritten, fpsRendered, formatTime(elapsed)));
                                }
                                System.out.flush();
                            }
                        }
                    }
                }
                
                // Process remaining chunks
                if (!chunkBatch.isEmpty() && !shutdownRequested.get()) {
                    long written = processBatchParallel(chunkBatch, globalChunkIndex, renderers, executor,
                        effectiveStartTick, effectiveEndTick, samplingInterval,
                        channel, statsBarRenderer, maxOrganismId, currentMaxAlive,
                        baseWidth, height, statsBarWidth, ffmpeg, ffmpegDied);
                    
                    if (written < 0) {
                        executor.shutdownNow();
                        return 1;
                    }
                    framesWritten.addAndGet(written);
                }
                
                executor.shutdown();
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            }
            
            // Remove shutdown hook
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException e) {
                    // Already shutting down
                }
            }
            
            System.out.println("\nFinished rendering. Closing...");
            
        } catch (Exception e) {
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {}
            }
            throw e;
        }

        try {
            outputReader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int exitCode = ffmpeg.waitFor();
        if (exitCode == 0) {
            System.out.println("Video created: " + outputFile.getAbsolutePath());
        } else {
            System.err.println("ffmpeg failed with exit code " + exitCode);
            return 1;
        }

        return 0;
    }
}
