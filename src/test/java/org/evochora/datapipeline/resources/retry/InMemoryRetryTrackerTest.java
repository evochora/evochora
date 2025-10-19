package org.evochora.datapipeline.resources.retry;

import org.evochora.junit.extensions.logging.ExpectLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for InMemoryRetryTracker.
 * <p>
 * Tests focus on retry counting, FIFO eviction, thread-safety, and metrics.
 */
@Tag("unit")
class InMemoryRetryTrackerTest {
    
    private InMemoryRetryTracker tracker;
    
    @BeforeEach
    void setup() {
        tracker = new InMemoryRetryTracker(100);  // Small size for testing FIFO
    }
    
    @Test
    void testIncrementAndGetRetryCount_NewMessage() throws Exception {
        // When: Increment retry count for new message
        int count = tracker.incrementAndGetRetryCount("batch-001");
        
        // Then: Returns 1 (first failure)
        assertThat(count).isEqualTo(1);
    }
    
    @Test
    void testIncrementAndGetRetryCount_ExistingMessage() throws Exception {
        // Given: Message with existing retry count
        tracker.incrementAndGetRetryCount("batch-001");
        tracker.incrementAndGetRetryCount("batch-001");
        
        // When: Increment again
        int count = tracker.incrementAndGetRetryCount("batch-001");
        
        // Then: Returns 3 (third failure)
        assertThat(count).isEqualTo(3);
    }
    
    @Test
    void testGetRetryCount_NonExistent() throws Exception {
        // When: Get retry count for non-existent message
        int count = tracker.getRetryCount("batch-999");
        
        // Then: Returns 0
        assertThat(count).isEqualTo(0);
    }
    
    @Test
    void testMarkMovedToDlq() throws Exception {
        // Given: Message with retry count
        tracker.incrementAndGetRetryCount("batch-001");
        assertThat(tracker.getRetryCount("batch-001")).isEqualTo(1);
        
        // When: Mark as moved to DLQ
        tracker.markMovedToDlq("batch-001");
        
        // Then: Retry count should be cleared (active cleanup!)
        assertThat(tracker.getRetryCount("batch-001")).isEqualTo(0);
    }
    
    @Test
    void testResetRetryCount() throws Exception {
        // Given: Message with retry count
        tracker.incrementAndGetRetryCount("batch-001");
        tracker.incrementAndGetRetryCount("batch-001");
        assertThat(tracker.getRetryCount("batch-001")).isEqualTo(2);
        
        // When: Reset retry count (after successful processing)
        tracker.resetRetryCount("batch-001");
        
        // Then: Count should be cleared (active cleanup!)
        assertThat(tracker.getRetryCount("batch-001")).isEqualTo(0);
    }
    
    @Test
    void testFIFO_Eviction_OldestRemoved() throws Exception {
        // Given: Tracker with maxKeys=100
        InMemoryRetryTracker smallTracker = new InMemoryRetryTracker(10);
        
        // Fill to capacity: batch-0 to batch-9
        for (int i = 0; i < 10; i++) {
            smallTracker.incrementAndGetRetryCount("batch-" + i);
        }
        
        // Verify oldest (batch-0) still present
        assertThat(smallTracker.getRetryCount("batch-0")).isEqualTo(1);
        
        // When: Add one more (should evict batch-0)
        smallTracker.incrementAndGetRetryCount("batch-10");
        
        // Then: Oldest entry evicted via FIFO (Safety Net!)
        assertThat(smallTracker.getRetryCount("batch-0")).isEqualTo(0);
        assertThat(smallTracker.getRetryCount("batch-10")).isEqualTo(1);
        assertThat(smallTracker.getRetryCount("batch-1")).isEqualTo(1);  // Still present
    }
    
    @Test
    void testConcurrency_MultipleThreads() throws Exception {
        // Given: Tracker and concurrent executors
        InMemoryRetryTracker sharedTracker = new InMemoryRetryTracker(10000);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(100);
        AtomicInteger errors = new AtomicInteger(0);
        
        try {
            // When: 100 threads increment same counter concurrently
            for (int i = 0; i < 100; i++) {
                executor.submit(() -> {
                    try {
                        sharedTracker.incrementAndGetRetryCount("shared-batch");
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            // Wait for all threads to complete
            latch.await(5, TimeUnit.SECONDS);
            
            // Then: Counter should be exactly 100 (thread-safe!)
            assertThat(sharedTracker.getRetryCount("shared-batch")).isEqualTo(100);
            assertThat(errors.get()).isEqualTo(0);
            
        } finally {
            executor.shutdownNow();
        }
    }
    
    @Test
    void testMetrics() throws Exception {
        // Given: Some retry activity
        tracker.incrementAndGetRetryCount("batch-001");
        tracker.incrementAndGetRetryCount("batch-001");
        tracker.incrementAndGetRetryCount("batch-002");
        tracker.markMovedToDlq("batch-001");  // Cleanup + mark as DLQ
        tracker.resetRetryCount("batch-002");  // Cleanup after success
        
        // When: Get metrics
        var metrics = tracker.getMetrics();
        
        // Then: Verify metrics are tracked
        assertThat(metrics).containsKey("tracked_messages");
        assertThat(metrics).containsKey("dlq_moved_count");
        assertThat(metrics).containsKey("total_retries");
        assertThat(metrics).containsKey("capacity_utilization_percent");
        
        // Verify cleanup worked (both entries removed)
        assertThat(metrics.get("tracked_messages")).isEqualTo(0);
        assertThat(metrics.get("dlq_moved_count")).isEqualTo(1);
        assertThat(metrics.get("total_retries")).isEqualTo(3L);  // 2 + 1 increments
    }
    
    @Test
    void testActiveCleanup_RemovesEntriesImmediately() throws Exception {
        // Given: Message with retry count
        tracker.incrementAndGetRetryCount("batch-001");
        tracker.incrementAndGetRetryCount("batch-002");
        assertThat(tracker.getRetryCount("batch-001")).isEqualTo(1);
        assertThat(tracker.getRetryCount("batch-002")).isEqualTo(1);
        
        // When: Reset one, move one to DLQ (both should cleanup)
        tracker.resetRetryCount("batch-001");
        tracker.markMovedToDlq("batch-002");
        
        // Then: Both entries should be removed (active cleanup!)
        assertThat(tracker.getRetryCount("batch-001")).isEqualTo(0);
        assertThat(tracker.getRetryCount("batch-002")).isEqualTo(0);
    }
    
    @Test
    void testFIFO_Eviction_MetricsTracked() throws Exception {
        // Given: Tracker with small capacity
        InMemoryRetryTracker smallTracker = new InMemoryRetryTracker(5);
        
        // Fill to capacity + 3 more (should trigger 3 evictions)
        for (int i = 0; i < 8; i++) {
            smallTracker.incrementAndGetRetryCount("batch-" + i);
        }
        
        // When: Get metrics
        var metrics = smallTracker.getMetrics();
        
        // Then: Evictions should be tracked
        assertThat(metrics.get("total_evictions")).isEqualTo(3L);
        assertThat(metrics.get("tracked_messages")).isEqualTo(5);  // Max capacity
        assertThat(metrics.get("capacity_utilization_percent")).isEqualTo(100.0);
    }
}

