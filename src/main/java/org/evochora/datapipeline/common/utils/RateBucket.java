/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.common.utils;

import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A utility class for tracking event rates over a sliding time window.
 * <p>
 * This class is thread-safe and designed for high-throughput scenarios. It uses a
 * {@link ConcurrentSkipListMap} to store event counts in time-based buckets (milliseconds),
 * allowing for efficient O(1) recording and O(log N) calculation of the rate over the
 * specified window, where N is the number of buckets.
 */
public class RateBucket {
    private final ConcurrentNavigableMap<Long, AtomicLong> buckets = new ConcurrentSkipListMap<>();
    private final long windowSizeMs;

    /**
     * Creates a new RateBucket with a specified window size.
     *
     * @param windowSizeMs The size of the sliding window in milliseconds.
     */
    public RateBucket(long windowSizeMs) {
        if (windowSizeMs <= 0) {
            throw new IllegalArgumentException("Window size must be positive.");
        }
        this.windowSizeMs = windowSizeMs;
    }

    /**
     * Records an event at the current time. This is an O(1) operation.
     */
    public void record() {
        record(System.currentTimeMillis());
    }

    /**
     * Records an event at a specific timestamp.
     *
     * @param timestampMs The timestamp of the event in milliseconds.
     */
    public void record(long timestampMs) {
        buckets.computeIfAbsent(timestampMs, k -> new AtomicLong()).incrementAndGet();
        cleanup(timestampMs);
    }

    /**
     * Calculates the event rate over the sliding window, in events per second.
     *
     * @return The calculated rate.
     */
    public double getRate() {
        return getRate(System.currentTimeMillis());
    }

    /**
     * Calculates the event rate at a specific point in time.
     *
     * @param nowMs The current time in milliseconds.
     * @return The rate in events per second.
     */
    public double getRate(long nowMs) {
        long fromTime = nowMs - windowSizeMs;
        ConcurrentNavigableMap<Long, AtomicLong> windowView = buckets.subMap(fromTime, true, nowMs, true);

        long totalCount = windowView.values().stream().mapToLong(AtomicLong::get).sum();
        return (double) totalCount / (windowSizeMs / 1000.0);
    }

    /**
     * Removes old buckets that are outside the time window to prevent memory leaks.
     *
     * @param nowMs The current time in milliseconds.
     */
    private void cleanup(long nowMs) {
        long cutoff = nowMs - windowSizeMs;
        buckets.headMap(cutoff).clear();
    }
}