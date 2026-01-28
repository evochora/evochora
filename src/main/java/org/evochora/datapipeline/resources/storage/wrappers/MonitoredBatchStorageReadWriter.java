package org.evochora.datapipeline.resources.storage.wrappers;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

/**
 * Monitored wrapper for batch storage operations that require both read and write access.
 * <p>
 * This wrapper is used by services that need to both read and write to storage, such as
 * the SimulationEngine in resume mode which needs to read checkpoints and potentially
 * truncate batch files.
 * <p>
 * Tracks combined metrics: batches read/written, bytes read/written, errors, and latencies.
 */
public class MonitoredBatchStorageReadWriter implements IBatchStorageRead, IBatchStorageWrite, IWrappedResource, IMonitorable {

    private final IBatchStorageRead readDelegate;
    private final IBatchStorageWrite writeDelegate;
    private final ResourceContext context;

    // Read metrics (cumulative)
    private final AtomicLong batchesRead = new AtomicLong(0);
    private final AtomicLong bytesRead = new AtomicLong(0);
    private final AtomicLong readErrors = new AtomicLong(0);
    private final AtomicLong messagesRead = new AtomicLong(0);

    // Write metrics (cumulative)
    private final AtomicLong batchesWritten = new AtomicLong(0);
    private final AtomicLong bytesWritten = new AtomicLong(0);
    private final AtomicLong writeErrors = new AtomicLong(0);
    private final AtomicLong messagesWritten = new AtomicLong(0);

    // Performance metrics (sliding window)
    private final SlidingWindowCounter readsCounter;
    private final SlidingWindowCounter readBytesCounter;
    private final SlidingWindowPercentiles readLatencyTracker;
    private final SlidingWindowCounter writesCounter;
    private final SlidingWindowCounter writeBytesCounter;
    private final SlidingWindowPercentiles writeLatencyTracker;

    /**
     * Creates a new monitored read/write wrapper.
     *
     * @param delegate resource that implements both read and write interfaces
     * @param context resource context for metrics and configuration
     * @param <T> type that implements both IBatchStorageRead and IBatchStorageWrite
     */
    public <T extends IBatchStorageRead & IBatchStorageWrite> MonitoredBatchStorageReadWriter(T delegate, ResourceContext context) {
        this.readDelegate = delegate;
        this.writeDelegate = delegate;
        this.context = context;

        int windowSeconds = Integer.parseInt(context.parameters().getOrDefault("metricsWindowSeconds", "5"));

        this.readsCounter = new SlidingWindowCounter(windowSeconds);
        this.readBytesCounter = new SlidingWindowCounter(windowSeconds);
        this.readLatencyTracker = new SlidingWindowPercentiles(windowSeconds);
        this.writesCounter = new SlidingWindowCounter(windowSeconds);
        this.writeBytesCounter = new SlidingWindowCounter(windowSeconds);
        this.writeLatencyTracker = new SlidingWindowPercentiles(windowSeconds);
    }

    // ========================================================================
    // IBatchStorageRead implementation
    // ========================================================================

    @Override
    public Optional<StoragePath> findMetadataPath(String runId) throws IOException {
        return readDelegate.findMetadataPath(runId);
    }

    @Override
    public List<String> listRunIds(Instant afterTimestamp) throws IOException {
        return readDelegate.listRunIds(afterTimestamp);
    }

