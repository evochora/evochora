package org.evochora.server.queue;

import org.evochora.server.contracts.IQueueMessage;
import org.evochora.server.contracts.raw.RawTickState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains unit tests for the {@link InMemoryTickQueue}.
 * These tests verify the basic functionality and thread-safety of the queue.
 * These are unit tests and do not require external resources.
 */
class InMemoryTickQueueTest {

    /**
     * Verifies the basic put and take operations on the queue, ensuring that
     * a message can be added and then removed, and that the queue size is updated correctly.
     * This is a unit test for the queue's core logic.
     * @throws Exception if queue operations fail.
     */
    @Test
    @Tag("unit")
    void putAndTake_shouldExchangeMessages() throws Exception {
        InMemoryTickQueue queue = new InMemoryTickQueue(1000);
        IQueueMessage message = new RawTickState(1L, Collections.emptyList(), Collections.emptyList());

        queue.put(message);
        assertThat(queue.size()).isEqualTo(1);

        IQueueMessage taken = queue.take();
        assertThat(taken).isInstanceOf(RawTickState.class);
        assertThat(queue.size()).isZero();
    }

    /**
     * Verifies that the queue can be safely accessed by a producer and a consumer
     * running in different threads. While named to suggest a backpressure test, this
     * test's primary value is in confirming the thread-safety of the put/take operations.
     * This is a multi-threaded unit test.
     * @throws Exception if thread or queue operations fail.
     */
    @Test
    @Tag("unit")
    void capacity_shouldApplyBackpressureByBytesHeuristic() throws Exception {
        InMemoryTickQueue queue = new InMemoryTickQueue(1000);

        var exec = Executors.newSingleThreadExecutor();
        Future<?> producer = exec.submit(() -> {
            try {
                // Heuristik: 1MB pro Tick, Queue-Größe ist 512MB, also ca. 512 Ticks
                // Wir fügen nur ein paar hinzu, um den Test nicht zu verlangsamen.
                for (int i = 0; i < 10; i++) {
                    queue.put(new RawTickState((long)i, Collections.emptyList(), Collections.emptyList()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        TimeUnit.MILLISECONDS.sleep(50);
        assertThat(queue.size()).isGreaterThan(0);

        for (int i = 0; i < 5; i++) {
            queue.take();
        }

        producer.get(1, TimeUnit.SECONDS);
        exec.shutdownNow();
    }
}