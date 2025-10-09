/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.utils.monitoring;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe utility for tracking percentiles with O(1) recording performance.
 * <p>
 * Uses fixed logarithmically-spaced buckets for approximating percentiles without
 * storing individual values. Recording is O(1) through direct linear scan of a
 * small fixed-size array (11 buckets by default).
 * <p>
 * <strong>Performance Characteristics:</strong>
 * <ul>
 *   <li>Recording: O(1) - linear scan of 11 fixed buckets (~8-12ns)</li>
 *   <li>Percentile calculation: O(buckets) = O(11) constant time</li>
 *   <li>Average calculation: O(1) using pre-computed sum and count</li>
 *   <li>Thread-safe: AtomicLong for all counters</li>
 * </ul>
 * <p>
 * <strong>Why Linear Scan is O(1):</strong>
 * For a fixed-size array of 11 buckets, linear scan performs exactly 11 comparisons
 * regardless of the input value. This is constant time, not dependent on data size.
 * Linear scan is actually faster than binary search for small N due to better CPU
 * branch prediction and cache locality.
 * <p>
 * <strong>Accuracy Trade-off:</strong>
 * Percentiles are approximated based on bucket boundaries. Values are categorized
 * into buckets, providing reasonable accuracy for monitoring purposes while
 * maintaining O(1) performance.
 * <p>
 * <strong>Default Buckets (for nanosecond latencies):</strong>
 * <pre>
 * &lt;  1ms:  bucket[0]
 * &lt;  2ms:  bucket[1]
 * &lt;  5ms:  bucket[2]
 * &lt; 10ms:  bucket[3]
 * &lt; 25ms:  bucket[4]
 * &lt; 50ms:  bucket[5]
 * &lt;100ms:  bucket[6]
 * &lt;250ms:  bucket[7]
 * &lt;500ms:  bucket[8]
 * &lt;  1s:   bucket[9]
 * &lt;  5s:   bucket[10]
 * &gt;= 5s:   bucket[11]
 * </pre>
 * <p>
 * <strong>Custom Buckets:</strong>
 * Provide custom bucket boundaries for different measurement units
 * (e.g., message sizes, request counts).
 *
 * @see LatencyBucket (replaced by this class with O(1) performance)
 */
public class PercentileTracker {

    // Default buckets for nanosecond latencies (1ms to 5s)
    private static final long[] DEFAULT_LATENCY_BUCKETS_NANOS = {
            1_000_000L,       // 1ms
            2_000_000L,       // 2ms
            5_000_000L,       // 5ms
            10_000_000L,      // 10ms
            25_000_000L,      // 25ms
            50_000_000L,      // 50ms
            100_000_000L,     // 100ms
            250_000_000L,     // 250ms
            500_000_000L,     // 500ms
            1_000_000_000L,   // 1s
            5_000_000_000L    // 5s
    };

    private final long[] bucketBounds;
    private final AtomicLong[] counts;
    private final AtomicLong totalSum = new AtomicLong(0);
    private final AtomicLong totalCount = new AtomicLong(0);

    /**
     * Creates a new PercentileTracker with default latency buckets (nanoseconds).
     * <p>
     * Default buckets range from 1ms to 5s, suitable for typical operation latencies.
     */
    public PercentileTracker() {
        this(DEFAULT_LATENCY_BUCKETS_NANOS);
    }

