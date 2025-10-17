/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.utils.monitoring;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe utility for tracking counts, sums, max, min, and averages over a sliding time window.
 * <p>
 * This class provides O(1) recording operations and efficient rate calculations
 * for monitoring throughput, bytes transferred, or any cumulative metric. It also tracks
 * max/min/average values when using {@link #recordValue(double)}.
 * <p>
 * <strong>Performance Characteristics:</strong>
 * <ul>
 *   <li>Recording: O(1) - atomic updates per bucket</li>
 *   <li>Rate/sum calculation: O(windowSeconds) - typically O(5) for 5-second window</li>
 *   <li>Max/Min/Avg: O(windowSeconds) - scans all buckets in window</li>
 *   <li>Cleanup: O(1) amortized - only when bucket count exceeds threshold</li>
 * </ul>
 * <p>
 * <strong>Memory Management:</strong>
 * Automatically cleans up old buckets to prevent unbounded growth.
 * Keeps windowSeconds + 5 buffer buckets (typically 10 buckets for 5-second window).
 * <p>
 * <strong>Usage Examples:</strong>
 * <pre>
 * // Track operations per second (sum-based)
 * SlidingWindowCounter opsCounter = new SlidingWindowCounter(5);  // 5-second window
 * opsCounter.recordCount();  // O(1) - increment for current second
 * double opsPerSec = opsCounter.getRate();  // Average over last 5 seconds
 * 
 * // Track bytes per second (sum-based)
 * SlidingWindowCounter bytesCounter = new SlidingWindowCounter(5);
 * bytesCounter.recordSum(1024);  // O(1) - add 1024 bytes
 * double bytesPerSec = bytesCounter.getRate();
 * 
 * // Track heap usage with max/min/avg (value-based)
 * SlidingWindowCounter heapCounter = new SlidingWindowCounter(60);
 * heapCounter.recordValue(2048.5);  // O(1) - record current heap MB
 * double maxHeap = heapCounter.getWindowMax();  // Max over last 60 seconds
 * double avgHeap = heapCounter.getWindowAverage();  // Avg over last 60 seconds
 * </pre>
 *
 * @see RateBucket (replaced by this class)
 */
public class SlidingWindowCounter {

    /**
     * Statistics bucket for a single second, supporting both sum-based and value-based tracking.
     */
    private static class BucketStats {
        private final AtomicLong sum = new AtomicLong(0);  // For sum-based tracking (recordSum, recordCount)
        private volatile double max = Double.MIN_VALUE;    // For value-based tracking (recordValue)
        private volatile double min = Double.MAX_VALUE;
        private volatile double valueSum = 0.0;
        private volatile int valueCount = 0;

        synchronized void recordValue(double value) {
            if (value > max) max = value;
            if (value < min) min = value;
            valueSum += value;
            valueCount++;
        }

        void addSum(long value) {
            sum.addAndGet(value);
        }

        long getSum() {
            return sum.get();
        }

        synchronized double getMax() {
            return max == Double.MIN_VALUE ? 0.0 : max;
        }

        synchronized double getMin() {
            return min == Double.MAX_VALUE ? 0.0 : min;
        }

        synchronized double getValueSum() {
            return valueSum;
        }

        synchronized int getValueCount() {
            return valueCount;
        }
    }

    private final ConcurrentHashMap<Long, BucketStats> buckets = new ConcurrentHashMap<>();
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
        buckets.computeIfAbsent(currentSecond, k -> new BucketStats()).addSum(value);
        cleanupIfNeeded(currentSecond);
    }

    /**
     * Records an individual value for max/min/average tracking.
     * <p>
     * This is an O(1) operation that updates the current second's max, min, sum, and count.
     * Use this method when you want to track individual measurements (e.g., heap usage, latency)
     * rather than cumulative sums.
     * <p>
     * <strong>Note:</strong> This method is separate from {@link #recordSum(long)}.
     * Use either recordValue() for max/min/avg tracking OR recordSum() for rate tracking,
     * not both on the same instance.
     *
     * @param value The value to record (e.g., current heap MB, latency ms)
     */
    public void recordValue(double value) {
        long currentSecond = Instant.now().getEpochSecond();
        buckets.computeIfAbsent(currentSecond, k -> new BucketStats()).recordValue(value);
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
            BucketStats bucket = buckets.get(second);
            if (bucket != null) {
                total += bucket.getSum();
            }
        }
        return (double) total / windowSeconds;
    }

    /**
     * Returns the maximum value recorded in the sliding window.
     * <p>
     * This is an O(windowSeconds) operation that scans all buckets to find the maximum.
     * Only works with values recorded via {@link #recordValue(double)}.
     *
     * @return The maximum value in the window, or 0.0 if no values recorded.
     */
    public double getWindowMax() {
        return getWindowMax(Instant.now().getEpochSecond());
    }

    /**
     * Returns the maximum value at a specific point in time.
     *
     * @param nowSeconds The current time in epoch seconds
     * @return The maximum value in the window
     */
    public double getWindowMax(long nowSeconds) {
        double max = Double.MIN_VALUE;
        for (int i = 0; i < windowSeconds; i++) {
            long second = nowSeconds - i;
            BucketStats bucket = buckets.get(second);
            if (bucket != null) {
                double bucketMax = bucket.getMax();
                if (bucketMax > max) {
                    max = bucketMax;
                }
            }
        }
        return max == Double.MIN_VALUE ? 0.0 : max;
    }

    /**
     * Returns the minimum value recorded in the sliding window.
     * <p>
     * This is an O(windowSeconds) operation that scans all buckets to find the minimum.
     * Only works with values recorded via {@link #recordValue(double)}.
     *
     * @return The minimum value in the window, or 0.0 if no values recorded.
     */
    public double getWindowMin() {
        return getWindowMin(Instant.now().getEpochSecond());
    }

    /**
     * Returns the minimum value at a specific point in time.
     *
     * @param nowSeconds The current time in epoch seconds
     * @return The minimum value in the window
     */
    public double getWindowMin(long nowSeconds) {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < windowSeconds; i++) {
            long second = nowSeconds - i;
            BucketStats bucket = buckets.get(second);
            if (bucket != null) {
                double bucketMin = bucket.getMin();
                if (bucketMin < min) {
                    min = bucketMin;
                }
            }
        }
        return min == Double.MAX_VALUE ? 0.0 : min;
    }

    /**
     * Returns the average of all values recorded in the sliding window.
     * <p>
     * This is an O(windowSeconds) operation that calculates the true average
     * across all individual values recorded via {@link #recordValue(double)}.
     *
     * @return The average value in the window, or 0.0 if no values recorded.
     */
    public double getWindowAverage() {
        return getWindowAverage(Instant.now().getEpochSecond());
    }

    /**
     * Returns the average value at a specific point in time.
     *
     * @param nowSeconds The current time in epoch seconds
     * @return The average value in the window
     */
    public double getWindowAverage(long nowSeconds) {
        double totalSum = 0.0;
        int totalCount = 0;
        for (int i = 0; i < windowSeconds; i++) {
            long second = nowSeconds - i;
            BucketStats bucket = buckets.get(second);
            if (bucket != null) {
                totalSum += bucket.getValueSum();
                totalCount += bucket.getValueCount();
            }
        }
        return totalCount > 0 ? totalSum / totalCount : 0.0;
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
            BucketStats bucket = buckets.get(second);
            if (bucket != null) {
                total += bucket.getSum();
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

