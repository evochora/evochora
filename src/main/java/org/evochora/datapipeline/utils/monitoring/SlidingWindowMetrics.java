/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.utils.monitoring;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A generic utility class for tracking metrics over a sliding time window.
 * <p>
 * This class provides O(1) recording operations and efficient sliding window calculations
 * for various metric types:
 * <ul>
 *   <li><b>Count Metrics:</b> Track event counts (e.g., messages processed, operations completed)</li>
 *   <li><b>Sum Metrics:</b> Track cumulative values (e.g., bytes written, total duration)</li>
 *   <li><b>Rate Metrics:</b> Calculate per-second rates from counts</li>
 * </ul>
 * <p>
 * <strong>Performance Characteristics:</strong>
 * <ul>
 *   <li>Recording: O(1) - just increments AtomicLong in HashMap</li>
 *   <li>Reading: O(windowSeconds) - typically O(5) for 5-second window</li>
 *   <li>Cleanup: O(1) amortized - only when bucket count exceeds threshold</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong> All operations are thread-safe using ConcurrentHashMap and AtomicLong.
 * <p>
 * <strong>Memory Management:</strong> Old buckets are automatically cleaned up to prevent unbounded growth.
 * Keeps windowSeconds + 5 buffer buckets (typically 10 buckets for 5-second window).
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>
 * // Track operations per second
 * SlidingWindowMetrics opsMetric = new SlidingWindowMetrics(5);  // 5-second window
 * opsMetric.recordCount();  // O(1) - increment counter for current second
 * double opsPerSec = opsMetric.getRate();  // Calculate average rate
 * 
 * // Track bytes per second
 * SlidingWindowMetrics bytesMetric = new SlidingWindowMetrics(5);
 * bytesMetric.recordSum(1024);  // O(1) - add 1024 bytes to current second
 * double bytesPerSec = bytesMetric.getRate();  // Calculate average byte rate
 * </pre>
 *
 * @see RateBucket
 */
public class SlidingWindowMetrics {

    private final ConcurrentHashMap<Long, AtomicLong> buckets = new ConcurrentHashMap<>();
    private final int windowSeconds;
    private final int maxBuckets;

    /**
     * Creates a new SlidingWindowMetrics with the specified window size.
     *
     * @param windowSeconds The size of the sliding window in seconds (typically 5)
     * @throws IllegalArgumentException if windowSeconds <= 0
     */
    public SlidingWindowMetrics(int windowSeconds) {
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("Window size must be positive, got: " + windowSeconds);
        }
        this.windowSeconds = windowSeconds;
        this.maxBuckets = windowSeconds + 5;  // Buffer to prevent constant cleanup
    }

    /**
     * Records a single event (count = 1) in the current second bucket.
     * <p>
     * This is an O(1) operation using ConcurrentHashMap.computeIfAbsent() and AtomicLong.incrementAndGet().
     * <p>
     * Use this for counting discrete events like messages processed, operations completed, etc.
     */
    public void recordCount() {
        recordSum(1);
    }

    /**
     * Records a value (count or sum) in the current second bucket.
     * <p>
     * This is an O(1) operation using ConcurrentHashMap.computeIfAbsent() and AtomicLong.addAndGet().
     * <p>
     * Use this for:
     * <ul>
     *   <li>Multiple events: recordSum(batchSize)</li>
     *   <li>Cumulative values: recordSum(bytesWritten)</li>
     *   <li>Durations: recordSum(durationMs)</li>
     * </ul>
     *
     * @param value The value to add to the current second's bucket
     */
    public void recordSum(long value) {
        long currentSecond = Instant.now().getEpochSecond();
        buckets.computeIfAbsent(currentSecond, k -> new AtomicLong(0)).addAndGet(value);
        cleanupIfNeeded(currentSecond);
    }

    /**
     * Calculates the average rate (events or sum per second) over the sliding window.
     * <p>
     * This is an O(windowSeconds) operation, typically O(5) for a 5-second window.
     * <p>
     * Examples:
     * <ul>
     *   <li>If tracking counts: returns events per second</li>
     *   <li>If tracking bytes: returns bytes per second</li>
     *   <li>If tracking durations: returns average duration per second</li>
     * </ul>
     *
     * @return The average rate over the sliding window
     */
    public double getRate() {
        return getRate(Instant.now().getEpochSecond());
    }

    /**
     * Calculates the average rate at a specific point in time.
     * <p>
     * This is an O(windowSeconds) operation, typically O(5).
     *
     * @param nowSeconds The current time in epoch seconds
     * @return The average rate over the sliding window
     */
    public double getRate(long nowSeconds) {
        long total = 0;
        for (int i = 0; i < windowSeconds; i++) {
            long second = nowSeconds - i;
            AtomicLong bucket = buckets.get(second);
            if (bucket != null) {
                total += bucket.get();
            }
        }
        return (double) total / windowSeconds;
    }

    /**
     * Returns the total sum of all values in the current sliding window.
     * <p>
     * This is an O(windowSeconds) operation, typically O(5).
     * <p>
     * Useful for calculating totals like "bytes written in last 5 seconds".
     *
     * @return The sum of all values in the window
     */
    public long getWindowSum() {
        return getWindowSum(Instant.now().getEpochSecond());
    }

    /**
     * Returns the total sum at a specific point in time.
     *
     * @param nowSeconds The current time in epoch seconds
     * @return The sum of all values in the window
     */
    public long getWindowSum(long nowSeconds) {
        long total = 0;
        for (int i = 0; i < windowSeconds; i++) {
            long second = nowSeconds - i;
            AtomicLong bucket = buckets.get(second);
            if (bucket != null) {
                total += bucket.get();
            }
        }
        return total;
    }

    /**
     * Returns the current number of buckets being tracked.
     * <p>
     * Primarily useful for testing and debugging. In normal operation, this should
     * oscillate between windowSeconds and maxBuckets (windowSeconds + 5).
     *
     * @return The number of active buckets
     */
    public int getBucketCount() {
        return buckets.size();
    }

    /**
     * Removes old buckets outside the sliding window to prevent unbounded memory growth.
     * <p>
     * This is an O(1) amortized operation - cleanup only happens when bucket count
     * exceeds maxBuckets threshold, and removes all old buckets at once.
     * <p>
     * Cleanup strategy: Keep windowSeconds + 5 buffer buckets to avoid constant cleanup churn.
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

