package org.evochora.datapipeline.resources.storage.wrappers;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitored wrapper for batch storage read operations.
 * <p>
 * Tracks per-service read metrics: batches queried, batches read, bytes read, query errors.
 * Used by services that read batches (e.g., future indexer services).
 */
public class MonitoredBatchStorageReader implements IBatchStorageRead, IWrappedResource, IMonitorable {

    private final IBatchStorageRead delegate;
    private final ResourceContext context;

    // Read metrics (cumulative)
    private final AtomicLong batchesRead = new AtomicLong(0);
    private final AtomicLong bytesRead = new AtomicLong(0);
    private final AtomicLong readErrors = new AtomicLong(0);
    private final AtomicLong messagesRead = new AtomicLong(0);

    // Performance metrics (sliding window with per-second buckets)
    private final int windowSeconds;
    private final ConcurrentHashMap<Long, AtomicLong> readsPerSecond = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> bytesPerSecond = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, LatencyBucket> readLatencyBuckets = new ConcurrentHashMap<>();

    public MonitoredBatchStorageReader(IBatchStorageRead delegate, ResourceContext context) {
        this.delegate = delegate;
        this.context = context;
        this.windowSeconds = Integer.parseInt(context.parameters().getOrDefault("throughputWindowSeconds", "5"));
    }

    /**
     * Records a read operation for performance tracking.
     */
    private void recordRead(long bytes, long latencyNanos) {
        long currentSecond = Instant.now().getEpochSecond();
        readsPerSecond.computeIfAbsent(currentSecond, k -> new AtomicLong(0)).incrementAndGet();
        bytesPerSecond.computeIfAbsent(currentSecond, k -> new AtomicLong(0)).addAndGet(bytes);
        readLatencyBuckets.computeIfAbsent(currentSecond, k -> new LatencyBucket()).record(latencyNanos);
        cleanupOldBuckets(currentSecond);
    }

    /**
     * Removes counter buckets older than the monitoring window to prevent unbounded memory growth.
     */
    private void cleanupOldBuckets(long currentSecond) {
        int maxBuckets = windowSeconds + 5;
        if (readsPerSecond.size() > maxBuckets) {
            long cutoffSecond = currentSecond - windowSeconds - 1;
            readsPerSecond.keySet().removeIf(second -> second < cutoffSecond);
            bytesPerSecond.keySet().removeIf(second -> second < cutoffSecond);
            readLatencyBuckets.keySet().removeIf(second -> second < cutoffSecond);
        }
    }

    private double calculateReadThroughput() {
        long currentSecond = Instant.now().getEpochSecond();
        long total = 0;
        for (int i = 0; i < windowSeconds; i++) {
            AtomicLong counter = readsPerSecond.get(currentSecond - i);
            if (counter != null) total += counter.get();
        }
        return (double) total / windowSeconds;
    }

    private double calculateByteThroughput() {
        long currentSecond = Instant.now().getEpochSecond();
        long total = 0;
        for (int i = 0; i < windowSeconds; i++) {
            AtomicLong counter = bytesPerSecond.get(currentSecond - i);
            if (counter != null) total += counter.get();
        }
        return (double) total / windowSeconds;
    }

    private double calculateAvgReadLatencyMs() {
        long currentSecond = Instant.now().getEpochSecond();
        long totalNanos = 0, count = 0;
        for (int i = 0; i < windowSeconds; i++) {
            LatencyBucket bucket = readLatencyBuckets.get(currentSecond - i);
            if (bucket != null) {
                totalNanos += bucket.getTotalNanos();
                count += bucket.getCount();
            }
        }
        return count == 0 ? 0.0 : (double) totalNanos / count / 1_000_000.0;
    }

    @Override
    public BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults) throws IOException {
        // Simple delegation - no metrics for now (can be added later if needed)
        return delegate.listBatchFiles(prefix, continuationToken, maxResults);
    }

    @Override
    public List<TickData> readBatch(String filename) throws IOException {
        long startNanos = System.nanoTime();
        try {
            List<TickData> result = delegate.readBatch(filename);

            // Update cumulative metrics
            batchesRead.incrementAndGet();
            long bytes = result.stream().mapToLong(TickData::getSerializedSize).sum();
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
    public <T extends MessageLite> T readMessage(String key, Parser<T> parser) throws IOException {
        long startNanos = System.nanoTime();
        try {
            T message = delegate.readMessage(key, parser);

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
            "reads_per_sec", calculateReadThroughput(),
            "bytes_per_sec", calculateByteThroughput(),
            "avg_read_latency_ms", calculateAvgReadLatencyMs()
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

    /**
     * Simple latency bucket that tracks count and total latency for a specific second.
     * Thread-safe for concurrent updates within the same bucket.
     */
    private static class LatencyBucket {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong totalNanos = new AtomicLong(0);

        void record(long latencyNanos) {
            count.incrementAndGet();
            totalNanos.addAndGet(latencyNanos);
        }

        long getCount() {
            return count.get();
        }

        long getTotalNanos() {
            return totalNanos.get();
        }
    }
}
