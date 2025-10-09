/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.utils.monitoring;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PercentileTracker utility class.
 */
@Tag("unit")
class PercentileTrackerTest {

    @Test
    void testConstructor_Default() {
        PercentileTracker tracker = new PercentileTracker();
        assertNotNull(tracker);
        assertEquals(12, tracker.getBucketCount());  // 11 boundaries + 1 overflow bucket
    }

    @Test
    void testConstructor_CustomBuckets() {
        long[] customBuckets = {100, 500, 1000, 5000};
        PercentileTracker tracker = new PercentileTracker(customBuckets);
        
        assertEquals(5, tracker.getBucketCount());  // 4 boundaries + 1 overflow
    }

    @Test
    void testConstructor_InvalidBuckets() {
        assertThrows(IllegalArgumentException.class, () -> new PercentileTracker(null));
        assertThrows(IllegalArgumentException.class, () -> new PercentileTracker(new long[0]));
    }

    @Test
    void testRecord_SingleValue() {
        PercentileTracker tracker = new PercentileTracker();
        tracker.record(5_000_000L);  // 5ms
        
        assertEquals(1, tracker.getCount());
        assertEquals(5_000_000L, tracker.getTotalSum());
    }

    @Test
    void testRecord_MultipleValues() {
        PercentileTracker tracker = new PercentileTracker();
        tracker.record(1_000_000L);   // 1ms
        tracker.record(5_000_000L);   // 5ms
        tracker.record(10_000_000L);  // 10ms
        
        assertEquals(3, tracker.getCount());
        assertEquals(16_000_000L, tracker.getTotalSum());
    }

    @Test
    void testGetAverage() {
        PercentileTracker tracker = new PercentileTracker();
        tracker.record(2_000_000L);   // 2ms
        tracker.record(4_000_000L);   // 4ms
        tracker.record(6_000_000L);   // 6ms
        
        double avg = tracker.getAverage();
        assertEquals(4_000_000.0, avg, 0.1);  // Average should be 4ms
    }

    @Test
    void testGetAverage_EmptyTracker() {
        PercentileTracker tracker = new PercentileTracker();
        assertEquals(0.0, tracker.getAverage());
    }

    @Test
    void testGetPercentile_Median() {
        PercentileTracker tracker = new PercentileTracker();
        
        // Record 100 values at 1ms
        for (int i = 0; i < 100; i++) {
            tracker.record(1_000_000L);
        }
        
        long p50 = tracker.getPercentile(50);
        // P50 should be at or below 1ms boundary
        assertTrue(p50 <= 1_000_000L, "P50 should be <= 1ms");
    }

    @Test
    void testGetPercentile_P95() {
        PercentileTracker tracker = new PercentileTracker();
        
        // Record 100 values: 95 at 1ms, 5 at 100ms
        for (int i = 0; i < 95; i++) {
            tracker.record(1_000_000L);
        }
        for (int i = 0; i < 5; i++) {
            tracker.record(100_000_000L);
        }
        
        long p95 = tracker.getPercentile(95);
        // P95 should capture the transition to higher latencies
        assertTrue(p95 >= 1_000_000L, "P95 should be >= 1ms");
    }

    @Test
    void testGetPercentile_P99() {
        PercentileTracker tracker = new PercentileTracker();
        
        // Record values with 1% outliers
        for (int i = 0; i < 99; i++) {
            tracker.record(5_000_000L);  // 5ms - typical
        }
        tracker.record(500_000_000L);  // 500ms - outlier
        
        long p99 = tracker.getPercentile(99);
        // P99 should reflect the outlier bucket
        assertTrue(p99 >= 5_000_000L, "P99 should be >= 5ms");
    }

    @Test
    void testGetPercentile_EdgeCases() {
        PercentileTracker tracker = new PercentileTracker();
        tracker.record(1_000_000L);
        
        assertEquals(0, tracker.getPercentile(0), "P0 should be 0");
        assertTrue(tracker.getPercentile(100) > 0, "P100 should be > 0");
    }

    @Test
    void testGetPercentile_InvalidPercentile() {
        PercentileTracker tracker = new PercentileTracker();
        
        assertThrows(IllegalArgumentException.class, () -> tracker.getPercentile(-1));
        assertThrows(IllegalArgumentException.class, () -> tracker.getPercentile(101));
    }

    @Test
    void testGetPercentile_EmptyTracker() {
        PercentileTracker tracker = new PercentileTracker();
        assertEquals(0, tracker.getPercentile(50));
    }

    @Test
    void testReset() {
        PercentileTracker tracker = new PercentileTracker();
        tracker.record(1_000_000L);
        tracker.record(2_000_000L);
        
        assertEquals(2, tracker.getCount());
        
        tracker.reset();
        
        assertEquals(0, tracker.getCount());
        assertEquals(0, tracker.getTotalSum());
        assertEquals(0.0, tracker.getAverage());
    }

    @Test
    void testConcurrentRecording() throws InterruptedException {
        PercentileTracker tracker = new PercentileTracker();
        int threadCount = 10;
        int valuesPerThread = 100;
        
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < valuesPerThread; j++) {
                    tracker.record(1_000_000L * (threadId + 1));  // Each thread uses different latency
                }
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        assertEquals(1000, tracker.getCount(), "Should have recorded all 1000 values");
        assertTrue(tracker.getAverage() > 0, "Average should be positive");
    }

    @Test
    void testCustomBuckets_MessageSizes() {
        // Custom buckets for message sizes (1KB, 10KB, 100KB, 1MB)
        long[] sizeBuckets = {1024L, 10_240L, 102_400L, 1_048_576L};
        PercentileTracker tracker = new PercentileTracker(sizeBuckets);
        
        tracker.record(500L);      // < 1KB
        tracker.record(5_000L);    // < 10KB
        tracker.record(50_000L);   // < 100KB
        tracker.record(500_000L);  // < 1MB
        
        assertEquals(4, tracker.getCount());
        
        long p50 = tracker.getPercentile(50);
        assertTrue(p50 >= 1024L && p50 <= 102_400L, "P50 should be in mid-range");
    }

    @Test
    void testBucketBoundaries() {
        long[] buckets = {10, 20, 30};
        PercentileTracker tracker = new PercentileTracker(buckets);
        
        tracker.record(5);   // bucket[0]: < 10
        tracker.record(15);  // bucket[1]: < 20
        tracker.record(25);  // bucket[2]: < 30
        tracker.record(35);  // bucket[3]: >= 30
        
        assertEquals(4, tracker.getCount());
    }

    @Test
    void testGetBucketValue() {
        long[] buckets = {10, 20};
        PercentileTracker tracker = new PercentileTracker(buckets);
        
        tracker.record(5);   // bucket 0
        tracker.record(5);   // bucket 0
        tracker.record(15);  // bucket 1
        
        assertEquals(2, tracker.getBucketValue(0));
        assertEquals(1, tracker.getBucketValue(1));
        assertEquals(0, tracker.getBucketValue(2));
    }

    @Test
    void testGetBucketBoundary() {
        long[] buckets = {10, 20, 30};
        PercentileTracker tracker = new PercentileTracker(buckets);
        
        assertEquals(0, tracker.getBucketBoundary(0));   // First bucket has no lower bound
        assertEquals(10, tracker.getBucketBoundary(1));
        assertEquals(20, tracker.getBucketBoundary(2));
        assertEquals(30, tracker.getBucketBoundary(3));
    }
}

