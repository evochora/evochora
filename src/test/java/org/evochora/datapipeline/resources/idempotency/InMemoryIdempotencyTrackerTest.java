package org.evochora.datapipeline.resources.idempotency;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
public class InMemoryIdempotencyTrackerTest {

    private InMemoryIdempotencyTracker<Integer> tracker;

    @BeforeEach
    void setUp() {
        tracker = new InMemoryIdempotencyTracker<>(1000);  // 1000 keys max
    }

    @Test
    void testCheckAndMarkProcessed_newKey_returnsTrue() {
        boolean result = tracker.checkAndMarkProcessed(1);
        assertTrue(result, "First time processing should return true");
    }

    @Test
    void testCheckAndMarkProcessed_duplicateKey_returnsFalse() {
        tracker.checkAndMarkProcessed(1);
        boolean result = tracker.checkAndMarkProcessed(1);
        assertFalse(result, "Duplicate key should return false");
    }

    @Test
    void testIsProcessed_newKey_returnsFalse() {
        assertFalse(tracker.isProcessed(1));
    }

    @Test
    void testIsProcessed_processedKey_returnsTrue() {
        tracker.markProcessed(1);
        assertTrue(tracker.isProcessed(1));
    }

    @Test
    void testMarkProcessed_addsKey() {
        tracker.markProcessed(1);
        assertTrue(tracker.isProcessed(1));
        assertEquals(1L, tracker.size());
    }

    @Test
    void testRemove_existingKey_returnsTrue() {
        tracker.markProcessed(1);
        assertTrue(tracker.remove(1));
        assertFalse(tracker.isProcessed(1));
    }

    @Test
    void testRemove_nonExistentKey_returnsFalse() {
        assertFalse(tracker.remove(999));
    }

    @Test
    void testClear_removesAllKeys() {
        tracker.markProcessed(1);
        tracker.markProcessed(2);
        tracker.markProcessed(3);
        assertEquals(3L, tracker.size());

        tracker.clear();
        assertEquals(0L, tracker.size());
        assertFalse(tracker.isProcessed(1));
    }

    @Test
    void testSize_tracksCorrectly() {
        assertEquals(0L, tracker.size());
        tracker.markProcessed(1);
        assertEquals(1L, tracker.size());
        tracker.markProcessed(2);
        assertEquals(2L, tracker.size());
        tracker.remove(1);
        assertEquals(1L, tracker.size());
    }

    @Test
    void testFIFO_eviction_oldestKeysRemoved() {
        InMemoryIdempotencyTracker<Integer> smallTracker = new InMemoryIdempotencyTracker<>(10);

        // Fill to capacity
        for (int i = 0; i < 10; i++) {
            smallTracker.markProcessed(i);
        }
        assertEquals(10L, smallTracker.size());
        assertTrue(smallTracker.isProcessed(0), "Oldest key (0) should still be present");

        // Add one more - should evict key 0
        smallTracker.markProcessed(10);
        assertEquals(10L, smallTracker.size(), "Size should remain at max capacity");
        assertFalse(smallTracker.isProcessed(0), "Oldest key (0) should be evicted");
        assertTrue(smallTracker.isProcessed(10), "New key (10) should be present");
        assertTrue(smallTracker.isProcessed(1), "Key (1) should still be present");
    }

    @Test
    void testFIFO_eviction_multipleOldestRemoved() {
        InMemoryIdempotencyTracker<Integer> smallTracker = new InMemoryIdempotencyTracker<>(5);

        // Fill to capacity: 0, 1, 2, 3, 4
        for (int i = 0; i < 5; i++) {
            smallTracker.markProcessed(i);
        }

        // Add 3 more: should evict 0, 1, 2
        for (int i = 5; i < 8; i++) {
            smallTracker.markProcessed(i);
        }

        assertEquals(5L, smallTracker.size());
        assertFalse(smallTracker.isProcessed(0));
        assertFalse(smallTracker.isProcessed(1));
        assertFalse(smallTracker.isProcessed(2));
        assertTrue(smallTracker.isProcessed(3));
        assertTrue(smallTracker.isProcessed(7));
    }

    @Test
    void testCheckAndMarkProcessed_afterEviction_treatsAsNew() {
        InMemoryIdempotencyTracker<Integer> smallTracker = new InMemoryIdempotencyTracker<>(5);

        // Fill to capacity and evict key 0
        for (int i = 0; i < 6; i++) {
            smallTracker.checkAndMarkProcessed(i);
        }

        // Key 0 was evicted - should be treated as new
        boolean result = smallTracker.checkAndMarkProcessed(0);
        assertTrue(result, "Evicted key should be treated as new");
    }

    @Test
    void testEvictionMetrics_trackCorrectly() {
        InMemoryIdempotencyTracker<Integer> smallTracker = new InMemoryIdempotencyTracker<>(10);

        // Fill to capacity - no evictions yet
        for (int i = 0; i < 10; i++) {
            smallTracker.markProcessed(i);
        }
        assertEquals(0L, smallTracker.getTotalEvictions());

        // Add 5 more - should trigger 5 evictions
        for (int i = 10; i < 15; i++) {
            smallTracker.markProcessed(i);
        }
        assertEquals(5L, smallTracker.getTotalEvictions());
    }

    @Test
    void testConcurrency_multipleThreads() throws InterruptedException {
        int numThreads = 10;
        int operationsPerThread = 100;
        Thread[] threads = new Thread[numThreads];

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < operationsPerThread; i++) {
                    int key = threadId * operationsPerThread + i;
                    tracker.checkAndMarkProcessed(key);
                }
            });
            threads[t].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(numThreads * operationsPerThread, tracker.size());
    }

    @Test
    void testDifferentKeyTypes_String() {
        InMemoryIdempotencyTracker<String> stringTracker = new InMemoryIdempotencyTracker<>(1000);

        assertTrue(stringTracker.checkAndMarkProcessed("msg-001"));
        assertFalse(stringTracker.checkAndMarkProcessed("msg-001"));
        assertTrue(stringTracker.checkAndMarkProcessed("msg-002"));
    }

    @Test
    void testMaxKeysConfiguration() {
        InMemoryIdempotencyTracker<Integer> customTracker = new InMemoryIdempotencyTracker<>(100);
        assertEquals(100, customTracker.getMaxKeys());
    }
}
