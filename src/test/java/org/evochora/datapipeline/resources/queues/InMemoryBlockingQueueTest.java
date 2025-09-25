package org.evochora.datapipeline.resources.queues;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.wrappers.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.wrappers.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
public class InMemoryBlockingQueueTest {

    private InMemoryBlockingQueue<String> queue;
    private Config config;

    @BeforeEach
    void setUp() {
        config = ConfigFactory.parseMap(Map.of("capacity", 10));
        queue = new InMemoryBlockingQueue<>(config);
    }

    @Test
    void testBasicSendAndReceive() {
        ResourceContext producerContext = new ResourceContext("test-service", "out", "queue-out", Collections.emptyMap());
        IOutputQueueResource<String> producer = (IOutputQueueResource<String>) queue.getWrappedResource(producerContext);

        ResourceContext consumerContext = new ResourceContext("test-service", "in", "queue-in", Collections.emptyMap());
        IInputQueueResource<String> consumer = (IInputQueueResource<String>) queue.getWrappedResource(consumerContext);

        assertTrue(producer.send("test-message"));
        Optional<String> received = consumer.receive();

        assertTrue(received.isPresent());
        assertEquals("test-message", received.get());
    }

    @Test
    void testContextualWrapping() {
        ResourceContext producerContext = new ResourceContext("test-service", "out", "queue-out", Collections.emptyMap());
        assertTrue(queue.getWrappedResource(producerContext) instanceof InMemoryBlockingQueue.QueueProducerWrapper);

        ResourceContext consumerContext = new ResourceContext("test-service", "in", "queue-in", Collections.emptyMap());
        assertTrue(queue.getWrappedResource(consumerContext) instanceof InMemoryBlockingQueue.QueueConsumerWrapper);

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

    @Test
    void testServiceSpecificMetrics() {
        ResourceContext producerContext = new ResourceContext("test-service-a", "out", "queue-out", Collections.emptyMap());
        IOutputQueueResource<String> producer = (IOutputQueueResource<String>) queue.getWrappedResource(producerContext);

        ResourceContext consumerContext = new ResourceContext("test-service-b", "in", "queue-in", Collections.emptyMap());
        IInputQueueResource<String> consumer = (IInputQueueResource<String>) queue.getWrappedResource(consumerContext);

        producer.send("message1");
        producer.send("message2");
        consumer.receive();

        Map<String, Number> producerMetrics = ((InMemoryBlockingQueue.QueueProducerWrapper) producer).getMetrics();
        assertEquals(2L, producerMetrics.get("messages_sent"));

        Map<String, Number> consumerMetrics = ((InMemoryBlockingQueue.QueueConsumerWrapper) consumer).getMetrics();
        assertEquals(1L, consumerMetrics.get("messages_consumed"));
    }

    @Test
    void testUsageState() {
        assertEquals(IResource.UsageState.WAITING, queue.getUsageState("queue-in"));
        assertEquals(IResource.UsageState.ACTIVE, queue.getUsageState("queue-out"));

        ResourceContext producerContext = new ResourceContext("test-service", "out", "queue-out", Collections.emptyMap());
        IOutputQueueResource<String> producer = (IOutputQueueResource<String>) queue.getWrappedResource(producerContext);
        producer.send("message");

        assertEquals(IResource.UsageState.ACTIVE, queue.getUsageState("queue-in"));

        for (int i = 0; i < 9; i++) {
            producer.send("message" + i);
        }

        assertEquals(IResource.UsageState.WAITING, queue.getUsageState("queue-out"));
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ResourceContext producerContext = new ResourceContext("producer-service", "out", "queue-out", Collections.emptyMap());
        IOutputQueueResource<String> producer = (IOutputQueueResource<String>) queue.getWrappedResource(producerContext);

        ResourceContext consumerContext = new ResourceContext("consumer-service", "in", "queue-in", Collections.emptyMap());
        IInputQueueResource<String> consumer = (IInputQueueResource<String>) queue.getWrappedResource(consumerContext);

        Runnable producerTask = () -> {
            for (int i = 0; i < 5; i++) {
                producer.send("message" + i);
            }
        };

        Runnable consumerTask = () -> {
            for (int i = 0; i < 5; i++) {
                consumer.receive();
            }
        };

        executor.submit(producerTask);
        executor.submit(consumerTask);

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        Map<String, Number> producerMetrics = ((InMemoryBlockingQueue.QueueProducerWrapper) producer).getMetrics();
        assertEquals(5L, producerMetrics.get("messages_sent"));

        Map<String, Number> consumerMetrics = ((InMemoryBlockingQueue.QueueConsumerWrapper) consumer).getMetrics();
        assertEquals(5L, consumerMetrics.get("messages_consumed"));

        assertEquals(0, queue.getMetrics().get("current_size"));
    }
}