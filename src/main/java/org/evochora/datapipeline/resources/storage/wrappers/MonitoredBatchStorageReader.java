package org.evochora.datapipeline.resources.storage.wrappers;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
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
import org.evochora.datapipeline.api.resources.storage.IResourceBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

/**
 * Monitored wrapper for batch storage read operations.
 * <p>
 * Tracks per-service read metrics: batches queried, batches read, bytes read, query errors.
 * Used by services that read batches (e.g., future indexer services).
 */
public class MonitoredBatchStorageReader implements IResourceBatchStorageRead, IWrappedResource, IMonitorable {

    private final IResourceBatchStorageRead delegate;
    private final ResourceContext context;

    // Read metrics (cumulative)
    private final AtomicLong batchesRead = new AtomicLong(0);
    private final AtomicLong bytesRead = new AtomicLong(0);
    private final AtomicLong readErrors = new AtomicLong(0);
    private final AtomicLong messagesRead = new AtomicLong(0);

    // Performance metrics (sliding window using unified utils)
    private final SlidingWindowCounter readsCounter;
    private final SlidingWindowCounter bytesCounter;
    private final SlidingWindowPercentiles latencyTracker;

    public MonitoredBatchStorageReader(IResourceBatchStorageRead delegate, ResourceContext context) {
        this.delegate = delegate;
        this.context = context;
        
        // Configuration hierarchy: Context parameter > Resource option > Default (5)
        int windowSeconds = Integer.parseInt(context.parameters().getOrDefault("metricsWindowSeconds", "5"));
        
        this.readsCounter = new SlidingWindowCounter(windowSeconds);
        this.bytesCounter = new SlidingWindowCounter(windowSeconds);
        this.latencyTracker = new SlidingWindowPercentiles(windowSeconds);
    }

    /**
     * Records a read operation for performance tracking.
     * This is an O(1) operation using unified monitoring utils.
     */
    private void recordRead(long bytes, long latencyNanos) {
        readsCounter.recordCount();
        bytesCounter.recordSum(bytes);
        latencyTracker.record(latencyNanos);
    }

    @Override
    public BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults) throws IOException {
        // Simple delegation - no metrics for now (can be added later if needed)
        return delegate.listBatchFiles(prefix, continuationToken, maxResults);
    }

    @Override
    public BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults, long startTick) throws IOException {
        // Simple delegation - no metrics for now (can be added later if needed)
        return delegate.listBatchFiles(prefix, continuationToken, maxResults, startTick);
    }

    @Override
    public BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults, long startTick, long endTick) throws IOException {
        // Simple delegation - no metrics for now (can be added later if needed)
        return delegate.listBatchFiles(prefix, continuationToken, maxResults, startTick, endTick);
    }

    @Override
    public BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults,
                                               SortOrder sortOrder) throws IOException {
        // Simple delegation - no metrics for now (can be added later if needed)
        return delegate.listBatchFiles(prefix, continuationToken, maxResults, sortOrder);
    }

    @Override
    public List<TickDataChunk> readChunkBatch(StoragePath path) throws IOException {
        long startNanos = System.nanoTime();
        try {
            List<TickDataChunk> result = delegate.readChunkBatch(path);

            // Update cumulative metrics
            batchesRead.incrementAndGet();
            long bytes = result.stream().mapToLong(TickDataChunk::getSerializedSize).sum();
            bytesRead.addAndGet(bytes);

            // Record performance metrics
            long latencyNanos = System.nanoTime() - startNanos;
            recordRead(bytes, latencyNanos);

            return result;
        } catch (IOException e) {
            readErrors.incrementAndGet();
            throw e;
        }
    }

    @Override
    public <T extends MessageLite> T readMessage(StoragePath path, Parser<T> parser) throws IOException {
        long startNanos = System.nanoTime();
        try {
            T message = delegate.readMessage(path, parser);

            // Update cumulative metrics
            messagesRead.incrementAndGet();
            long bytes = message.getSerializedSize();
            bytesRead.addAndGet(bytes);

            // Record performance metrics
            long latencyNanos = System.nanoTime() - startNanos;
            recordRead(bytes, latencyNanos);

            return message;
        } catch (IOException e) {
            readErrors.incrementAndGet();
            throw e;
        }
    }

    @Override
    public List<String> listRunIds(Instant afterTimestamp) throws IOException {
        // Simple delegation, like listBatchFiles.
        return delegate.listRunIds(afterTimestamp);
    }

    @Override
    public Optional<StoragePath> findMetadataPath(String runId) throws IOException {
        // Simple delegation - no metrics for now (can be added later if needed)
        return delegate.findMetadataPath(runId);
    }

    @Override
    public String getResourceName() {
        return delegate.getResourceName() + ":" + context.serviceName();
    }

    public ResourceContext getContext() {
        return context;
    }

    @Override
    public IResource.UsageState getUsageState(String usageType) {
        // Delegate to the underlying storage resource
        return delegate.getUsageState(usageType);
    }

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "batches_read", batchesRead.get(),
            "messages_read", messagesRead.get(),
            "bytes_read", bytesRead.get(),
            "read_errors", readErrors.get(),
            "reads_per_sec", readsCounter.getRate(),
            "bytes_per_sec", bytesCounter.getRate(),
            "avg_read_latency_ms", latencyTracker.getAverage() / 1_000_000.0  // Convert nanos to ms
        );
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
}
