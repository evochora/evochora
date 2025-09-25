package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.PipelineContracts.DummyMessage;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
public class EndToEndServiceTest {

    @Test
    void testProducerToConsumerFlow() throws InterruptedException {
        Config producerConfig = ConfigFactory.parseString("maxMessages=20, intervalMs=10, messagePrefix=\"E2E\"");
        Config consumerConfig = ConfigFactory.parseString("maxMessages=20, processingDelayMs=5");

        InMemoryBlockingQueue<DummyMessage> queue = new InMemoryBlockingQueue<>(ConfigFactory.parseString("capacity=10"));

        Map<String, List<IResource>> producerResources = new HashMap<>();
        producerResources.put("output", Collections.singletonList(queue));
        DummyProducerService producer = new DummyProducerService(producerConfig, producerResources);

        Map<String, List<IResource>> consumerResources = new HashMap<>();
        consumerResources.put("input", Collections.singletonList(queue));
        DummyConsumerService consumer = new DummyConsumerService(consumerConfig, consumerResources);

        consumer.start();
        producer.start();

        long deadline = System.currentTimeMillis() + 2000;
        while ((producer.getCurrentState() != IService.State.STOPPED || consumer.getCurrentState() != IService.State.STOPPED) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(IService.State.STOPPED, producer.getCurrentState(), "Producer should have stopped on its own.");
        assertEquals(IService.State.STOPPED, consumer.getCurrentState(), "Consumer should have stopped on its own.");

        Map<String, Number> producerMetrics = producer.getMetrics();
        Map<String, Number> consumerMetrics = consumer.getMetrics();

        assertEquals(20L, producerMetrics.get("messages_sent").longValue());
        assertEquals(20L, consumerMetrics.get("messages_received").longValue());
    }

    @Test
    void testPauseAndResume() throws InterruptedException {
        Config producerConfig = ConfigFactory.parseString("intervalMs=10, messagePrefix=\"PauseTest\"");
        Config consumerConfig = ConfigFactory.empty();

        InMemoryBlockingQueue<DummyMessage> queue = new InMemoryBlockingQueue<>(ConfigFactory.parseString("capacity=100"));

        Map<String, List<IResource>> producerResources = new HashMap<>();
        producerResources.put("output", Collections.singletonList(queue));
        DummyProducerService producer = new DummyProducerService(producerConfig, producerResources);

        Map<String, List<IResource>> consumerResources = new HashMap<>();
        consumerResources.put("input", Collections.singletonList(queue));
        DummyConsumerService consumer = new DummyConsumerService(consumerConfig, consumerResources);

        consumer.start();
        producer.start();

        Thread.sleep(50); // Let some messages flow

        producer.pause();
        consumer.pause();

        Thread.sleep(50);

        long messagesSentWhilePaused = producer.getMetrics().get("messages_sent").longValue();
        long messagesReceivedWhilePaused = consumer.getMetrics().get("messages_received").longValue();

        Thread.sleep(100);

        assertEquals(messagesSentWhilePaused, producer.getMetrics().get("messages_sent").longValue());
        assertEquals(messagesReceivedWhilePaused, consumer.getMetrics().get("messages_received").longValue());

        producer.resume();
        consumer.resume();

        Thread.sleep(100);

        assertTrue(producer.getMetrics().get("messages_sent").longValue() > messagesSentWhilePaused);
        assertTrue(consumer.getMetrics().get("messages_received").longValue() > messagesReceivedWhilePaused);

        producer.stop();
        consumer.stop();
    }
}