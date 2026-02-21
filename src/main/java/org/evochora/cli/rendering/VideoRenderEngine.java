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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.cli.CliResourceFactory;
import org.evochora.datapipeline.api.contracts.DeltaType;
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
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;

/**
 * Video rendering engine that orchestrates the rendering pipeline.
 * <p>
 * Handles config loading, storage access, ffmpeg process management,
 * and single/multi-threaded rendering loops with optimized sampling support.
 */
public class VideoRenderEngine {

    private static final String CONFIG_FILE_NAME = "evochora.conf";

    private final VideoRenderOptions options;
    private final IVideoFrameRenderer frameRenderer;

    public VideoRenderEngine(VideoRenderOptions options, IVideoFrameRenderer frameRenderer) {
        this.options = options;
        this.frameRenderer = frameRenderer;
    }

    /**
     * Executes the video rendering pipeline.
     *
     * @return Exit code (0 for success, non-zero for failure).
     */
    public Integer execute() throws Exception {
        // Load configuration
        Config config = loadConfig();
        if (config == null) return 1;

        // Initialize storage
        IBatchStorageRead storage = initializeStorage(config);
        if (storage == null) return 1;

        // Resolve run ID
        String targetRunId = resolveRunId(storage);
        if (targetRunId == null) return 1;

        // Load metadata and initialize renderer
        EnvironmentProperties envProps = loadMetadataAndInit(storage, targetRunId);
        if (envProps == null) return 1;

        // Scan batch files and determine tick range
        BatchScanResult scanResult = scanBatchFiles(storage, targetRunId);

        // Calculate effective tick range
        long effectiveStartTick = options.startTick != null ? options.startTick : 0;
        long effectiveEndTick = options.endTick != null ? options.endTick : Long.MAX_VALUE;
        long totalFrames = calculateTotalFrames(scanResult, effectiveStartTick, effectiveEndTick);

        // Resolve output file and format
        File outputFile = resolveOutputFile();
        String format = resolveFormat(outputFile);

        // Start ffmpeg
        Process ffmpeg = startFfmpeg(outputFile, format);
        if (ffmpeg == null) return 1;

        // Start ffmpeg output reader
        AtomicBoolean ffmpegDied = new AtomicBoolean(false);
        Thread outputReader = startFfmpegOutputReader(ffmpeg, ffmpegDied);

        int width = frameRenderer.getImageWidth();
        int height = frameRenderer.getImageHeight();
        System.out.println(String.format("Video: %dx%d, %d frames, %d fps", width, height, totalFrames, options.fps));

        // Render
        AtomicBoolean shutdownRequested = new AtomicBoolean(false);
        Thread shutdownHook = null;

        try (OutputStream ffmpegInput = ffmpeg.getOutputStream();
             WritableByteChannel channel = Channels.newChannel(ffmpegInput)) {

            AtomicLong framesWritten = new AtomicLong(0);
            long startTime = System.currentTimeMillis();

            if (options.threadCount == 1) {
                shutdownHook = createShutdownHook(shutdownRequested, null);
                Runtime.getRuntime().addShutdownHook(shutdownHook);

                renderSingleThreaded(storage, scanResult.batchPaths, channel,
                    effectiveStartTick, effectiveEndTick, framesWritten,
                    totalFrames, startTime, shutdownRequested, ffmpeg, ffmpegDied);
            } else {
                ExecutorService executor = Executors.newFixedThreadPool(options.threadCount);
                shutdownHook = createShutdownHook(shutdownRequested, executor);
                Runtime.getRuntime().addShutdownHook(shutdownHook);

                renderMultiThreaded(storage, scanResult.batchPaths, channel, executor,
                    effectiveStartTick, effectiveEndTick, framesWritten,
                    totalFrames, startTime, shutdownRequested, ffmpeg, ffmpegDied);

                executor.shutdown();
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            }

            removeShutdownHook(shutdownHook);
            System.out.println("\nFinished rendering. Closing...");

        } catch (Exception e) {
            removeShutdownHook(shutdownHook);
            throw e;
        }

        // Wait for ffmpeg to finish
        try {
            outputReader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int exitCode = ffmpeg.waitFor();
        if (exitCode == 0) {
            System.out.println("Video created: " + outputFile.getAbsolutePath());
            return 0;
        } else {
            System.err.println("ffmpeg failed with exit code " + exitCode);
            return 1;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Chunk rendering (unified logic for both single/multi-threaded)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Processes a chunk and renders frames at sample tick intervals.
     * Returns rendered frames as pixel arrays (int[]) for zero-copy writing.
     */
    private List<int[]> renderChunkPixels(TickDataChunk chunk, IVideoFrameRenderer renderer,
                                          long effectiveStartTick, long effectiveEndTick,
                                          int samplingInterval) {
        List<int[]> frames = new ArrayList<>();

        if (samplingInterval == 1) {
            // Incremental rendering - every tick
            renderChunkIncremental(chunk, renderer, effectiveStartTick, effectiveEndTick, frames);
        } else {
            // Sampling mode - skip to sample ticks using accumulated deltas
            renderChunkSampled(chunk, renderer, effectiveStartTick, effectiveEndTick, samplingInterval, frames);
        }

        return frames;
    }

    private void renderChunkIncremental(TickDataChunk chunk, IVideoFrameRenderer renderer,
                                        long effectiveStartTick, long effectiveEndTick,
                                        List<int[]> frames) {
        // Render snapshot
        TickData snapshot = chunk.getSnapshot();
        long snapshotTick = snapshot.getTickNumber();
        int[] pixelData = renderer.renderSnapshot(snapshot);

        if (snapshotTick >= effectiveStartTick && snapshotTick <= effectiveEndTick) {
            frames.add(pixelData.clone());  // Clone because frameBuffer is reused
        }

        // Render deltas
        for (TickDelta delta : chunk.getDeltasList()) {
            long deltaTick = delta.getTickNumber();
            pixelData = renderer.renderDelta(delta);

            if (deltaTick >= effectiveStartTick && deltaTick <= effectiveEndTick) {
                frames.add(pixelData.clone());
            }
        }
    }

    private void renderChunkSampled(TickDataChunk chunk, IVideoFrameRenderer renderer,
                                    long effectiveStartTick, long effectiveEndTick,
                                    int samplingInterval, List<int[]> frames) {
        long chunkFirstTick = chunk.getFirstTick();
        long chunkLastTick = chunk.getLastTick();

        // Calculate sample tick range within this chunk
        long rangeStart = Math.max(chunkFirstTick, effectiveStartTick);
        long rangeEnd = Math.min(chunkLastTick, effectiveEndTick);
        long firstSampleInRange = ceilToMultiple(rangeStart, samplingInterval);

        if (firstSampleInRange > rangeEnd) {
            return; // No sample ticks in this chunk
        }

        TickData snapshot = chunk.getSnapshot();
        long snapshotTick = snapshot.getTickNumber();

        // Check if snapshot is a sample tick
        if (snapshotTick >= effectiveStartTick && snapshotTick <= effectiveEndTick
                && snapshotTick % samplingInterval == 0) {
            int[] pixelData = renderer.renderSnapshot(snapshot);
            frames.add(pixelData.clone());
        }

        // Process remaining sample ticks
        List<TickDelta> deltas = chunk.getDeltasList();
        long currentSampleTick = (firstSampleInRange == snapshotTick)
            ? firstSampleInRange + samplingInterval
            : firstSampleInRange;

        while (currentSampleTick <= rangeEnd) {
            // Reset state from snapshot
            renderer.applySnapshotState(snapshot);

            // Find best starting point (latest accumulated delta <= currentSampleTick)
            int startIdx = findAccumulatedDeltaIndex(deltas, currentSampleTick);

            // Apply deltas up to current sample tick
            for (int i = startIdx; i < deltas.size(); i++) {
                TickDelta delta = deltas.get(i);
                if (delta.getTickNumber() > currentSampleTick) break;
                renderer.applyDeltaState(delta);
            }

            // Render and store
            int[] pixelData = renderer.renderCurrentState();
            frames.add(pixelData.clone());

            currentSampleTick += samplingInterval;
        }
    }

    private int findAccumulatedDeltaIndex(List<TickDelta> deltas, long targetTick) {
        int startIdx = 0;
        for (int i = 0; i < deltas.size(); i++) {
            TickDelta d = deltas.get(i);
            if (d.getTickNumber() > targetTick) break;
            if (d.getDeltaType() == DeltaType.ACCUMULATED) {
                startIdx = i;
            }
        }
        return startIdx;
    }

    private long ceilToMultiple(long value, int multiple) {
        return ((value + multiple - 1) / multiple) * multiple;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Batch file filtering (unified logic)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Checks if a batch file should be processed based on tick range and sampling interval.
     * Returns true if the batch contains sample ticks within the effective range.
     */
    private boolean shouldProcessBatch(StoragePath batchPath, long effectiveStartTick,
                                       long effectiveEndTick, int samplingInterval) {
        if (samplingInterval == 1) {
            return true; // Process all batches when not sampling
        }

        // Parse tick range from filename (e.g., "batch_0_99.pb")
        String filename = batchPath.asString();
        int batchIdx = filename.lastIndexOf("/batch_");
        if (batchIdx < 0) return true;

        String batchName = filename.substring(batchIdx + 7);
        int firstUnderscore = batchName.indexOf('_');
        int dotPbIdx = batchName.indexOf(".pb");
        if (firstUnderscore <= 0 || dotPbIdx <= firstUnderscore) return true;

        try {
            long batchStartTick = Long.parseLong(batchName.substring(0, firstUnderscore));
            long batchEndTick = Long.parseLong(batchName.substring(firstUnderscore + 1, dotPbIdx));

            // Check if batch overlaps with effective range
            long rangeStart = Math.max(batchStartTick, effectiveStartTick);
            long rangeEnd = Math.min(batchEndTick, effectiveEndTick);
            if (rangeStart > rangeEnd) return false;

            // Check if batch contains any sample ticks
            long firstSampleInRange = ceilToMultiple(rangeStart, samplingInterval);
            return firstSampleInRange <= rangeEnd;

        } catch (NumberFormatException e) {
            return true; // Can't parse, process anyway
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Single-threaded rendering
    // ─────────────────────────────────────────────────────────────────────────────

    private void renderSingleThreaded(IBatchStorageRead storage, List<StoragePath> batchPaths,
                                      WritableByteChannel channel, long effectiveStartTick,
                                      long effectiveEndTick, AtomicLong framesWritten,
                                      long totalFrames, long startTime,
                                      AtomicBoolean shutdownRequested,
                                      Process ffmpeg, AtomicBoolean ffmpegDied) throws Exception {
        System.out.println("Rendering: 1 thread, incremental (delta-optimized)");

        // Direct buffer for zero-copy writes (int[] → channel without byte conversion)
        int frameSize = frameRenderer.getImageWidth() * frameRenderer.getImageHeight() * 4;
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(frameSize).order(ByteOrder.LITTLE_ENDIAN);
        long lastProgressUpdate = startTime;

        for (StoragePath batchPath : batchPaths) {
            if (shutdownRequested.get()) break;
            if (!shouldProcessBatch(batchPath, effectiveStartTick, effectiveEndTick, options.samplingInterval)) {
                continue;
            }

            // Collect all chunks: render loop needs random-access control flow (shutdown checks, progress tracking)
            List<TickDataChunk> chunks = new ArrayList<>();
            storage.forEachChunk(batchPath, chunks::add);

            for (TickDataChunk chunk : chunks) {
                if (shutdownRequested.get()) break;
                if (chunk.getLastTick() < effectiveStartTick || chunk.getFirstTick() > effectiveEndTick) {
                    continue;
                }
                if (!ffmpeg.isAlive() || ffmpegDied.get()) {
                    System.err.println("\nffmpeg died unexpectedly");
                    throw new RuntimeException("ffmpeg died");
                }

                // Render and write frames directly - zero-copy via DirectByteBuffer
                long written = renderChunkDirect(chunk, frameRenderer, channel, directBuffer,
                    effectiveStartTick, effectiveEndTick, options.samplingInterval);
                framesWritten.addAndGet(written);

                lastProgressUpdate = updateProgress(framesWritten.get(), totalFrames, startTime, lastProgressUpdate);
            }
        }
    }

    /**
     * Renders a chunk and writes frames directly to channel.
     * Uses zero-copy: int[] pixels written directly as bytes (bgr0 format).
     */
    private long renderChunkDirect(TickDataChunk chunk, IVideoFrameRenderer renderer,
                                   WritableByteChannel channel, ByteBuffer directBuffer,
                                   long effectiveStartTick, long effectiveEndTick,
                                   int samplingInterval) throws java.io.IOException {
        long written = 0;

        if (samplingInterval == 1) {
            // Incremental: render and write each frame immediately
            TickData snapshot = chunk.getSnapshot();
            long snapshotTick = snapshot.getTickNumber();
            int[] pixelData = renderer.renderSnapshot(snapshot);

            if (snapshotTick >= effectiveStartTick && snapshotTick <= effectiveEndTick) {
                writePixelsDirect(channel, directBuffer, pixelData);
                written++;
            }

            for (TickDelta delta : chunk.getDeltasList()) {
                long deltaTick = delta.getTickNumber();
                pixelData = renderer.renderDelta(delta);

                if (deltaTick >= effectiveStartTick && deltaTick <= effectiveEndTick) {
                    writePixelsDirect(channel, directBuffer, pixelData);
                    written++;
                }
            }
        } else {
            // Sampling mode: use pixel-based rendering with zero-copy write
            List<int[]> frames = renderChunkPixels(chunk, renderer,
                effectiveStartTick, effectiveEndTick, samplingInterval);
            for (int[] pixelData : frames) {
                writePixelsDirect(channel, directBuffer, pixelData);
                written++;
            }
        }

        return written;
    }

    /**
     * Writes pixel data directly to channel without byte-by-byte conversion.
     * Java int[] in little-endian memory = BGR0 format that ffmpeg expects.
     */
    private void writePixelsDirect(WritableByteChannel channel, ByteBuffer directBuffer,
                                   int[] pixelData) throws java.io.IOException {
        directBuffer.clear();
        directBuffer.asIntBuffer().put(pixelData);
        directBuffer.rewind();
        channel.write(directBuffer);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Multi-threaded rendering
    // ─────────────────────────────────────────────────────────────────────────────

    private void renderMultiThreaded(IBatchStorageRead storage, List<StoragePath> batchPaths,
                                     WritableByteChannel channel, ExecutorService executor,
                                     long effectiveStartTick, long effectiveEndTick,
                                     AtomicLong framesWritten, long totalFrames, long startTime,
                                     AtomicBoolean shutdownRequested,
                                     Process ffmpeg, AtomicBoolean ffmpegDied) throws Exception {
        // Create renderer pool (one per thread)
        List<IVideoFrameRenderer> renderers = new ArrayList<>();
        for (int i = 0; i < options.threadCount; i++) {
            renderers.add(frameRenderer.createThreadInstance());
        }

        // Calculate safe in-flight limit based on memory
        // Each chunk can have ~100 frames, each frame = width * height * 4 bytes
        int frameSize = frameRenderer.getImageWidth() * frameRenderer.getImageHeight() * 4;
        long estimatedChunkBytes = (long) frameSize * 100;  // ~100 frames per chunk
        long maxHeap = Runtime.getRuntime().maxMemory();
        long safeMemoryBudget = maxHeap / 2;  // Use at most half the heap for buffering
        int maxInFlight = Math.max(2, (int) (safeMemoryBudget / estimatedChunkBytes));
        int pipelineSize = Math.min(options.threadCount, maxInFlight);

        if (pipelineSize < options.threadCount) {
            System.out.println(String.format("Rendering: %d/%d thread(s), memory-limited (frame: %.1f MB)",
                pipelineSize, options.threadCount, frameSize / 1e6));
        } else {
            System.out.println(String.format("Rendering: %d thread(s), incremental (delta-optimized)",
                options.threadCount));
        }

        // Direct buffer for zero-copy writes (shared, used only by main thread)
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(frameSize).order(ByteOrder.LITTLE_ENDIAN);

        // Pipeline: submit chunks, write results in order as they complete
        @SuppressWarnings("unchecked")
        Future<List<int[]>>[] pendingFutures = new Future[pipelineSize];
        int head = 0;  // Next slot to write
        int tail = 0;  // Next slot to submit
        int inFlight = 0;
        long lastProgressUpdate = startTime;

        for (StoragePath batchPath : batchPaths) {
            if (shutdownRequested.get()) break;
            if (!shouldProcessBatch(batchPath, effectiveStartTick, effectiveEndTick, options.samplingInterval)) {
                continue;
            }

            // Collect all chunks: render loop needs random-access control flow (shutdown checks, progress tracking)
            List<TickDataChunk> chunks = new ArrayList<>();
            storage.forEachChunk(batchPath, chunks::add);

            for (TickDataChunk chunk : chunks) {
                if (shutdownRequested.get()) break;
                if (chunk.getLastTick() < effectiveStartTick || chunk.getFirstTick() > effectiveEndTick) {
                    continue;
                }

                // If pipeline is full, wait for oldest chunk and write it
                if (inFlight >= pipelineSize) {
                    if (!ffmpeg.isAlive() || ffmpegDied.get()) {
                        System.err.println("\nffmpeg died");
                        executor.shutdownNow();
                        throw new RuntimeException("ffmpeg died");
                    }

                    writeCompletedPixels(pendingFutures[head].get(), channel, directBuffer, framesWritten);
                    head = (head + 1) % pipelineSize;
                    inFlight--;
                }

                // Submit new chunk (check shutdown to avoid RejectedExecutionException)
                if (shutdownRequested.get()) break;

                final TickDataChunk chunkToRender = chunk;
                final IVideoFrameRenderer renderer = renderers.get(tail % renderers.size());
                final long effStart = effectiveStartTick;
                final long effEnd = effectiveEndTick;
                final int sampling = options.samplingInterval;

                try {
                    pendingFutures[tail] = executor.submit(() ->
                        renderChunkPixels(chunkToRender, renderer, effStart, effEnd, sampling));
                    tail = (tail + 1) % pipelineSize;
                    inFlight++;
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    // Executor was shut down between check and submit - exit gracefully
                    break;
                }

                lastProgressUpdate = updateProgress(framesWritten.get(), totalFrames, startTime, lastProgressUpdate);
            }
        }

        // Write remaining chunks in pipeline
        while (inFlight > 0 && !shutdownRequested.get()) {
            if (!ffmpeg.isAlive() || ffmpegDied.get()) {
                System.err.println("\nffmpeg died");
                executor.shutdownNow();
                throw new RuntimeException("ffmpeg died");
            }

            writeCompletedPixels(pendingFutures[head].get(), channel, directBuffer, framesWritten);
            head = (head + 1) % pipelineSize;
            inFlight--;
        }
    }

    /**
     * Writes completed pixel frames using zero-copy DirectByteBuffer.
     */
    private void writeCompletedPixels(List<int[]> frames, WritableByteChannel channel,
                                      ByteBuffer directBuffer, AtomicLong framesWritten) throws Exception {
        for (int[] pixelData : frames) {
            writePixelsDirect(channel, directBuffer, pixelData);
            framesWritten.incrementAndGet();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Progress reporting
    // ─────────────────────────────────────────────────────────────────────────────

    private long updateProgress(long totalWritten, long totalFrames, long startTime, long lastUpdate) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastUpdate < 500) return lastUpdate;

        long elapsed = currentTime - startTime;
        double fps = totalWritten > 0 ? (totalWritten * 1000.0) / elapsed : 0;
        long remaining = totalFrames > 0 && fps > 0
            ? (long) (((totalFrames - totalWritten) * 1000.0) / fps) : 0;

        if (totalFrames > 0) {
            int pct = (int) ((totalWritten * 100) / totalFrames);
            int barWidth = 40;
            int filled = (int) ((totalWritten * barWidth) / totalFrames);
            StringBuilder bar = new StringBuilder("[");
            for (int i = 0; i < barWidth; i++) {
                bar.append(i < filled ? "=" : " ");
            }
            bar.append("]");
            System.out.print(String.format("\r%s %d%% | Frame %d/%d | %.1f fps | Elapsed: %s | ETA: %s",
                bar, pct, totalWritten, totalFrames, fps, formatTime(elapsed), formatTime(remaining)));
        } else {
            System.out.print(String.format("\rFrame %d | %.1f fps | Elapsed: %s",
                totalWritten, fps, formatTime(elapsed)));
        }
        System.out.flush();

        return currentTime;
    }

    private String formatTime(long ms) {
        if (ms < 0) return "?";
        long sec = ms / 1000;
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Initialization helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private Config loadConfig() {
        try {
            if (options.configFile != null) {
                if (!options.configFile.exists()) {
                    System.err.println("Configuration file not found: " + options.configFile.getAbsolutePath());
                    return null;
                }
                System.out.println("Using configuration file: " + options.configFile.getAbsolutePath());
                return ConfigFactory.systemProperties()
                    .withFallback(ConfigFactory.systemEnvironment())
                    .withFallback(ConfigFactory.parseFile(options.configFile))
                    .withFallback(ConfigFactory.load())
                    .resolve();
            }

            File defaultConf = new File(CONFIG_FILE_NAME);
            if (defaultConf.exists()) {
                System.out.println("Using configuration file: " + defaultConf.getAbsolutePath());
                return ConfigFactory.systemProperties()
                    .withFallback(ConfigFactory.systemEnvironment())
                    .withFallback(ConfigFactory.parseFile(defaultConf))
                    .withFallback(ConfigFactory.load())
                    .resolve();
            }

            System.out.println("Warning: No 'evochora.conf' found. Using defaults.");
            return ConfigFactory.systemProperties()
                .withFallback(ConfigFactory.systemEnvironment())
                .withFallback(ConfigFactory.load())
                .resolve();

        } catch (ConfigException e) {
            System.err.println("Failed to load configuration: " + e.getMessage());
            return null;
        }
    }

    private IBatchStorageRead initializeStorage(Config config) {
        String path = "pipeline.resources." + options.storageName;
        if (!config.hasPath(path)) {
            System.err.println("Storage resource '" + options.storageName + "' not configured.");
            return null;
        }
        IBatchStorageRead storage = CliResourceFactory.create("cli-video-renderer",
            IBatchStorageRead.class, config.getConfig(path));
        System.out.println("Using storage: " + options.storageName + " (" + storage.getClass().getSimpleName() + ")");
        return storage;
    }

    private String resolveRunId(IBatchStorageRead storage) throws java.io.IOException {
        if (options.runId != null) return options.runId;

        System.out.println("No run-id specified, discovering latest run...");
        List<String> runIds = storage.listRunIds(java.time.Instant.EPOCH);
        if (runIds.isEmpty()) {
            System.err.println("No simulation runs found.");
            return null;
        }
        String runId = runIds.get(runIds.size() - 1);
        System.out.println("Found latest run: " + runId);
        return runId;
    }

    private EnvironmentProperties loadMetadataAndInit(IBatchStorageRead storage, String runId)
            throws java.io.IOException {
        System.out.println("Reading metadata...");
        java.util.Optional<StoragePath> metaPath = storage.findMetadataPath(runId);
        if (metaPath.isEmpty()) {
            System.err.println("Metadata not found for run: " + runId);
            return null;
        }

        SimulationMetadata metadata = storage.readMessage(metaPath.get(), SimulationMetadata.parser());
        EnvironmentProperties envProps = new EnvironmentProperties(
            MetadataConfigHelper.getEnvironmentShape(metadata),
            MetadataConfigHelper.isEnvironmentToroidal(metadata));

        frameRenderer.init(envProps);
        frameRenderer.setOverlays(loadOverlays(options.overlayNames));

        return envProps;
    }

    private List<IOverlayRenderer> loadOverlays(List<String> names) {
        List<IOverlayRenderer> overlays = new ArrayList<>();
        if (names == null || names.isEmpty()) return overlays;

        for (String name : names) {
            String className = "org.evochora.cli.rendering.overlay."
                + name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase()
                + "OverlayRenderer";
            try {
                Class<?> clazz = Class.forName(className);
                overlays.add((IOverlayRenderer) clazz.getDeclaredConstructor().newInstance());
            } catch (Exception e) {
                System.err.println("Warning: Failed to load overlay '" + name + "': " + e.getMessage());
            }
        }
        return overlays;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Batch scanning
    // ─────────────────────────────────────────────────────────────────────────────

    private static class BatchScanResult {
        final List<StoragePath> batchPaths;
        final long minTick;
        final long maxTick;

        BatchScanResult(List<StoragePath> batchPaths, long minTick, long maxTick) {
            this.batchPaths = batchPaths;
            this.minTick = minTick;
            this.maxTick = maxTick;
        }
    }

    private BatchScanResult scanBatchFiles(IBatchStorageRead storage, String runId)
            throws java.io.IOException {
        System.out.print("Scanning batch files... ");

        List<StoragePath> paths = new ArrayList<>();
        long minTick = Long.MAX_VALUE;
        long maxTick = -1;

        String token = null;
        do {
            BatchFileListResult result = storage.listBatchFiles(runId + "/raw/", token, 1000);
            for (StoragePath path : result.getFilenames()) {
                paths.add(path);

                // Parse tick range from filename
                String filename = path.asString();
                int batchIdx = filename.lastIndexOf("/batch_");
                if (batchIdx >= 0) {
                    String batchName = filename.substring(batchIdx + 7);
                    int underscore = batchName.indexOf('_');
                    int dot = batchName.indexOf(".pb");
                    if (underscore > 0 && dot > underscore) {
                        try {
                            long start = Long.parseLong(batchName.substring(0, underscore));
                            long end = Long.parseLong(batchName.substring(underscore + 1, dot));
                            if (start < minTick) minTick = start;
                            if (end > maxTick) maxTick = end;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            token = result.getNextContinuationToken();
        } while (token != null);

        System.out.println(String.format("%d files found, tick range: %d-%d",
            paths.size(), minTick < Long.MAX_VALUE ? minTick : 0, maxTick));

        return new BatchScanResult(paths, minTick, maxTick);
    }

    private long calculateTotalFrames(BatchScanResult scan, long effectiveStart, long effectiveEnd) {
        if (scan.maxTick < 0) return 0;

        long actualMin = Math.max(scan.minTick < Long.MAX_VALUE ? scan.minTick : 0, effectiveStart);
        long actualMax = Math.min(scan.maxTick, effectiveEnd);
        if (actualMax < actualMin) return 0;

        long firstRenderable = ceilToMultiple(actualMin, options.samplingInterval);
        if (firstRenderable > actualMax) return 0;

        return ((actualMax - firstRenderable) / options.samplingInterval) + 1;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // FFmpeg management
    // ─────────────────────────────────────────────────────────────────────────────

    private File resolveOutputFile() {
        File file = options.outputFile;
        String path = file.getPath();

        if (path.startsWith("~/") || path.equals("~")) {
            path = path.replaceFirst("^~", System.getProperty("user.home"));
            return new File(path);
        }
        return file.isAbsolute() ? file : file.getAbsoluteFile();
    }

    private String resolveFormat(File outputFile) {
        String path = outputFile.getAbsolutePath().toLowerCase();
        String format = options.format.toLowerCase();

        int lastDot = path.lastIndexOf('.');
        String ext = lastDot > 0 ? path.substring(lastDot + 1) : "";

        return ext.equals(format) ? ext : format;
    }

    private Process startFfmpeg(File outputFile, String format) {
        int width = frameRenderer.getImageWidth();
        int height = frameRenderer.getImageHeight();

        List<String> args = new ArrayList<>();
        args.add("ffmpeg");
        args.add("-y");
        args.add("-f"); args.add("rawvideo");
        args.add("-vcodec"); args.add("rawvideo");
        args.add("-s"); args.add(width + "x" + height);
        args.add("-pix_fmt"); args.add("bgr0");
        args.add("-r"); args.add(String.valueOf(options.fps));
        args.add("-i"); args.add("-");

        switch (format) {
            case "mp4":
                args.add("-c:v"); args.add("libx264");
                args.add("-preset"); args.add(options.preset);
                args.add("-crf"); args.add("18");
                args.add("-movflags"); args.add("+frag_keyframe+empty_moov");
                break;
            case "webm":
                args.add("-c:v"); args.add("libvpx-vp9");
                args.add("-crf"); args.add("10");
                args.add("-b:v"); args.add("0");
                break;
            default:
                args.add("-c:v"); args.add("libx264");
                args.add("-preset"); args.add(options.preset);
                break;
        }
        args.add("-pix_fmt"); args.add("yuv420p");
        args.add(outputFile.getAbsolutePath());

        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            return pb.start();
        } catch (Exception e) {
            System.err.println("Failed to start ffmpeg: " + e.getMessage());
            return null;
        }
    }

    private Thread startFfmpegOutputReader(Process ffmpeg, AtomicBoolean ffmpegDied) {
        Thread reader = new Thread(() -> {
            try (java.io.InputStream stream = ffmpeg.getInputStream()) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = stream.read(buffer)) != -1) {
                    if (options.verbose) {
                        System.err.print("[ffmpeg] " + new String(buffer, 0, bytesRead));
                    }
                }
            } catch (Exception e) {
                if (!ffmpeg.isAlive()) ffmpegDied.set(true);
            }
        }, "ffmpeg-output-reader");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private Thread createShutdownHook(AtomicBoolean shutdownRequested, ExecutorService executor) {
        return new Thread(() -> {
            System.out.println("\n\nShutdown requested...");
            shutdownRequested.set(true);
            if (executor != null) executor.shutdownNow();
        }, "video-shutdown-hook");
    }

    private void removeShutdownHook(Thread hook) {
        if (hook == null) return;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {}
    }
}
