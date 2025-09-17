package org.evochora.datapipeline.core;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.channels.IMonitorableChannel;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.State;
import org.evochora.datapipeline.channels.InMemoryChannel;
import org.evochora.datapipeline.services.testing.DummyConsumerService;
import org.evochora.datapipeline.services.testing.DummyProducerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class ServiceManagerTest {

    private Config config;

    @BeforeEach
    void setUp() {
        config = ConfigFactory.parseResources("pipeline.hocon");
    }

    @Test
    void pipelineShouldBeConstructedCorrectly() {
        ServiceManager serviceManager = new ServiceManager(config);

        assertEquals(1, serviceManager.getChannels().size(), "Should have 1 channel");
        assertTrue(serviceManager.getChannels().get("test-stream") instanceof InMemoryChannel, "Channel should be InMemoryChannel");

        assertEquals(2, serviceManager.getServices().size(), "Should have 2 services");
        assertTrue(serviceManager.getServices().get("test-producer") instanceof DummyProducerService);
        assertTrue(serviceManager.getServices().get("test-consumer") instanceof DummyConsumerService);
    }

    @Test
    void pipelineLifecycleShouldExecuteCorrectly() throws InterruptedException {
        ServiceManager serviceManager = new ServiceManager(config);
        serviceManager.startAll();

        // --- Graceful Shutdown ---
        IMonitorableChannel channel = (IMonitorableChannel) serviceManager.getChannels().get("test-stream");

        // 1. Wait for the producer to finish its work.
        Thread producerThread = serviceManager.getServiceThreads().get("test-producer");
        producerThread.join(); // Wait for the producer thread to terminate

        // 2. Wait for the consumer to drain the queue.
        while (channel.getQueueSize() > 0) {
            Thread.sleep(50);
        }

        // 3. Stop the consumer service by interrupting its thread.
        Thread consumerThread = serviceManager.getServiceThreads().get("test-consumer");
        consumerThread.interrupt();
        consumerThread.join();

        // 4. Assert the final count.
        DummyConsumerService consumer = (DummyConsumerService) serviceManager.getServices().get("test-consumer");
        assertEquals(100, consumer.getReceivedMessageCount(), "Consumer should have received all 100 messages");
    }

    @Test
    void pauseAndResumeShouldControlFlow() throws InterruptedException {
        ServiceManager serviceManager = new ServiceManager(config);
        DummyConsumerService consumer = (DummyConsumerService) serviceManager.getServices().get("test-consumer");
        IService producer = serviceManager.getServices().get("test-producer");

        // Add a sleep to the producer to make it pausable
        DummyProducerService dummyProducer = (DummyProducerService) producer;
        dummyProducer.setExecutionDelay(10);

        serviceManager.startAll();
        Thread.sleep(100); // Give producer time to send a few messages

        serviceManager.pauseService("test-producer");
        assertEquals(State.PAUSED, producer.getServiceStatus().state(), "Producer state should be PAUSED");

        int countWhenPaused = consumer.getReceivedMessageCount();
        assertTrue(countWhenPaused > 0, "Producer should have sent some messages before pausing");
        assertTrue(countWhenPaused < 100, "Producer should not have sent all messages before pausing");

        Thread.sleep(200);
        assertEquals(countWhenPaused, consumer.getReceivedMessageCount(), "Message count should not change while paused");

        serviceManager.resumeService("test-producer");
        assertEquals(State.RUNNING, producer.getServiceStatus().state(), "Producer state should be RUNNING after resume");

        // --- Graceful Shutdown ---
        Thread producerThread = serviceManager.getServiceThreads().get("test-producer");
        producerThread.join();

        IMonitorableChannel channel = (IMonitorableChannel) serviceManager.getChannels().get("test-stream");
        while (channel.getQueueSize() > 0) {
            Thread.sleep(50);
        }

        Thread consumerThread = serviceManager.getServiceThreads().get("test-consumer");
        consumerThread.interrupt();
        consumerThread.join();

        assertEquals(100, consumer.getReceivedMessageCount(), "All messages should be received after resuming");
    }
}
