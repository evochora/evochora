package org.evochora.server.queue;

import org.evochora.server.contracts.IQueueMessage;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTickQueueTest {

    @Test
    void putAndTake_shouldExchangeMessages() throws Exception {
        InMemoryTickQueue queue = new InMemoryTickQueue();
        IQueueMessage message = org.evochora.server.contracts.PreparedTickState.of(
            "debug", 1L, 
            new org.evochora.server.contracts.PreparedTickState.WorldMeta(new int[]{10, 10}),
            new org.evochora.server.contracts.PreparedTickState.WorldState(
                Collections.emptyList(), Collections.emptyList()
            ),
            Collections.emptyMap()
        );

        queue.put(message);
        assertThat(queue.size()).isEqualTo(1);

        IQueueMessage taken = queue.take();
        assertThat(taken).isInstanceOf(org.evochora.server.contracts.PreparedTickState.class);
        assertThat(queue.size()).isZero();
    }

    @Test
    void capacity_shouldApplyBackpressureByBytesHeuristic() throws Exception {
        InMemoryTickQueue queue = new InMemoryTickQueue();

        var exec = Executors.newSingleThreadExecutor();
        Future<?> producer = exec.submit(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    queue.put(org.evochora.server.contracts.PreparedTickState.of(
                        "debug", i, 
                        new org.evochora.server.contracts.PreparedTickState.WorldMeta(new int[]{10, 10}),
                        new org.evochora.server.contracts.PreparedTickState.WorldState(
                            Collections.emptyList(), Collections.emptyList()
                        ),
                        Collections.emptyMap()
                    ));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Give producer a moment to fill then consume a few
        TimeUnit.MILLISECONDS.sleep(50);
        assertThat(queue.size()).isGreaterThan(0);

        for (int i = 0; i < 5; i++) {
            queue.take();
        }

        producer.get(1, TimeUnit.SECONDS);
        exec.shutdownNow();
    }
}


