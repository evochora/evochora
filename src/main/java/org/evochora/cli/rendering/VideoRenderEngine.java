package org.evochora.cli.rendering;

import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.cli.CliResourceFactory;
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

/**
 * Video rendering engine that orchestrates the rendering pipeline.
 * <p>
 * This class contains the core rendering logic, handling config loading,
 * storage access, ffmpeg process management, and single/multi-threaded
 * rendering loops.
 * <p>
 * The logic is extracted 1:1 from RenderVideoCommand to ensure all
 * performance optimizations are preserved.
 */
public class VideoRenderEngine {

    private static final String CONFIG_FILE_NAME = "evochora.conf";

    private final VideoRenderOptions options;
    private final IVideoFrameRenderer frameRenderer;

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
     * Represents a single rendered frame ready to be written to ffmpeg.
     */
    private static class RenderedFrame {
        final byte[] frameData;

        RenderedFrame(byte[] frameData) {
            this.frameData = frameData;
        }
    }

    /**
     * Creates a new video render engine.
     *
     * @param options The video rendering options.
     * @param frameRenderer The frame renderer to use.
     */
    public VideoRenderEngine(VideoRenderOptions options, IVideoFrameRenderer frameRenderer) {
        this.options = options;
        this.frameRenderer = frameRenderer;
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
     * <p>
     * Writes into the provided buffer to avoid per-frame allocation.
     *
     * @param pixelData Source RGB pixel data.
     * @param buffer Target BGRA buffer (must be pixelData.length * 4 bytes).
     */
    private void convertToBgra(int[] pixelData, byte[] buffer) {
        int bufferIndex = 0;
        for (int rgb : pixelData) {
            buffer[bufferIndex++] = (byte) (rgb & 0xFF);
            buffer[bufferIndex++] = (byte) ((rgb >> 8) & 0xFF);
            buffer[bufferIndex++] = (byte) ((rgb >> 16) & 0xFF);
            buffer[bufferIndex++] = (byte) 255;
        }
    }

    /**
     * Converts pixel data to a new BGRA byte array.
     * <p>
     * Used in multi-threaded rendering where frames must be stored.
     * Single-threaded rendering uses {@link #convertToBgra(int[], byte[])} instead.
     *
     * @param pixelData Source RGB pixel data.
     * @return New BGRA byte array.
     */
    private byte[] convertToBgraNew(int[] pixelData) {
        byte[] buffer = new byte[pixelData.length * 4];
        convertToBgra(pixelData, buffer);
        return buffer;
    }

    /**
     * Loads overlay renderers by name via reflection.
     *
     * @param names List of overlay names (e.g., "info")
     * @return List of instantiated overlay renderers
     */
    private List<IOverlayRenderer> loadOverlays(List<String> names) {
        List<IOverlayRenderer> overlays = new ArrayList<>();
        if (names == null || names.isEmpty()) {
            return overlays;
        }

        for (String name : names) {
            String className = "org.evochora.cli.rendering.overlay."
                + capitalize(name) + "OverlayRenderer";
            try {
                Class<?> clazz = Class.forName(className);
                IOverlayRenderer overlay = (IOverlayRenderer) clazz.getDeclaredConstructor().newInstance();
                overlays.add(overlay);
            } catch (Exception e) {
                System.err.println("Warning: Failed to load overlay '" + name + "': " + e.getMessage());
            }
        }
        return overlays;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    /**
     * Renders a single chunk and writes frames directly to the channel.
     * Used for single-threaded rendering to avoid memory accumulation.
     * <p>
     * For samplingInterval=1: Uses incremental rendering (renderSnapshot + renderDelta).
     * For samplingInterval>1: Uses renderTick() with delta decompression (only renders needed ticks).
     *
     * @return number of frames written
     */
    private long renderAndWriteChunkDirect(TickDataChunk chunk,
                                            IVideoFrameRenderer renderer,
                                            WritableByteChannel channel,
                                            long effectiveStartTick, long effectiveEndTick,
                                            int samplingInterval) throws java.io.IOException {
        long framesWritten = 0;
        byte[] bgraBuffer = renderer.getBgraBuffer();

        if (samplingInterval == 1) {
            // ==== INCREMENTAL RENDERING (fastest path) ====
            // Render snapshot
            TickData snapshot = chunk.getSnapshot();
            long snapshotTick = snapshot.getTickNumber();
            int[] pixelData = renderer.renderSnapshot(snapshot);

            if (snapshotTick >= effectiveStartTick && snapshotTick <= effectiveEndTick) {
                convertToBgra(pixelData, bgraBuffer);
                ByteBuffer buffer = ByteBuffer.wrap(bgraBuffer);
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                channel.write(buffer);
                framesWritten++;
            }

            // Render deltas incrementally
            for (TickDelta delta : chunk.getDeltasList()) {
                long deltaTick = delta.getTickNumber();
                pixelData = renderer.renderDelta(delta);

                if (deltaTick >= effectiveStartTick && deltaTick <= effectiveEndTick) {
                    convertToBgra(pixelData, bgraBuffer);
                    ByteBuffer buffer = ByteBuffer.wrap(bgraBuffer);
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                    channel.write(buffer);
                    framesWritten++;
                }
            }
        } else {
            // ==== SAMPLING MODE (uses renderTick with delta decompression) ====
            // Only render ticks that match the sampling interval
            long chunkFirstTick = chunk.getFirstTick();
            long chunkLastTick = chunk.getLastTick();

            // Find first sample tick in this chunk
            long firstSampleTick = ((Math.max(chunkFirstTick, effectiveStartTick) + samplingInterval - 1)
                    / samplingInterval) * samplingInterval;

            for (long tick = firstSampleTick; tick <= Math.min(chunkLastTick, effectiveEndTick); tick += samplingInterval) {
                try {
                    int[] pixelData = renderer.renderTick(chunk, tick);
                    convertToBgra(pixelData, bgraBuffer);
                    ByteBuffer buffer = ByteBuffer.wrap(bgraBuffer);
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                    channel.write(buffer);
                    framesWritten++;
                } catch (org.evochora.datapipeline.api.delta.ChunkCorruptedException e) {
                    System.err.println("Warning: Failed to render tick " + tick + ": " + e.getMessage());
                }
            }
        }

        return framesWritten;
    }

    /**
     * Renders a single chunk and returns all frames.
     * Used for multi-threaded rendering where frames need to be buffered.
     * <p>
     * For samplingInterval=1: Uses incremental rendering (renderSnapshot + renderDelta).
     * For samplingInterval>1: Uses renderTick() with delta decompression (only renders needed ticks).
     */
    private RenderedChunk renderChunk(TickDataChunk chunk, long chunkIndex,
                                       IVideoFrameRenderer renderer,
                                       long effectiveStartTick, long effectiveEndTick,
                                       int samplingInterval) {
        List<RenderedFrame> frames = new ArrayList<>();

        if (samplingInterval == 1) {
            // ==== INCREMENTAL RENDERING (fastest path) ====
            TickData snapshot = chunk.getSnapshot();
            long snapshotTick = snapshot.getTickNumber();
            int[] pixelData = renderer.renderSnapshot(snapshot);

            if (snapshotTick >= effectiveStartTick && snapshotTick <= effectiveEndTick) {
                frames.add(new RenderedFrame(convertToBgraNew(pixelData)));
            }

            for (TickDelta delta : chunk.getDeltasList()) {
                long deltaTick = delta.getTickNumber();
                pixelData = renderer.renderDelta(delta);

                if (deltaTick >= effectiveStartTick && deltaTick <= effectiveEndTick) {
                    frames.add(new RenderedFrame(convertToBgraNew(pixelData)));
                }
            }
        } else {
            // ==== SAMPLING MODE (uses renderTick with delta decompression) ====
            long chunkFirstTick = chunk.getFirstTick();
            long chunkLastTick = chunk.getLastTick();

            // Find first sample tick in this chunk
            long firstSampleTick = ((Math.max(chunkFirstTick, effectiveStartTick) + samplingInterval - 1)
                    / samplingInterval) * samplingInterval;

            for (long tick = firstSampleTick; tick <= Math.min(chunkLastTick, effectiveEndTick); tick += samplingInterval) {
                try {
                    int[] pixelData = renderer.renderTick(chunk, tick);
                    frames.add(new RenderedFrame(convertToBgraNew(pixelData)));
                } catch (org.evochora.datapipeline.api.delta.ChunkCorruptedException e) {
                    System.err.println("Warning: Failed to render tick " + tick + ": " + e.getMessage());
                }
            }
        }

        return new RenderedChunk(frames);
    }

    /**
     * Executes the video rendering pipeline.
     * <p>
     * This method is a 1:1 copy of the original RenderVideoCommand.call() logic,
     * with variable references changed from this.xyz to options.xyz.
     *
     * @return Exit code (0 for success, non-zero for failure).
     * @throws Exception if rendering fails.
     */
    public Integer execute() throws Exception {
        // Normalize output file path
        File outputFile = options.outputFile;
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
            if (options.configFile != null) {
                if (!options.configFile.exists()) {
                    System.err.println("Configuration file not found: " + options.configFile.getAbsolutePath());
                    return 1;
                }
                System.out.println("Using configuration file: " + options.configFile.getAbsolutePath());
                config = ConfigFactory.systemProperties()
                    .withFallback(ConfigFactory.systemEnvironment())
                    .withFallback(ConfigFactory.parseFile(options.configFile))
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

        String storageConfigPath = "pipeline.resources." + options.storageName;
        if (!config.hasPath(storageConfigPath)) {
            System.err.println("Storage resource '" + options.storageName + "' not configured.");
            return 1;
        }
        Config storageConfig = config.getConfig(storageConfigPath);

        IBatchStorageRead storage = CliResourceFactory.create("cli-video-renderer", IBatchStorageRead.class, storageConfig);
        System.out.println("Using storage: " + options.storageName + " (" + storage.getClass().getSimpleName() + ")");

        String targetRunId = options.runId;
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

        // Initialize renderer with environment properties
        frameRenderer.init(envProps);

        // Load and set overlay renderers on the renderer
        // Overlays are applied automatically during renderSnapshot/renderDelta
        frameRenderer.setOverlays(loadOverlays(options.overlayNames));

        int width = frameRenderer.getImageWidth();
        int height = frameRenderer.getImageHeight();

        long effectiveStartTick = options.startTick != null ? options.startTick : 0;
        long effectiveEndTick = options.endTick != null ? options.endTick : Long.MAX_VALUE;

        // Determine output format
        String format = options.format;
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
        ffmpegArgs.add("-r"); ffmpegArgs.add(String.valueOf(options.fps));
        ffmpegArgs.add("-i"); ffmpegArgs.add("-");

        switch (format.toLowerCase()) {
            case "mp4":
                ffmpegArgs.add("-c:v"); ffmpegArgs.add("libx264");
                ffmpegArgs.add("-preset"); ffmpegArgs.add(options.preset);
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
                ffmpegArgs.add("-preset"); ffmpegArgs.add(options.preset);
                break;
        }
        ffmpegArgs.add("-pix_fmt"); ffmpegArgs.add("yuv420p");

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
        final boolean showDebugOutput = options.verbose;
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
                long firstRenderableTick = ((actualMinTick / options.samplingInterval) * options.samplingInterval);
                if (firstRenderableTick < actualMinTick) {
                    firstRenderableTick += options.samplingInterval;
                }
                if (firstRenderableTick <= actualMaxTick) {
                    totalFrames = ((actualMaxTick - firstRenderableTick) / options.samplingInterval) + 1;
                }
            }
        }

        System.out.println(String.format("Video: %dx%d, %d frames, %d fps", width, height, totalFrames, options.fps));
        System.out.println(String.format("Rendering: %d thread(s), incremental (delta-optimized)", options.threadCount));

        final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
        Thread shutdownHook = null;
        final File finalOutputFile = outputFile;

        try (OutputStream ffmpegInput = ffmpeg.getOutputStream();
             WritableByteChannel channel = Channels.newChannel(ffmpegInput)) {

            AtomicLong framesWritten = new AtomicLong(0);
            long startTime = System.currentTimeMillis();
            long lastProgressUpdate = startTime;

            if (options.threadCount == 1) {
                // ============================================================
                // SINGLE-THREADED: Direct rendering and writing (no buffering)
                // ============================================================

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
                        long written = renderAndWriteChunkDirect(chunk, frameRenderer, channel,
                            effectiveStartTick, effectiveEndTick, options.samplingInterval);
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

                ExecutorService executor = Executors.newFixedThreadPool(options.threadCount);

                // Create renderer pool (one per thread)
                // Each thread needs its own renderer (not thread-safe)
                List<IVideoFrameRenderer> renderers = new ArrayList<>();
                for (int i = 0; i < options.threadCount; i++) {
                    renderers.add(frameRenderer.createThreadInstance());
                }

                final ExecutorService finalExecutor = executor;
                shutdownHook = new Thread(() -> {
                    System.out.println("\n\nShutdown requested...");
                    shutdownRequested.set(true);
                    finalExecutor.shutdownNow();
                }, "video-shutdown-hook");
                Runtime.getRuntime().addShutdownHook(shutdownHook);

                // Pipeline: submit chunks, write results in order as they complete
                // Keeps at most threadCount chunks in memory
                @SuppressWarnings("unchecked")
                java.util.concurrent.Future<RenderedChunk>[] pendingFutures = new java.util.concurrent.Future[options.threadCount];
                int head = 0;  // Next slot to write
                int tail = 0;  // Next slot to submit
                int inFlight = 0;
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

                        // If pipeline is full, wait for oldest chunk and write it
                        if (inFlight >= options.threadCount) {
                            if (!ffmpeg.isAlive() || ffmpegDied.get()) {
                                System.err.println("\nffmpeg died");
                                executor.shutdownNow();
                                return 1;
                            }

                            RenderedChunk rendered = pendingFutures[head].get();
                            for (RenderedFrame frame : rendered.frames) {
                                ByteBuffer buffer = ByteBuffer.wrap(frame.frameData);
                                buffer.order(ByteOrder.LITTLE_ENDIAN);
                                channel.write(buffer);
                                framesWritten.incrementAndGet();
                            }
                            head = (head + 1) % options.threadCount;
                            inFlight--;
                        }

                        // Submit new chunk
                        final TickDataChunk chunkToRender = chunk;
                        final IVideoFrameRenderer renderer = renderers.get(tail % renderers.size());
                        final long chunkIdx = globalChunkIndex++;

                        pendingFutures[tail] = executor.submit(() ->
                            renderChunk(chunkToRender, chunkIdx, renderer,
                                effectiveStartTick, effectiveEndTick, options.samplingInterval));
                        tail = (tail + 1) % options.threadCount;
                        inFlight++;

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

                // Write remaining chunks in pipeline
                while (inFlight > 0 && !shutdownRequested.get()) {
                    if (!ffmpeg.isAlive() || ffmpegDied.get()) {
                        System.err.println("\nffmpeg died");
                        executor.shutdownNow();
                        return 1;
                    }

                    RenderedChunk rendered = pendingFutures[head].get();
                    for (RenderedFrame frame : rendered.frames) {
                        ByteBuffer buffer = ByteBuffer.wrap(frame.frameData);
                        buffer.order(ByteOrder.LITTLE_ENDIAN);
                        channel.write(buffer);
                        framesWritten.incrementAndGet();
                    }
                    head = (head + 1) % options.threadCount;
                    inFlight--;
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
            System.out.println("Video created: " + finalOutputFile.getAbsolutePath());
        } else {
            System.err.println("ffmpeg failed with exit code " + exitCode);
            return 1;
        }

        return 0;
    }
}
