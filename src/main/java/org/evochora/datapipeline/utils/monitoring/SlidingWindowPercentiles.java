/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.utils.monitoring;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A thread-safe utility for tracking percentiles over a sliding time window.
 * <p>
 * Combines {@link PercentileTracker} with sliding window semantics to provide
 * percentile and average calculations over recent time periods only.
 * <p>
 * <strong>Performance Characteristics:</strong>
 * <ul>
 *   <li>Recording: O(1) - linear scan of 11 fixed buckets</li>
 *   <li>Percentile calculation: O(windowSeconds × buckets) = O(5 × 11) = O(55) ≈ O(1)</li>
 *   <li>Average calculation: O(windowSeconds) = O(5) ≈ O(1)</li>
 *   <li>Cleanup: O(1) amortized</li>
 * </ul>
 * <p>
 * <strong>Memory Management:</strong>
 * Automatically cleans up old per-second PercentileTracker instances to prevent
 * unbounded growth. Keeps windowSeconds + 5 buffer buckets.
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>
 * // Track latency percentiles over 5-second window
 * SlidingWindowPercentiles latency = new SlidingWindowPercentiles(5);
 * 
 * // Recording (O(1))
 * long startNanos = System.nanoTime();
 * doSomeOperation();
 * latency.record(System.nanoTime() - startNanos);
 * 
 * // Reading
 * double avgLatencyMs = latency.getAverage() / 1_000_000.0;  // Convert nanos to ms
 * long p95Nanos = latency.getPercentile(95);
 * long p99Nanos = latency.getPercentile(99);
 * </pre>
 */
public class SlidingWindowPercentiles {

    private final ConcurrentHashMap<Long, PercentileTracker> buckets = new ConcurrentHashMap<>();
    private final int windowSeconds;
    private final int maxBuckets;

    /**
     * Creates a new SlidingWindowPercentiles tracker with default latency buckets.
     *
     * @param windowSeconds The size of the sliding window in seconds (typically 5)
     * @throws IllegalArgumentException if windowSeconds <= 0
     */
    public SlidingWindowPercentiles(int windowSeconds) {
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("Window size must be positive, got: " + windowSeconds);
        }
        this.windowSeconds = windowSeconds;
        this.maxBuckets = windowSeconds + 5;  // Buffer to prevent constant cleanup
    }

    /**
     * Records a value in the current second's PercentileTracker bucket.
     * <p>
     * This is an O(1) operation - creates a new PercentileTracker if needed (rare),
     * then records the value using O(1) linear scan.
     *
     * @param value The value to record (e.g., latency in nanoseconds)
     */
    public void record(long value) {
        long currentSecond = Instant.now().getEpochSecond();
        buckets.computeIfAbsent(currentSecond, k -> new PercentileTracker()).record(value);
        cleanupIfNeeded(currentSecond);
    }

    /**
     * Calculates the percentile across all values in the sliding window.
     * <p>
     * This merges all per-second PercentileTrackers in the window and calculates
     * the overall percentile. Performance is O(windowSeconds × buckets) = O(5 × 11) = O(55),
     * which is constant time.
     *
     * @param percentile The percentile to calculate (0-100)
     * @return The estimated value at the percentile
     * @throws IllegalArgumentException if percentile is not in range [0, 100]
     */
    public long getPercentile(double percentile) {
        if (percentile < 0 || percentile > 100) {
            throw new IllegalArgumentException("Percentile must be between 0 and 100, got: " + percentile);
        }
        
        long currentSecond = Instant.now().getEpochSecond();
        
        // Merge counts from all trackers in the window
        long totalCount = 0;
        for (int i = 0; i < windowSeconds; i++) {
            PercentileTracker tracker = buckets.get(currentSecond - i);
            if (tracker != null) {
                totalCount += tracker.getCount();
            }
        }
        
        if (totalCount == 0) {
            return 0;
        }
        
        // Find the percentile by accumulating counts across all trackers
        long targetCount = (long) (totalCount * (percentile / 100.0));
        long accumulatedCount = 0;
        
        // We need to merge bucket counts from all window trackers
        // Get the first tracker to determine bucket structure
        PercentileTracker firstTracker = null;
        for (int i = 0; i < windowSeconds; i++) {
            PercentileTracker tracker = buckets.get(currentSecond - i);
            if (tracker != null) {
                firstTracker = tracker;
                break;
            }
        }
        
        if (firstTracker == null) {
            return 0;
        }
        
        // Merge counts for each bucket index across all trackers
        int bucketCount = firstTracker.getBucketCount();
        for (int bucketIdx = 0; bucketIdx < bucketCount; bucketIdx++) {
            long bucketTotal = 0;
            for (int i = 0; i < windowSeconds; i++) {
                PercentileTracker tracker = buckets.get(currentSecond - i);
                if (tracker != null) {
                    bucketTotal += tracker.getBucketValue(bucketIdx);
                }
            }
            
            accumulatedCount += bucketTotal;
            if (accumulatedCount >= targetCount) {
                // Return this bucket's boundary
                return firstTracker.getBucketBoundary(bucketIdx);
            }
        }
        
        // Fallback to highest boundary
        return firstTracker.getBucketBoundary(bucketCount - 1);
    }

    /**
     * Calculates the average of all values in the sliding window.
     * <p>
     * This is an O(windowSeconds) operation, typically O(5).
     *
     * @return The average value, or 0 if no values recorded
     */
    public double getAverage() {
        long currentSecond = Instant.now().getEpochSecond();
        long totalSum = 0;
        long totalCount = 0;
        
        for (int i = 0; i < windowSeconds; i++) {
            PercentileTracker tracker = buckets.get(currentSecond - i);
            if (tracker != null) {
                totalSum += tracker.getTotalSum();
                totalCount += tracker.getCount();
            }
        }
        
        return totalCount == 0 ? 0.0 : (double) totalSum / totalCount;
    }

    /**
     * Returns the total count of values recorded in the sliding window.
     * <p>
     * This is an O(windowSeconds) operation, typically O(5).
     *
     * @return The total count
     */
    public long getCount() {
        long currentSecond = Instant.now().getEpochSecond();
        long total = 0;
        
        for (int i = 0; i < windowSeconds; i++) {
            PercentileTracker tracker = buckets.get(currentSecond - i);
            if (tracker != null) {
                total += tracker.getCount();
            }
        }
        
        return total;
    }

    /**
     * Returns the current number of per-second buckets being tracked.
     * <p>
     * Primarily useful for testing and debugging.
     *
     * @return The number of active per-second buckets
     */
    public int getSecondBucketCount() {
        return buckets.size();
    }

    /**
     * Removes old per-second buckets outside the sliding window.
     * <p>
     * This is an O(1) amortized operation - cleanup only happens when bucket count
     * exceeds threshold.
     *
     * @param currentSecond The current time in epoch seconds
     */
    private void cleanupIfNeeded(long currentSecond) {
        if (buckets.size() > maxBuckets) {
            long cutoffSecond = currentSecond - windowSeconds - 1;
            buckets.keySet().removeIf(second -> second < cutoffSecond);
        }
    }
}

