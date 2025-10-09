/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.common.utils;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A utility class for tracking and calculating latency percentiles.
 * This implementation is designed to be thread-safe and efficient for high-throughput
 * scenarios by using a bucket-based approach to approximate percentiles without
 * storing all individual latency values.
 * <p>
 * The buckets are logarithmically spaced to provide higher resolution for lower latencies,
 * which are typically more common and require more precise measurement.
 */
public class LatencyBucket {
    // Logarithmically spaced buckets for latency in nanoseconds.
    private static final long[] BUCKET_BOUNDS = {
            1_000_000, 2_000_000, 5_000_000, 10_000_000, 25_000_000, 50_000_000,
            100_000_000, 250_000_000, 500_000_000, 1_000_000_000, 5_000_000_000L
    };
    private final AtomicInteger[] counts = new AtomicInteger[BUCKET_BOUNDS.length + 1];
    private final AtomicInteger totalCount = new AtomicInteger(0);

    public LatencyBucket() {
        for (int i = 0; i < counts.length; i++) {
            counts[i] = new AtomicInteger(0);
        }
    }

    /**
     * Records a latency value in nanoseconds. This is an O(log N) operation due to the
     * binary search for the correct bucket.
     *
     * @param latencyNanos The latency value to record.
     */
    public void record(long latencyNanos) {
        int index = Arrays.binarySearch(BUCKET_BOUNDS, latencyNanos);
        if (index < 0) {
            index = -index - 1;
        }
        counts[index].incrementAndGet();
        totalCount.incrementAndGet();
    }

    /**
     * Calculates the latency at a given percentile.
     *
     * @param percentile The percentile to calculate (0-100).
     * @return The estimated latency in nanoseconds for the given percentile.
     */
    public long getPercentile(double percentile) {
        if (percentile < 0 || percentile > 100) {
            throw new IllegalArgumentException("Percentile must be between 0 and 100.");
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
                return i > 0 ? BUCKET_BOUNDS[i - 1] : 0;
            }
        }
        return BUCKET_BOUNDS[BUCKET_BOUNDS.length - 1];
    }
}