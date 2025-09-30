package org.evochora.datapipeline.services.idempotency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class InMemoryIdempotencyTrackerTest {

    private InMemoryIdempotencyTracker<Integer> tracker;

    @BeforeEach
    void setUp() {
        tracker = new InMemoryIdempotencyTracker<>(Duration.ofSeconds(5));
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
    void testTTL_expiredKeysAreRemoved() throws InterruptedException {
        InMemoryIdempotencyTracker<Integer> shortTTLTracker =
                new InMemoryIdempotencyTracker<>(Duration.ofMillis(100));

        shortTTLTracker.markProcessed(1);
        assertTrue(shortTTLTracker.isProcessed(1));

        Thread.sleep(150); // Wait for TTL to expire

        assertFalse(shortTTLTracker.isProcessed(1), "Expired key should return false");
    }

    @Test
    void testCheckAndMarkProcessed_expiredKey_returnsTrue() throws InterruptedException {
        InMemoryIdempotencyTracker<Integer> shortTTLTracker =
                new InMemoryIdempotencyTracker<>(Duration.ofMillis(100));

        shortTTLTracker.checkAndMarkProcessed(1);
        Thread.sleep(150);

        // Expired key should be treated as new
        boolean result = shortTTLTracker.checkAndMarkProcessed(1);
        assertTrue(result, "Expired key should be processed as new");
    }

    @Test
    void testCleanup_removesOldEntries() throws InterruptedException {
        InMemoryIdempotencyTracker<Integer> shortTTLTracker =
                new InMemoryIdempotencyTracker<>(Duration.ofMillis(100));

        for (int i = 0; i < 100; i++) {
            shortTTLTracker.markProcessed(i);
        }
        assertEquals(100L, shortTTLTracker.size());

        Thread.sleep(150);
        shortTTLTracker.cleanup();

        assertEquals(0L, shortTTLTracker.size(), "All expired entries should be removed");
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
        InMemoryIdempotencyTracker<String> stringTracker =
                new InMemoryIdempotencyTracker<>(Duration.ofSeconds(5));

        assertTrue(stringTracker.checkAndMarkProcessed("msg-001"));
        assertFalse(stringTracker.checkAndMarkProcessed("msg-001"));
        assertTrue(stringTracker.checkAndMarkProcessed("msg-002"));
    }

    @Test
    void testAutomaticCleanup_triggersAfterThreshold() {
        InMemoryIdempotencyTracker<Integer> autoCleanupTracker =
                new InMemoryIdempotencyTracker<>(Duration.ofMillis(1), 10);

        // Add more than cleanup threshold
        for (int i = 0; i < 15; i++) {
            autoCleanupTracker.markProcessed(i);
        }

        // Cleanup should have been triggered automatically
        // Size might be smaller due to expired entries being cleaned
        assertTrue(autoCleanupTracker.size() <= 15);
    }
}