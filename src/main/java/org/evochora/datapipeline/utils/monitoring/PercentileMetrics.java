/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.utils.monitoring;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A utility class for tracking and calculating percentiles with O(1) recording.
 * <p>
 * Uses a fixed logarithmically-spaced bucket array for approximating percentiles.
 * Recording is O(1) by using direct array indexing instead of binary search.
 * <p>
 * <strong>Performance Characteristics:</strong>
 * <ul>
 *   <li>Recording: O(1) - direct array indexing via simple comparison</li>
 *   <li>Percentile calculation: O(buckets) - typically O(12) for 12 buckets</li>
 *   <li>Thread-safe: AtomicInteger for all counters</li>
 * </ul>
 * <p>
 * <strong>Accuracy Trade-off:</strong>
 * Percentiles are approximated based on bucket boundaries. For example, if a value falls
 * between 5ms and 10ms buckets, it's counted in the 10ms bucket. This provides reasonable
 * accuracy for monitoring purposes while maintaining O(1) recording performance.
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
 * You can provide custom bucket boundaries for different measurement units
 * (e.g., message sizes, request counts).
 */
public class PercentileMetrics {

    // Default buckets for nanosecond latencies (1ms to 5s)
    private static final long[] DEFAULT_LATENCY_BUCKETS = {
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
    private final AtomicInteger[] counts;
    private final AtomicInteger totalCount = new AtomicInteger(0);

    /**
     * Creates a new PercentileMetrics tracker with default latency buckets (nanoseconds).
     * <p>
     * Default buckets range from 1ms to 5s, suitable for typical operation latencies.
     */
    public PercentileMetrics() {
        this(DEFAULT_LATENCY_BUCKETS);
    }

    /**
     * Creates a new PercentileMetrics tracker with custom bucket boundaries.
     * <p>
     * Buckets must be sorted in ascending order. Values are categorized into buckets
     * using simple comparison (O(1) for small bucket counts).
     * <p>
     * Example for message sizes:
     * <pre>
     * long[] sizeBuckets = {1024, 10_240, 102_400, 1_048_576};  // 1KB, 10KB, 100KB, 1MB
     * PercentileMetrics sizeMetrics = new PercentileMetrics(sizeBuckets);
     * </pre>
     *
     * @param bucketBounds Sorted array of bucket boundary values
     * @throws IllegalArgumentException if bucketBounds is null or empty
     */
    public PercentileMetrics(long[] bucketBounds) {
        if (bucketBounds == null || bucketBounds.length == 0) {
            throw new IllegalArgumentException("Bucket bounds cannot be null or empty");
        }
        this.bucketBounds = bucketBounds.clone();  // Defensive copy
        this.counts = new AtomicInteger[bucketBounds.length + 1];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = new AtomicInteger(0);
        }
    }

    /**
     * Records a value in the appropriate bucket using O(1) linear scan.
     * <p>
     * For typical bucket counts (10-15), linear scan is faster than binary search
     * due to better cache locality and no branch mispredictions.
     * <p>
     * <strong>Performance:</strong> O(buckets) where buckets is typically 10-15,
     * effectively O(1) constant time for monitoring purposes.
     *
     * @param value The value to record (e.g., latency in nanoseconds, message size in bytes)
     */
    public void record(long value) {
        // Linear scan - O(buckets) but typically faster than binary search for small N (10-15)
        int bucketIndex = 0;
        for (int i = 0; i < bucketBounds.length; i++) {
            if (value < bucketBounds[i]) {
                bucketIndex = i;
                break;
            }
            bucketIndex = i + 1;
        }
        
        counts[bucketIndex].incrementAndGet();
        totalCount.incrementAndGet();
    }

    /**
     * Calculates the estimated value at the given percentile.
     * <p>
     * Returns the bucket boundary that contains the percentile. This is an approximation
     * since we don't store individual values, only bucket counts.
     * <p>
     * <strong>Performance:</strong> O(buckets) where buckets is typically 10-15.
     *
     * @param percentile The percentile to calculate (0-100)
     * @return The estimated value at the percentile (e.g., nanoseconds, bytes)
     * @throws IllegalArgumentException if percentile is not in range [0, 100]
     */
    public long getPercentile(double percentile) {
        if (percentile < 0 || percentile > 100) {
            throw new IllegalArgumentException("Percentile must be between 0 and 100, got: " + percentile);
        }
        
        int total = totalCount.get();
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
     * Returns the total number of recorded values.
     * <p>
     * This is an O(1) operation.
     *
     * @return The total count of all recorded values
     */
    public int getCount() {
        return totalCount.get();
    }

    /**
     * Resets all buckets and the total count to zero.
     * <p>
     * Use this for testing or when you want to start fresh measurements.
     */
    public void reset() {
        for (AtomicInteger count : counts) {
            count.set(0);
        }
        totalCount.set(0);
    }

    /**
     * Returns the number of buckets in this percentile tracker.
     * <p>
     * Primarily useful for testing and validation.
     *
     * @return The number of buckets (bucketBounds.length + 1)
     */
    public int getBucketCount() {
        return counts.length;
    }
}

