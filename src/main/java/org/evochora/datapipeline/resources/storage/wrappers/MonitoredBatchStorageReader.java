package org.evochora.datapipeline.resources.storage.wrappers;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.BatchMetadata;
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
    private final AtomicLong queriesPerformed = new AtomicLong(0);
    private final AtomicLong batchesQueried = new AtomicLong(0);
    private final AtomicLong batchesRead = new AtomicLong(0);
    private final AtomicLong bytesRead = new AtomicLong(0);
    private final AtomicLong queryErrors = new AtomicLong(0);
    private final AtomicLong readErrors = new AtomicLong(0);
    private final AtomicLong messagesRead = new AtomicLong(0);

    // Performance metrics (sliding window with per-second buckets)
    private final int windowSeconds;
    private final ConcurrentHashMap<Long, AtomicLong> queriesPerSecond = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> readsPerSecond = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> bytesPerSecond = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, LatencyBucket> queryLatencyBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, LatencyBucket> readLatencyBuckets = new ConcurrentHashMap<>();

    public MonitoredBatchStorageReader(IBatchStorageRead delegate, ResourceContext context) {
        this.delegate = delegate;
        this.context = context;
        this.windowSeconds = Integer.parseInt(context.parameters().getOrDefault("throughputWindowSeconds", "5"));
    }

    /**
     * Records a query operation for performance tracking.
     */
    private void recordQuery(long latencyNanos) {
        long currentSecond = Instant.now().getEpochSecond();
        queriesPerSecond.computeIfAbsent(currentSecond, k -> new AtomicLong(0)).incrementAndGet();
        queryLatencyBuckets.computeIfAbsent(currentSecond, k -> new LatencyBucket()).record(latencyNanos);
        cleanupOldBuckets(currentSecond);
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
        if (queriesPerSecond.size() > maxBuckets) {
            long cutoffSecond = currentSecond - windowSeconds - 1;
            queriesPerSecond.keySet().removeIf(second -> second < cutoffSecond);
            readsPerSecond.keySet().removeIf(second -> second < cutoffSecond);
            bytesPerSecond.keySet().removeIf(second -> second < cutoffSecond);
            queryLatencyBuckets.keySet().removeIf(second -> second < cutoffSecond);
            readLatencyBuckets.keySet().removeIf(second -> second < cutoffSecond);
        }
    }

    private double calculateQueryThroughput() {
        long currentSecond = Instant.now().getEpochSecond();
        long total = 0;
        for (int i = 0; i < windowSeconds; i++) {
            AtomicLong counter = queriesPerSecond.get(currentSecond - i);
            if (counter != null) total += counter.get();
        }
        return (double) total / windowSeconds;
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

    private double calculateAvgQueryLatencyMs() {
        long currentSecond = Instant.now().getEpochSecond();
        long totalNanos = 0, count = 0;
        for (int i = 0; i < windowSeconds; i++) {
            LatencyBucket bucket = queryLatencyBuckets.get(currentSecond - i);
            if (bucket != null) {
                totalNanos += bucket.getTotalNanos();
                count += bucket.getCount();
            }
        }
        return count == 0 ? 0.0 : (double) totalNanos / count / 1_000_000.0;
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
    public List<BatchMetadata> queryBatches(long startTick, long endTick) throws IOException {
        long startNanos = System.nanoTime();
        try {
            List<BatchMetadata> result = delegate.queryBatches(startTick, endTick);

            // Update cumulative metrics
            queriesPerformed.incrementAndGet();
            batchesQueried.addAndGet(result.size());

            // Record performance metrics
            long latencyNanos = System.nanoTime() - startNanos;
            recordQuery(latencyNanos);

            return result;
        } catch (IOException e) {
            queryErrors.incrementAndGet();
            throw e;
        }
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
        return Map.ofEntries(
            Map.entry("queries_performed", queriesPerformed.get()),
            Map.entry("batches_queried", batchesQueried.get()),
            Map.entry("batches_read", batchesRead.get()),
            Map.entry("messages_read", messagesRead.get()),
            Map.entry("bytes_read", bytesRead.get()),
            Map.entry("query_errors", queryErrors.get()),
            Map.entry("read_errors", readErrors.get()),
            Map.entry("queries_per_sec", calculateQueryThroughput()),
            Map.entry("reads_per_sec", calculateReadThroughput()),
            Map.entry("bytes_per_sec", calculateByteThroughput()),
            Map.entry("avg_query_latency_ms", calculateAvgQueryLatencyMs()),
            Map.entry("avg_read_latency_ms", calculateAvgReadLatencyMs())
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
