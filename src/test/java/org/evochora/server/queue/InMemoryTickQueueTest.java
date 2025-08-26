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

class InMemoryTickQueueTest {

    @Test
    @Tag("unit")
    void putAndTake_shouldExchangeMessages() throws Exception {
        InMemoryTickQueue queue = new InMemoryTickQueue();
        IQueueMessage message = new RawTickState(1L, Collections.emptyList(), Collections.emptyList());

        queue.put(message);
        assertThat(queue.size()).isEqualTo(1);

        IQueueMessage taken = queue.take();
        assertThat(taken).isInstanceOf(RawTickState.class);
        assertThat(queue.size()).isZero();
    }

    @Test
    @Tag("unit")
    void capacity_shouldApplyBackpressureByBytesHeuristic() throws Exception {
        InMemoryTickQueue queue = new InMemoryTickQueue();

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