/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.utils.monitoring;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe utility for tracking counts and sums over a sliding time window.
 * <p>
 * This class provides O(1) recording operations and efficient rate calculations
 * for monitoring throughput, bytes transferred, or any cumulative metric.
 * <p>
 * <strong>Performance Characteristics:</strong>
 * <ul>
 *   <li>Recording: O(1) - just increments AtomicLong in HashMap</li>
 *   <li>Rate calculation: O(windowSeconds) - typically O(5) for 5-second window</li>
 *   <li>Cleanup: O(1) amortized - only when bucket count exceeds threshold</li>
 * </ul>
 * <p>
 * <strong>Memory Management:</strong>
 * Automatically cleans up old buckets to prevent unbounded growth.
 * Keeps windowSeconds + 5 buffer buckets (typically 10 buckets for 5-second window).
 * <p>
 * <strong>Usage Examples:</strong>
 * <pre>
 * // Track operations per second
 * SlidingWindowCounter opsCounter = new SlidingWindowCounter(5);  // 5-second window
 * opsCounter.recordCount();  // O(1) - increment for current second
 * double opsPerSec = opsCounter.getRate();  // Average over last 5 seconds
 * 
 * // Track bytes per second
 * SlidingWindowCounter bytesCounter = new SlidingWindowCounter(5);
 * bytesCounter.recordSum(1024);  // O(1) - add 1024 bytes
 * double bytesPerSec = bytesCounter.getRate();
 * </pre>
 *
 * @see RateBucket (replaced by this class)
 */
public class SlidingWindowCounter {

    private final ConcurrentHashMap<Long, AtomicLong> buckets = new ConcurrentHashMap<>();
    private final int windowSeconds;
    private final int maxBuckets;

    /**
     * Creates a new SlidingWindowCounter with the specified window size.
     *
     * @param windowSeconds The size of the sliding window in seconds (typically 5)
     * @throws IllegalArgumentException if windowSeconds <= 0
     */
    public SlidingWindowCounter(int windowSeconds) {
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

