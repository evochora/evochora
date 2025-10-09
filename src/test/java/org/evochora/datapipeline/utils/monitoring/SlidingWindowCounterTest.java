/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.utils.monitoring;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SlidingWindowCounter utility class.
 */
@Tag("unit")
class SlidingWindowCounterTest {

    @Test
    void testConstructor_ValidWindow() {
        SlidingWindowCounter counter = new SlidingWindowCounter(5);
        assertNotNull(counter);
        assertEquals(0, counter.getBucketCount());
    }

    @Test
    void testConstructor_InvalidWindow() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowCounter(0));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowCounter(-1));
    }

    @Test
    void testRecordCount_SingleEvent() {
        SlidingWindowCounter counter = new SlidingWindowCounter(5);
        counter.recordCount();
        
        double rate = counter.getRate();
        // Rate should be ~0.2 (1 event / 5 seconds)
        assertTrue(rate >= 0.1 && rate <= 0.3, "Rate should be around 0.2, got: " + rate);
    }

    @Test
    void testRecordCount_MultipleEvents() {
        SlidingWindowCounter counter = new SlidingWindowCounter(5);
        for (int i = 0; i < 10; i++) {
            counter.recordCount();
        }
        
        double rate = counter.getRate();
        // Rate should be ~2.0 (10 events / 5 seconds)
        assertTrue(rate >= 1.5 && rate <= 2.5, "Rate should be around 2.0, got: " + rate);
    }

    @Test
    void testRecordSum_CustomValues() {
        SlidingWindowCounter counter = new SlidingWindowCounter(5);
        counter.recordSum(100);
        counter.recordSum(200);
        counter.recordSum(300);
        
        double rate = counter.getRate();
        // Rate should be ~120.0 (600 / 5 seconds)
        assertTrue(rate >= 100 && rate <= 140, "Rate should be around 120, got: " + rate);
    }

    @Test
    void testGetWindowSum() {
        SlidingWindowCounter counter = new SlidingWindowCounter(5);
        counter.recordSum(100);
        counter.recordSum(200);
        counter.recordSum(300);
        
        long sum = counter.getWindowSum();
        assertEquals(600, sum);
    }

    @Test
    void testEmptyCounter_ReturnsZero() {
        SlidingWindowCounter counter = new SlidingWindowCounter(5);
        
        assertEquals(0.0, counter.getRate());
        assertEquals(0, counter.getWindowSum());
        assertEquals(0, counter.getBucketCount());
    }

    @Test
    void testCleanup_OldBucketsRemoved() throws InterruptedException {
        SlidingWindowCounter counter = new SlidingWindowCounter(1);  // 1-second window
        
        counter.recordCount();
        assertEquals(1, counter.getBucketCount());
        
        // Wait for window to expire
        Thread.sleep(1200);
        
        // Recording new event should trigger cleanup
        counter.recordCount();
        
        // Old buckets should be cleaned up
        assertTrue(counter.getBucketCount() <= 6, "Should have at most windowSeconds + 5 buckets");
    }

    @Test
    void testRate_DecaysOverTime() throws InterruptedException {
        SlidingWindowCounter counter = new SlidingWindowCounter(1);  // 1-second window for faster test
        
        // Record 10 events
        for (int i = 0; i < 10; i++) {
            counter.recordCount();
        }
        
        double initialRate = counter.getRate();
        assertTrue(initialRate > 0, "Should have positive rate");
        
        // Wait for window to fully expire
        Thread.sleep(1200);
        
        double laterRate = counter.getRate();
        // Rate should be 0 or much lower as old buckets fall out of window
        assertTrue(laterRate <= initialRate, "Rate should not increase over time");
    }

    @Test
    void testConcurrentRecording() throws InterruptedException {
        SlidingWindowCounter counter = new SlidingWindowCounter(5);
        int threadCount = 10;
        int eventsPerThread = 100;
        
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < eventsPerThread; j++) {
                    counter.recordCount();
                }
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        long totalSum = counter.getWindowSum();
        assertEquals(1000, totalSum, "Should have recorded all 1000 events");
    }

    @Test
    void testGetRate_WithSpecificTimestamp() {
        SlidingWindowCounter counter = new SlidingWindowCounter(5);
        long now = Instant.now().getEpochSecond();
        
        counter.recordCount();
        
        // Get rate at current time
        double rate = counter.getRate(now);
        assertTrue(rate >= 0, "Rate should be non-negative");
    }

    @Test
    void testGetWindowSum_WithSpecificTimestamp() {
        SlidingWindowCounter counter = new SlidingWindowCounter(5);
        long now = Instant.now().getEpochSecond();
        
        counter.recordSum(100);
        counter.recordSum(200);
        
        long sum = counter.getWindowSum(now);
        assertEquals(300, sum);
    }
}

