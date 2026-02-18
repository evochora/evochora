package org.evochora.datapipeline.resources.queues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.queues.StreamingBatch;
import org.evochora.datapipeline.resources.queues.wrappers.MonitoredQueueConsumer;
import org.evochora.datapipeline.resources.queues.wrappers.MonitoredQueueProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

@Tag("unit")
public class InMemoryBlockingQueueTest {

    private InMemoryBlockingQueue<String> queue;
    private IInputQueueResource<String> consumer;
    private IOutputQueueResource<String> producer;

    @BeforeEach
    void setUp() {
        Config config = ConfigFactory.parseMap(Map.of("capacity", 10));
        queue = new InMemoryBlockingQueue<>("test-queue", config);

        ResourceContext producerContext = new ResourceContext("test-service", "out", "queue-out", "test-queue", Collections.emptyMap());
        @SuppressWarnings("unchecked")
        IOutputQueueResource<String> prod = (IOutputQueueResource<String>) queue.getWrappedResource(producerContext);
        producer = prod;

        ResourceContext consumerContext = new ResourceContext("test-service", "in", "queue-in", "test-queue", Collections.emptyMap());
        @SuppressWarnings("unchecked")
        IInputQueueResource<String> cons = (IInputQueueResource<String>) queue.getWrappedResource(consumerContext);
        consumer = cons;
    }

    @Test
    void testContextualWrapping() {
        assertTrue(producer instanceof MonitoredQueueProducer);
        assertTrue(consumer instanceof MonitoredQueueConsumer);
        assertThrows(IllegalArgumentException.class, () -> {
            ResourceContext invalidContext = new ResourceContext("test-service", "invalid", "invalid-type", "test-queue", Collections.emptyMap());
            queue.getWrappedResource(invalidContext);
        });
    }

    @Test
    void testGlobalMetrics() {
        Map<String, Number> metrics = queue.getMetrics();
        assertEquals(10, metrics.get("capacity"));
        assertEquals(0, metrics.get("current_size"));
    }

    @Test
    void testPutAndReceive() throws InterruptedException {
        producer.put("test-message");
        try (StreamingBatch<String> batch = consumer.receiveBatch(1, 5, TimeUnit.SECONDS)) {
            assertEquals(1, batch.size());
            assertEquals("test-message", batch.iterator().next());
        }
    }

    @Test
    void testOfferAndReceive() throws InterruptedException {
        assertTrue(producer.offer("test-message"));
        try (StreamingBatch<String> batch = consumer.receiveBatch(1, 0, TimeUnit.MILLISECONDS)) {
            assertEquals(1, batch.size());
            assertEquals("test-message", batch.iterator().next());
        }
    }

    @Test
    void testOfferFailsWhenFull() {
        for (int i = 0; i < 10; i++) {
            assertTrue(producer.offer("message" + i));
        }
        assertFalse(producer.offer("extra-message"));
    }

    @Test
    void testReceiveReturnsEmptyWhenEmpty() throws InterruptedException {
        try (StreamingBatch<String> batch = consumer.receiveBatch(1, 0, TimeUnit.MILLISECONDS)) {
            assertEquals(0, batch.size());
        }
    }