    /**
     * Creates a new PercentileTracker with custom bucket boundaries.
     * <p>
     * Buckets must be sorted in ascending order. Values are categorized into buckets
     * using linear scan (O(1) for fixed-size arrays).
     * <p>
     * Example for message sizes:
     * <pre>
     * long[] sizeBuckets = {1024, 10_240, 102_400, 1_048_576};  // 1KB, 10KB, 100KB, 1MB
     * PercentileTracker sizeMetrics = new PercentileTracker(sizeBuckets);
     * </pre>
     *
     * @param bucketBounds Sorted array of bucket boundary values
     * @throws IllegalArgumentException if bucketBounds is null or empty
     */
    public PercentileTracker(long[] bucketBounds) {
        if (bucketBounds == null || bucketBounds.length == 0) {
            throw new IllegalArgumentException("Bucket bounds cannot be null or empty");
        }
        this.bucketBounds = bucketBounds.clone();  // Defensive copy
        this.counts = new AtomicLong[bucketBounds.length + 1];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = new AtomicLong(0);
        }
    }

    /**
     * Records a value in the appropriate bucket using O(1) linear scan.
     * <p>
     * For the default 11 buckets, this performs exactly 11 comparisons maximum,
     * making it constant time O(1). Linear scan is faster than binary search for
     * small N due to better cache locality and branch prediction.
     * <p>
     * Also updates running totals for average calculation.
     *
     * @param value The value to record (e.g., latency in nanoseconds, message size in bytes)
     */
    public void record(long value) {
        // Linear scan - O(buckets) where buckets is fixed constant (11)
        // Faster than binary search for N < 20 due to cache locality
        int bucketIndex = counts.length - 1;  // Default to last bucket (overflow)
        for (int i = 0; i < bucketBounds.length; i++) {
            if (value < bucketBounds[i]) {
                bucketIndex = i;
                break;
            }
        }
        
        counts[bucketIndex].incrementAndGet();
        totalCount.incrementAndGet();
        totalSum.addAndGet(value);
    }

    /**
     * Calculates the estimated value at the given percentile.
     * <p>
     * Returns the bucket boundary that contains the percentile. This is an approximation
     * since we don't store individual values, only bucket counts.
     * <p>
     * This is an O(buckets) operation where buckets is a fixed constant (11),
     * making it effectively O(1).
     *
     * @param percentile The percentile to calculate (0-100)
     * @return The estimated value at the percentile (e.g., nanoseconds, bytes)
     * @throws IllegalArgumentException if percentile is not in range [0, 100]
     */
    public long getPercentile(double percentile) {
        if (percentile < 0 || percentile > 100) {
            throw new IllegalArgumentException("Percentile must be between 0 and 100, got: " + percentile);
        }
        
        long total = totalCount.get();
        if (total == 0) {
            return 0;
        }
        
        long targetCount = (long) (total * (percentile / 100.0));
        long accumulatedCount = 0;
        
        for (int i = 0; i < counts.length; i++) {
            accumulatedCount += counts[i].get();
            if (accumulatedCount >= targetCount) {
                // Return the upper bound of this bucket
                return (i > 0) ? bucketBounds[i - 1] : 0;
            }
        }
        
        // Return the highest bucket boundary if we somehow didn't find it
        return bucketBounds[bucketBounds.length - 1];
    }

    /**
     * Calculates the average of all recorded values.
     * <p>
     * This is an O(1) operation using pre-computed sum and count.
     *
     * @return The average value, or 0 if no values recorded
     */
    public double getAverage() {
        long count = totalCount.get();
        if (count == 0) {
            return 0.0;
        }
        return (double) totalSum.get() / count;
    }

    /**
     * Returns the total number of recorded values.
     * <p>
     * This is an O(1) operation.
     *
     * @return The total count of all recorded values
     */
    public long getCount() {
        return totalCount.get();
    }

    /**
     * Returns the total sum of all recorded values.
     * <p>
     * This is an O(1) operation.
     *
     * @return The total sum
     */
    public long getTotalSum() {
        return totalSum.get();
    }

    /**
     * Resets all buckets and totals to zero.
     * <p>
     * Use this for testing or when you want to start fresh measurements.
     */
    public void reset() {
        for (AtomicLong count : counts) {
            count.set(0);
        }
        totalCount.set(0);
        totalSum.set(0);
    }

    /**
     * Returns the number of buckets in this tracker.
     * <p>
     * Primarily useful for testing and validation.
     *
     * @return The number of buckets (bucketBounds.length + 1)
     */
    public int getBucketCount() {
        return counts.length;
    }

    /**
     * Returns the count for a specific bucket index.
     * <p>
     * Package-private for use by SlidingWindowPercentiles when merging buckets
     * across multiple time periods.
     *
     * @param bucketIndex The bucket index (0 to getBucketCount()-1)
     * @return The count for this bucket
     */
    long getBucketValue(int bucketIndex) {
        if (bucketIndex < 0 || bucketIndex >= counts.length) {
            return 0;
        }
        return counts[bucketIndex].get();
    }

    /**
     * Returns the upper boundary value for a specific bucket index.
     * <p>
     * Package-private for use by SlidingWindowPercentiles when calculating percentiles
     * across merged buckets.
     *
     * @param bucketIndex The bucket index (0 to getBucketCount()-1)
     * @return The bucket boundary, or 0 for the first bucket, or the last boundary for overflow
     */
    long getBucketBoundary(int bucketIndex) {
        if (bucketIndex <= 0) {
            return 0;
        }
        if (bucketIndex > bucketBounds.length) {
            return bucketBounds[bucketBounds.length - 1];
        }
        return bucketBounds[bucketIndex - 1];
    }
}


