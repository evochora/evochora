/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.utils.monitoring;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SlidingWindowPercentiles utility class.
 */
@Tag("unit")
class SlidingWindowPercentilesTest {

    @Test
    void testConstructor_Valid() {
        SlidingWindowPercentiles percentiles = new SlidingWindowPercentiles(5);
        assertNotNull(percentiles);
        assertEquals(0, percentiles.getSecondBucketCount());
    }

    @Test
    void testConstructor_Invalid() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowPercentiles(0));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowPercentiles(-1));
    }

    @Test
    void testRecord_SingleValue() {
        SlidingWindowPercentiles percentiles = new SlidingWindowPercentiles(5);
        percentiles.record(5_000_000L);  // 5ms
        
        assertEquals(1, percentiles.getCount());
        assertEquals(5_000_000.0, percentiles.getAverage(), 0.1);
    }

    @Test
    void testRecord_MultipleValues() {
        SlidingWindowPercentiles percentiles = new SlidingWindowPercentiles(5);
        percentiles.record(1_000_000L);   // 1ms
        percentiles.record(5_000_000L);   // 5ms
        percentiles.record(10_000_000L);  // 10ms
        
        assertEquals(3, percentiles.getCount());
        
        double avg = percentiles.getAverage();
        assertTrue(avg >= 5_000_000 && avg <= 6_000_000, "Average should be around 5.33ms");
    }

    @Test
    void testGetPercentile_Median() {
        SlidingWindowPercentiles percentiles = new SlidingWindowPercentiles(5);
        
        // Record 100 values at different latencies
        for (int i = 0; i < 50; i++) {
            percentiles.record(1_000_000L);  // 1ms
        }
        for (int i = 0; i < 50; i++) {
            percentiles.record(10_000_000L);  // 10ms
        }
        
        long p50 = percentiles.getPercentile(50);
        // P50 should be around the boundary between 1ms and 10ms
        assertTrue(p50 >= 1_000_000L && p50 <= 10_000_000L);
    }

    @Test
    void testGetPercentile_P95() {
        SlidingWindowPercentiles percentiles = new SlidingWindowPercentiles(5);
        
        // 95 fast, 5 slow
        for (int i = 0; i < 95; i++) {
            percentiles.record(1_000_000L);  // 1ms
        }
        for (int i = 0; i < 5; i++) {
            percentiles.record(100_000_000L);  // 100ms
        }
        
        long p95 = percentiles.getPercentile(95);
        assertTrue(p95 >= 1_000_000L, "P95 should capture the slower operations");
    }

    @Test
    void testGetPercentile_EmptyTracker() {
        SlidingWindowPercentiles percentiles = new SlidingWindowPercentiles(5);
        assertEquals(0, percentiles.getPercentile(50));
        assertEquals(0, percentiles.getPercentile(95));
        assertEquals(0, percentiles.getPercentile(99));
    }

    @Test
    void testGetPercentile_InvalidPercentile() {
        SlidingWindowPercentiles percentiles = new SlidingWindowPercentiles(5);
        
        assertThrows(IllegalArgumentException.class, () -> percentiles.getPercentile(-1));
        assertThrows(IllegalArgumentException.class, () -> percentiles.getPercentile(101));
    }

    @Test
    void testGetAverage() {
        SlidingWindowPercentiles percentiles = new SlidingWindowPercentiles(5);
        percentiles.record(2_000_000L);  // 2ms
        percentiles.record(4_000_000L);  // 4ms
        percentiles.record(6_000_000L);  // 6ms
        
        double avg = percentiles.getAverage();
        assertEquals(4_000_000.0, avg, 0.1);  // Average should be 4ms
    }

    @Test
    void testGetAverage_EmptyTracker() {
        SlidingWindowPercentiles percentiles = new SlidingWindowPercentiles(5);
        assertEquals(0.0, percentiles.getAverage());
    }

    @Test
    void testGetCount() {
        SlidingWindowPercentiles percentiles = new SlidingWindowPercentiles(5);
        
        assertEquals(0, percentiles.getCount());
        
        percentiles.record(1_000_000L);
        assertEquals(1, percentiles.getCount());
        
        percentiles.record(2_000_000L);
        assertEquals(2, percentiles.getCount());
    }

    @Test
    void testSlidingWindow_DecaysOverTime() throws InterruptedException {
        SlidingWindowPercentiles percentiles = new SlidingWindowPercentiles(2);  // 2-second window
        
        // Record 10 values
        for (int i = 0; i < 10; i++) {
            percentiles.record(5_000_000L);
        }
        
        long initialCount = percentiles.getCount();
        assertEquals(10, initialCount);
        
        // Wait for window to expire
        Thread.sleep(2200);
        
        long laterCount = percentiles.getCount();
        // Old values should have fallen out of window
        assertTrue(laterCount < initialCount || laterCount == 0, 
                "Count should decay as old buckets fall out of window");
    }

    @Test
    void testCleanup_OldBucketsRemoved() throws InterruptedException {
        SlidingWindowPercentiles percentiles = new SlidingWindowPercentiles(1);  // 1-second window
        
        percentiles.record(1_000_000L);
        assertEquals(1, percentiles.getSecondBucketCount());
        
        // Wait for window to expire
        Thread.sleep(1200);
        
        // Recording new value should trigger cleanup
        percentiles.record(2_000_000L);
        
        // Should have cleaned up old buckets
        assertTrue(percentiles.getSecondBucketCount() <= 6, 
                "Should have at most windowSeconds + 5 buckets");
    }

    @Test
    void testConcurrentRecording() throws InterruptedException {
        SlidingWindowPercentiles percentiles = new SlidingWindowPercentiles(5);
        int threadCount = 10;
        int valuesPerThread = 100;
        
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < valuesPerThread; j++) {
                    percentiles.record((threadId + 1) * 1_000_000L);
                }
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        assertEquals(1000, percentiles.getCount(), "Should have recorded all 1000 values");
        assertTrue(percentiles.getAverage() > 0, "Average should be positive");
    }
}

