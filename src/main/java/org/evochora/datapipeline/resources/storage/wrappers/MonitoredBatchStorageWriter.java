package org.evochora.datapipeline.resources.storage.wrappers;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.storage.StreamingWriteResult;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;

import com.google.protobuf.MessageLite;

/**
 * Monitored wrapper for batch storage write operations.
 * <p>
 * Tracks per-service write metrics: batches written, bytes written, write errors.
 * Used by services that write batches (e.g., PersistenceService).
 */
public class MonitoredBatchStorageWriter implements IBatchStorageWrite, IWrappedResource, IMonitorable {

    private final IBatchStorageWrite delegate;
    private final ResourceContext context;

    // Write metrics (cumulative)
    private final AtomicLong batchesWritten = new AtomicLong(0);
    private final AtomicLong bytesWritten = new AtomicLong(0);
    private final AtomicLong writeErrors = new AtomicLong(0);
    private final AtomicLong messagesWritten = new AtomicLong(0);

    // Performance metrics (sliding window using unified utils)
    private final SlidingWindowCounter batchesCounter;
    private final SlidingWindowCounter bytesCounter;
    private final SlidingWindowPercentiles latencyTracker;

    public MonitoredBatchStorageWriter(IBatchStorageWrite delegate, ResourceContext context) {
        this.delegate = delegate;
        this.context = context;
        
        // Configuration hierarchy: Context parameter > Resource option > Default (5)
        int windowSeconds = Integer.parseInt(context.parameters().getOrDefault("metricsWindowSeconds", "5"));
        
        this.batchesCounter = new SlidingWindowCounter(windowSeconds);
        this.bytesCounter = new SlidingWindowCounter(windowSeconds);
        this.latencyTracker = new SlidingWindowPercentiles(windowSeconds);
    }

    /**
     * Records a write operation for performance tracking.
     * This is an O(1) operation using unified monitoring utils.
     */
    private void recordWrite(int batchSize, long bytes, long latencyNanos) {
        batchesCounter.recordCount();
        bytesCounter.recordSum(bytes);
        latencyTracker.record(latencyNanos);
    }

    @Override
    public <T extends MessageLite> StoragePath writeMessage(String key, T message) throws IOException {
        long startNanos = System.nanoTime();
        try {
            StoragePath path = delegate.writeMessage(key, message);

            // Update cumulative metrics
            messagesWritten.incrementAndGet();
            long bytes = message.getSerializedSize();
            bytesWritten.addAndGet(bytes);

            // Record performance metrics (count as 1 message batch)
            long latencyNanos = System.nanoTime() - startNanos;
            recordWrite(1, bytes, latencyNanos);

            return path;
        } catch (IOException e) {
            writeErrors.incrementAndGet();
            throw e;
        }
    }

    @Override
    public StreamingWriteResult writeChunkBatchStreaming(Iterator<TickDataChunk> chunks) throws IOException {
        long startNanos = System.nanoTime();
        try {
            StreamingWriteResult result = delegate.writeChunkBatchStreaming(chunks);

            // Update cumulative metrics
            batchesWritten.incrementAndGet();
            bytesWritten.addAndGet(result.bytesWritten());

            // Record performance metrics
            long latencyNanos = System.nanoTime() - startNanos;
            recordWrite(result.chunkCount(), result.bytesWritten(), latencyNanos);

            return result;
        } catch (IOException e) {
            writeErrors.incrementAndGet();
            throw e;
        }
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

    /**
     * {@inheritDoc}
     * <p>
     * Metric definitions:
     * <ul>
     *   <li>{@code batches_written}: cumulative batch write count</li>
     *   <li>{@code messages_written}: cumulative single-message write count</li>
     *   <li>{@code bytes_written}: cumulative compressed bytes written to storage</li>
     *   <li>{@code write_errors}: cumulative write error count</li>
     *   <li>{@code batches_per_sec}: sliding window batch write rate</li>
     *   <li>{@code bytes_per_sec}: sliding window compressed byte throughput</li>
     *   <li>{@code avg_write_latency_ms}: sliding window average write latency in milliseconds</li>
     * </ul>
     */
    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "batches_written", batchesWritten.get(),
            "messages_written", messagesWritten.get(),
            "bytes_written", bytesWritten.get(),
            "write_errors", writeErrors.get(),
            "batches_per_sec", batchesCounter.getRate(),
            "bytes_per_sec", bytesCounter.getRate(),
            "avg_write_latency_ms", latencyTracker.getAverage() / 1_000_000.0  // Convert nanos to ms
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
