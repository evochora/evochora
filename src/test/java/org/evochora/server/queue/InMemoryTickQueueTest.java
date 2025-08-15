package org.evochora.server.queue;

import org.evochora.server.contracts.IQueueMessage;
import org.evochora.server.contracts.WorldStateMessage;
import org.evochora.server.setup.Config;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTickQueueTest {

    @Test
    void putAndTake_shouldExchangeMessages() throws Exception {
        InMemoryTickQueue queue = new InMemoryTickQueue(new Config(64L * 1024L * 1024L, "runs"));
        IQueueMessage message = new WorldStateMessage(1L, 0L, Collections.emptyList(), Collections.emptyList());

        queue.put(message);
        assertThat(queue.size()).isEqualTo(1);

        IQueueMessage taken = queue.take();
        assertThat(taken).isInstanceOf(WorldStateMessage.class);
        assertThat(queue.size()).isZero();
    }

    @Test
    void capacity_shouldApplyBackpressureByBytesHeuristic() throws Exception {
        InMemoryTickQueue queue = new InMemoryTickQueue(new Config(2_000_000L, "runs")); // ~2 MB

        var exec = Executors.newSingleThreadExecutor();
        Future<?> producer = exec.submit(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    queue.put(new WorldStateMessage(i, 0L, Collections.emptyList(), Collections.emptyList()));
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


