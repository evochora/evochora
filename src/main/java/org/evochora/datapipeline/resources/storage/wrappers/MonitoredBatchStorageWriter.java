package org.evochora.datapipeline.resources.storage.wrappers;

import com.google.protobuf.MessageLite;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.BatchMetadata;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    // Performance metrics (sliding window with per-second buckets)
    private final int windowSeconds;
    private final ConcurrentHashMap<Long, AtomicLong> batchesPerSecond = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> bytesPerSecond = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, LatencyBucket> latencyBuckets = new ConcurrentHashMap<>();

    public MonitoredBatchStorageWriter(IBatchStorageWrite delegate, ResourceContext context) {
        this.delegate = delegate;
        this.context = context;
        this.windowSeconds = Integer.parseInt(context.parameters().getOrDefault("throughputWindowSeconds", "5"));
    }

    /**
     * Records a write operation for performance tracking using sliding window counters.
     * This is an O(1) operation that just increments counters for the current second.
     */
    private void recordWrite(int batchSize, long bytes, long latencyNanos) {
        long currentSecond = Instant.now().getEpochSecond();
        batchesPerSecond.computeIfAbsent(currentSecond, k -> new AtomicLong(0)).incrementAndGet();
        bytesPerSecond.computeIfAbsent(currentSecond, k -> new AtomicLong(0)).addAndGet(bytes);
        latencyBuckets.computeIfAbsent(currentSecond, k -> new LatencyBucket()).record(latencyNanos);
        cleanupOldBuckets(currentSecond);
    }

    /**
     * Removes counter buckets older than the monitoring window to prevent unbounded memory growth.
     * Only removes counters if we have more buckets than needed (window + buffer).
     */
    private void cleanupOldBuckets(long currentSecond) {
        // Keep windowSeconds + 5 extra seconds as buffer
        int maxBuckets = windowSeconds + 5;
        if (batchesPerSecond.size() > maxBuckets) {
            long cutoffSecond = currentSecond - windowSeconds - 1;
            batchesPerSecond.keySet().removeIf(second -> second < cutoffSecond);
            bytesPerSecond.keySet().removeIf(second -> second < cutoffSecond);
            latencyBuckets.keySet().removeIf(second -> second < cutoffSecond);
        }
    }

    /**
     * Calculates the throughput (batches per second) based on per-second counters within the configured window.
     * This is an O(windowSeconds) operation, typically O(5).
     */
    private double calculateBatchThroughput() {
        long currentSecond = Instant.now().getEpochSecond();
        long totalBatches = 0;

        for (int i = 0; i < windowSeconds; i++) {
            long second = currentSecond - i;
            AtomicLong counter = batchesPerSecond.get(second);
            if (counter != null) {
                totalBatches += counter.get();
            }
        }

        return (double) totalBatches / windowSeconds;
    }

    /**
     * Calculates the byte throughput (bytes per second) based on per-second counters within the configured window.
     * This is an O(windowSeconds) operation, typically O(5).
     */
    private double calculateByteThroughput() {
        long currentSecond = Instant.now().getEpochSecond();
        long totalBytes = 0;

        for (int i = 0; i < windowSeconds; i++) {
            long second = currentSecond - i;
            AtomicLong counter = bytesPerSecond.get(second);
            if (counter != null) {
                totalBytes += counter.get();
            }
        }

        return (double) totalBytes / windowSeconds;
    }

    /**
     * Calculates average write latency in milliseconds based on latency buckets within the configured window.
     * This is an O(windowSeconds) operation, typically O(5).
     */
    private double calculateAvgLatencyMs() {
        long currentSecond = Instant.now().getEpochSecond();
        long totalLatencyNanos = 0;
        long totalCount = 0;

        for (int i = 0; i < windowSeconds; i++) {
            long second = currentSecond - i;
            LatencyBucket bucket = latencyBuckets.get(second);
            if (bucket != null) {
                totalLatencyNanos += bucket.getTotalNanos();
                totalCount += bucket.getCount();
            }
        }

        if (totalCount == 0) {
            return 0.0;
        }

        return (double) totalLatencyNanos / totalCount / 1_000_000.0;
    }

    @Override
    public String writeBatch(List<TickData> batch, long firstTick, long lastTick) throws IOException {
        long startNanos = System.nanoTime();
        try {
            String filename = delegate.writeBatch(batch, firstTick, lastTick);

            // Update cumulative metrics
            batchesWritten.incrementAndGet();
            long bytes = batch.stream().mapToLong(TickData::getSerializedSize).sum();
            bytesWritten.addAndGet(bytes);

            // Record performance metrics
            long latencyNanos = System.nanoTime() - startNanos;
            recordWrite(batch.size(), bytes, latencyNanos);

            return filename;
        } catch (IOException e) {
            writeErrors.incrementAndGet();
            throw e;
        }
    }

    @Override
    public <T extends MessageLite> void writeMessage(String key, T message) throws IOException {
        long startNanos = System.nanoTime();
        try {
            delegate.writeMessage(key, message);

            // Update cumulative metrics
            messagesWritten.incrementAndGet();
            long bytes = message.getSerializedSize();
            bytesWritten.addAndGet(bytes);

            // Record performance metrics (count as 1 message batch)
            long latencyNanos = System.nanoTime() - startNanos;
            recordWrite(1, bytes, latencyNanos);
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

    @Override
    public Map<String, Number> getMetrics() {
        return Map.of(
            "batches_written", batchesWritten.get(),
            "messages_written", messagesWritten.get(),
            "bytes_written", bytesWritten.get(),
            "write_errors", writeErrors.get(),
            "batches_per_sec", calculateBatchThroughput(),
            "bytes_per_sec", calculateByteThroughput(),
            "avg_write_latency_ms", calculateAvgLatencyMs()
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