    @Override
    public BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults)
            throws IOException {
        return readDelegate.listBatchFiles(prefix, continuationToken, maxResults);
    }

    @Override
    public BatchFileListResult listBatchFiles(String prefix, String continuationToken, int limit, SortOrder sortOrder)
            throws IOException {
        return readDelegate.listBatchFiles(prefix, continuationToken, limit, sortOrder);
    }

    @Override
    public BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults, long startTick)
            throws IOException {
        return readDelegate.listBatchFiles(prefix, continuationToken, maxResults, startTick);
    }

    @Override
    public BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults,
            long startTick, long endTick) throws IOException {
        return readDelegate.listBatchFiles(prefix, continuationToken, maxResults, startTick, endTick);
    }

    @Override
    public Optional<StoragePath> findLastBatchFile(String runIdPrefix) throws IOException {
        return readDelegate.findLastBatchFile(runIdPrefix);
    }

    @Override
    public List<TickDataChunk> readChunkBatch(StoragePath path) throws IOException {
        long startNanos = System.nanoTime();
        try {
            List<TickDataChunk> chunks = readDelegate.readChunkBatch(path);

            batchesRead.incrementAndGet();
            long bytes = chunks.stream().mapToLong(TickDataChunk::getSerializedSize).sum();
            bytesRead.addAndGet(bytes);

            long latencyNanos = System.nanoTime() - startNanos;
            readsCounter.recordCount();
            readBytesCounter.recordSum(bytes);
            readLatencyTracker.record(latencyNanos);

            return chunks;
        } catch (IOException e) {
            readErrors.incrementAndGet();
            throw e;
        }
    }

    @Override
    public <T extends MessageLite> T readMessage(StoragePath path, Parser<T> parser) throws IOException {
        long startNanos = System.nanoTime();
        try {
            T message = readDelegate.readMessage(path, parser);

            messagesRead.incrementAndGet();
            long bytes = message.getSerializedSize();
            bytesRead.addAndGet(bytes);

            long latencyNanos = System.nanoTime() - startNanos;
            readsCounter.recordCount();
            readBytesCounter.recordSum(bytes);
            readLatencyTracker.record(latencyNanos);

            return message;
        } catch (IOException e) {
            readErrors.incrementAndGet();
            throw e;
        }
    }

    // ========================================================================
    // IBatchStorageWrite implementation
    // ========================================================================

    @Override
    public StoragePath writeChunkBatch(List<TickDataChunk> batch, long firstTick, long lastTick) throws IOException {
        long startNanos = System.nanoTime();
        try {
            StoragePath path = writeDelegate.writeChunkBatch(batch, firstTick, lastTick);

            batchesWritten.incrementAndGet();
            long bytes = batch.stream().mapToLong(TickDataChunk::getSerializedSize).sum();
            bytesWritten.addAndGet(bytes);

            long latencyNanos = System.nanoTime() - startNanos;
            writesCounter.recordCount();
            writeBytesCounter.recordSum(bytes);
            writeLatencyTracker.record(latencyNanos);

            return path;
        } catch (IOException e) {
            writeErrors.incrementAndGet();
            throw e;
        }
    }

    @Override
    public <T extends MessageLite> StoragePath writeMessage(String key, T message) throws IOException {
        long startNanos = System.nanoTime();
        try {
            StoragePath path = writeDelegate.writeMessage(key, message);

            messagesWritten.incrementAndGet();
            long bytes = message.getSerializedSize();
            bytesWritten.addAndGet(bytes);

            long latencyNanos = System.nanoTime() - startNanos;
            writesCounter.recordCount();
            writeBytesCounter.recordSum(bytes);
            writeLatencyTracker.record(latencyNanos);

            return path;
        } catch (IOException e) {
            writeErrors.incrementAndGet();
            throw e;
        }
    }

    @Override
    public void moveToSuperseded(StoragePath path) throws IOException {
        writeDelegate.moveToSuperseded(path);
    }

    // ========================================================================
    // IResource implementation
    // ========================================================================

    @Override
    public String getResourceName() {
        return readDelegate.getResourceName() + ":" + context.serviceName();
    }

    @Override
    public IResource.UsageState getUsageState(String usageType) {
        return readDelegate.getUsageState(usageType);
    }

    // ========================================================================
    // IMonitorable implementation
    // ========================================================================

    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new HashMap<>();
        // Read metrics
        metrics.put("batches_read", batchesRead.get());
        metrics.put("messages_read", messagesRead.get());
        metrics.put("bytes_read", bytesRead.get());
        metrics.put("read_errors", readErrors.get());
        metrics.put("reads_per_sec", readsCounter.getRate());
        metrics.put("read_bytes_per_sec", readBytesCounter.getRate());
        metrics.put("avg_read_latency_ms", readLatencyTracker.getAverage() / 1_000_000.0);
        // Write metrics
        metrics.put("batches_written", batchesWritten.get());
        metrics.put("messages_written", messagesWritten.get());
        metrics.put("bytes_written", bytesWritten.get());
        metrics.put("write_errors", writeErrors.get());
        metrics.put("writes_per_sec", writesCounter.getRate());
        metrics.put("write_bytes_per_sec", writeBytesCounter.getRate());
        metrics.put("avg_write_latency_ms", writeLatencyTracker.getAverage() / 1_000_000.0);
        return metrics;
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public List<OperationalError> getErrors() {
        return Collections.emptyList();
    }

    @Override
    public void clearErrors() {
        // No-op
    }

    /**
     * Returns the resource context.
     *
     * @return the context
     */
    public ResourceContext getContext() {
        return context;
    }
}
