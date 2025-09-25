package org.evochora.datapipeline.resources.queues;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.wrappers.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.wrappers.queues.IOutputQueueResource;
import org.evochora.datapipeline.resources.queues.wrappers.MonitoredQueueConsumer;
import org.evochora.datapipeline.resources.queues.wrappers.MonitoredQueueProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("unit")
public class InMemoryBlockingQueueTest {

    private InMemoryBlockingQueue<String> queue;
    private IInputQueueResource<String> consumer;
    private IOutputQueueResource<String> producer;

    @BeforeEach
    void setUp() {
        Config config = ConfigFactory.parseMap(Map.of("capacity", 10));
        queue = new InMemoryBlockingQueue<>(config);

        ResourceContext producerContext = new ResourceContext("test-service", "out", "queue-out", Collections.emptyMap());
        producer = (IOutputQueueResource<String>) queue.getWrappedResource(producerContext);

        ResourceContext consumerContext = new ResourceContext("test-service", "in", "queue-in", Collections.emptyMap());
        consumer = (IInputQueueResource<String>) queue.getWrappedResource(consumerContext);
    }

    @Test
    void testContextualWrapping() {
        assertTrue(producer instanceof MonitoredQueueProducer);
        assertTrue(consumer instanceof MonitoredQueueConsumer);
        assertThrows(IllegalArgumentException.class, () -> {
            ResourceContext invalidContext = new ResourceContext("test-service", "invalid", "invalid-type", Collections.emptyMap());
            queue.getWrappedResource(invalidContext);
        });
    }

    @Test
    void testGlobalMetrics() {
        Map<String, Number> metrics = queue.getMetrics();
        assertEquals(10, metrics.get("capacity"));
        assertEquals(0, metrics.get("current_size"));
    }

    // Basic Blocking Operations
    @Test
    void testPutAndTake() throws InterruptedException {
        producer.put("test-message");
        assertEquals("test-message", consumer.take());
    }

    // Non-Blocking Operations
    @Test
    void testOfferAndPoll() {
        assertTrue(producer.offer("test-message"));
        Optional<String> received = consumer.poll();
        assertTrue(received.isPresent());
        assertEquals("test-message", received.get());
    }

    @Test
    void testOfferFailsWhenFull() {
        for (int i = 0; i < 10; i++) {
            assertTrue(producer.offer("message" + i));
        }
        assertFalse(producer.offer("extra-message"));
    }

    @Test
    void testPollReturnsEmptyWhenEmpty() {
        assertTrue(consumer.poll().isEmpty());
    }

    // Timeout Operations
    @Test
    @Timeout(2)
    void testOfferWithTimeoutSucceeds() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            producer.offer("message" + i);
        }
        // This will block, so run in a separate thread
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean offerSuccess = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        executor.submit(() -> {
            try {
                offerSuccess.set(producer.offer("timeout-message", 1, TimeUnit.SECONDS));
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(100); // give the producer time to block
        consumer.take(); // make space
        latch.await(1, TimeUnit.SECONDS);
        assertTrue(offerSuccess.get());
        executor.shutdown();
    }

    @Test
    void testPollWithTimeoutReturnsEmpty() throws InterruptedException {
        Optional<String> result = consumer.poll(10, TimeUnit.MILLISECONDS);
        assertTrue(result.isEmpty());
    }

    // Batch Operations
    @Test
    void testPutAllAndDrainTo() throws InterruptedException {
        List<String> items = List.of("batch1", "batch2", "batch3");
        producer.putAll(items);

        List<String> drainedItems = new ArrayList<>();
        int count = consumer.drainTo(drainedItems, 5);

        assertEquals(3, count);
        assertEquals(items, drainedItems);
    }

    @Test
    void testDrainToWithMaxElements() throws InterruptedException {
        producer.putAll(List.of("1", "2", "3", "4", "5"));

        List<String> drainedItems = new ArrayList<>();
        int count = consumer.drainTo(drainedItems, 3);

        assertEquals(3, count);
        assertEquals(List.of("1", "2", "3"), drainedItems);
        assertEquals(2, queue.getMetrics().get("current_size"));
    }

    // Service-Specific Metrics
    @Test
    void testServiceSpecificMetrics() throws InterruptedException {
        producer.put("message1");
        producer.offer("message2");
        producer.putAll(List.of("batch1", "batch2"));

        consumer.take();
        consumer.poll();
        consumer.drainTo(new ArrayList<>(), 2);

        Map<String, Number> producerMetrics = ((MonitoredQueueProducer<String>) producer).getMetrics();
        assertEquals(4L, producerMetrics.get("messages_sent"));

        Map<String, Number> consumerMetrics = ((MonitoredQueueConsumer<String>) consumer).getMetrics();
        assertEquals(4L, consumerMetrics.get("messages_consumed"));
    }

    // Usage State
    @Test
    void testUsageState() {
        assertEquals(IResource.UsageState.WAITING, queue.getUsageState("queue-in"));
        assertEquals(IResource.UsageState.ACTIVE, queue.getUsageState("queue-out"));

        producer.offer("message");

        assertEquals(IResource.UsageState.ACTIVE, queue.getUsageState("queue-in"));

        for (int i = 0; i < 9; i++) {
            producer.offer("message" + i);
        }

        assertEquals(IResource.UsageState.WAITING, queue.getUsageState("queue-out"));
    }

    // Thread Safety
    @Test
    void testThreadSafety() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(10);

        Runnable producerTask = () -> {
            for (int i = 0; i < 5; i++) {
                try {
                    producer.put("message" + i);
                } catch (InterruptedException e) { fail(e); }
            }
        };

        Runnable consumerTask = () -> {
            for (int i = 0; i < 5; i++) {
                try {
                    assertNotNull(consumer.take());
                    latch.countDown();
                } catch (InterruptedException e) { fail(e); }
            }
        };

        executor.submit(producerTask);
        executor.submit(consumerTask);

        // This is a simplified test. A more robust test would use more tasks and latches.
        // For now, we assume if the producer can finish, the consumer will eventually consume.
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Wait for consumer to finish processing after producer is done
        Thread.sleep(100);

        Map<String, Number> producerMetrics = ((MonitoredQueueProducer<String>) producer).getMetrics();
        assertEquals(5L, producerMetrics.get("messages_sent"));

        Map<String, Number> consumerMetrics = ((MonitoredQueueConsumer<String>) consumer).getMetrics();
        assertEquals(5L, consumerMetrics.get("messages_consumed"));

        assertEquals(0, queue.getMetrics().get("current_size"));
    }
}