    @Test
    @Timeout(2)
    void testOfferWithTimeoutSucceeds() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            producer.offer("message" + i);
        }
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

        Thread.sleep(100);
        // Consume one item to free space
        try (StreamingBatch<String> batch = consumer.receiveBatch(1, 0, TimeUnit.MILLISECONDS)) {
            assertEquals(1, batch.size());
        }
        latch.await(1, TimeUnit.SECONDS);
        assertTrue(offerSuccess.get());
        executor.shutdown();
    }

    @Test
    void testReceiveWithTimeoutReturnsEmpty() throws InterruptedException {
        try (StreamingBatch<String> batch = consumer.receiveBatch(1, 10, TimeUnit.MILLISECONDS)) {
            assertEquals(0, batch.size());
        }
    }

    @Test
    void testPutAllAndReceiveBatch() throws InterruptedException {
        List<String> items = List.of("batch1", "batch2", "batch3");
        producer.putAll(items);

        try (StreamingBatch<String> batch = consumer.receiveBatch(5, 0, TimeUnit.MILLISECONDS)) {
            assertEquals(3, batch.size());
            List<String> drainedItems = new ArrayList<>();
            batch.iterator().forEachRemaining(drainedItems::add);
            assertEquals(items, drainedItems);
        }
    }

    @Test
    void testReceiveBatchWithMaxElements() throws InterruptedException {
        producer.putAll(List.of("1", "2", "3", "4", "5"));

        try (StreamingBatch<String> batch = consumer.receiveBatch(3, 0, TimeUnit.MILLISECONDS)) {
            assertEquals(3, batch.size());
            List<String> drainedItems = new ArrayList<>();
            batch.iterator().forEachRemaining(drainedItems::add);
            assertEquals(List.of("1", "2", "3"), drainedItems);
        }
        assertEquals(2, queue.getMetrics().get("current_size"));
    }

    @Test
    void testOfferAll() {
        ResourceContext isolatedContext = new ResourceContext("isolated-service", "out", "queue-out", "test-queue", Collections.emptyMap());
        @SuppressWarnings("unchecked")
        IOutputQueueResource<String> isolatedProducer = (IOutputQueueResource<String>) queue.getWrappedResource(isolatedContext);
        assertTrue(isolatedProducer instanceof MonitoredQueueProducer);
        MonitoredQueueProducer<String> monitoredProducer = (MonitoredQueueProducer<String>) isolatedProducer;

        List<String> items = List.of("batch1", "batch2", "batch3", "batch4", "batch5");
        int count1 = monitoredProducer.offerAll(items);
        assertEquals(5, count1);
        assertEquals(5, queue.getMetrics().get("current_size"));
        assertEquals(5L, monitoredProducer.getMetrics().get("messages_sent"));

        int count2 = monitoredProducer.offerAll(items);
        assertEquals(5, count2);
        assertEquals(10, queue.getMetrics().get("current_size"));
        assertEquals(10L, monitoredProducer.getMetrics().get("messages_sent"));

        int count3 = monitoredProducer.offerAll(List.of("extra1", "extra2"));
        assertEquals(0, count3);
        assertEquals(10, queue.getMetrics().get("current_size"));
        assertEquals(10L, monitoredProducer.getMetrics().get("messages_sent"));
    }

    @Test
    void testServiceSpecificMetrics() throws InterruptedException {
        producer.put("message1");
        producer.offer("message2");
        producer.putAll(List.of("batch1", "batch2"));

        // Consume all 4 messages using receiveBatch
        try (StreamingBatch<String> batch = consumer.receiveBatch(4, 0, TimeUnit.MILLISECONDS)) {
            assertEquals(4, batch.size());
        }

        Map<String, Number> producerMetrics = ((MonitoredQueueProducer<String>) producer).getMetrics();
        assertEquals(4L, producerMetrics.get("messages_sent"));

        Map<String, Number> consumerMetrics = ((MonitoredQueueConsumer<String>) consumer).getMetrics();
        assertEquals(4L, consumerMetrics.get("messages_consumed"));
    }

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
                    try (StreamingBatch<String> batch = consumer.receiveBatch(1, 5, TimeUnit.SECONDS)) {
                        assertEquals(1, batch.size());
                        assertNotNull(batch.iterator().next());
                        latch.countDown();
                    }
                } catch (InterruptedException e) { fail(e); }
            }
        };

        executor.submit(producerTask);
        executor.submit(consumerTask);

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        Thread.sleep(100);

        Map<String, Number> producerMetrics = ((MonitoredQueueProducer<String>) producer).getMetrics();
        assertEquals(5L, producerMetrics.get("messages_sent"));

        Map<String, Number> consumerMetrics = ((MonitoredQueueConsumer<String>) consumer).getMetrics();
        assertEquals(5L, consumerMetrics.get("messages_consumed"));

        assertEquals(0, queue.getMetrics().get("current_size"));
    }

    @Test
    void testResourceNameIsInherited() {
        // Test base resource
        assertEquals("test-queue", queue.getResourceName());

        // Test wrapped producer
        IResource wrappedProducer = (IResource) producer;
        assertEquals("test-queue", wrappedProducer.getResourceName());

        // Test wrapped consumer
        IResource wrappedConsumer = (IResource) consumer;
        assertEquals("test-queue", wrappedConsumer.getResourceName());
    }
}
