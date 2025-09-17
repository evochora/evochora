package org.evochora.datapipeline.core;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.State;
import org.evochora.datapipeline.channels.InMemoryChannel;
import org.evochora.datapipeline.services.testing.DummyConsumerService;
import org.evochora.datapipeline.services.testing.DummyProducerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class ServiceManagerTest {

    private Config config;

    @BeforeEach
    void setUp() {
        // Load the test configuration from src/test/resources
        config = ConfigFactory.parseResources("pipeline.hocon");
    }

    @Test
    void pipelineShouldBeConstructedCorrectly() {
        ServiceManager serviceManager = new ServiceManager(config);

        // Assert that one channel and two services have been created
        assertEquals(1, serviceManager.getChannels().size(), "Should have 1 channel");
        assertTrue(serviceManager.getChannels().get("test-stream") instanceof InMemoryChannel, "Channel should be InMemoryChannel");

        assertEquals(2, serviceManager.getServices().size(), "Should have 2 services");
        IService producer = serviceManager.getServices().get("test-producer");
        IService consumer = serviceManager.getServices().get("test-consumer");

        assertNotNull(producer, "Producer should not be null");
        assertTrue(producer instanceof DummyProducerService, "Service should be DummyProducerService");

        assertNotNull(consumer, "Consumer should not be null");
        assertTrue(consumer instanceof DummyConsumerService, "Service should be DummyConsumerService");
    }

    @Test
    void pipelineLifecycleShouldExecuteCorrectly() throws InterruptedException {
        ServiceManager serviceManager = new ServiceManager(config);
        serviceManager.startAll();

        // Wait a reasonable amount of time for the producer to send all its messages.
        // The producer sends 100 messages, let's give it a couple of seconds.
        Thread.sleep(2000);

        serviceManager.stopAll();

        DummyConsumerService consumer = (DummyConsumerService) serviceManager.getServices().get("test-consumer");
        assertEquals(100, consumer.getReceivedMessageCount(), "Consumer should have received 100 messages");
    }

    @Test
    void pauseAndResumeShouldControlFlow() throws InterruptedException {
        ServiceManager serviceManager = new ServiceManager(config);
        DummyConsumerService consumer = (DummyConsumerService) serviceManager.getServices().get("test-consumer");
        DummyProducerService producer = (DummyProducerService) serviceManager.getServices().get("test-producer");

        CountDownLatch producerPausedLatch = new CountDownLatch(1);
        producer.setLatch(producerPausedLatch);

        serviceManager.startAll();

        // Pause the producer and wait for it to acknowledge the pause
        serviceManager.pauseService("test-producer");
        assertTrue(producerPausedLatch.await(2, TimeUnit.SECONDS), "Producer should have acknowledged pause");
        assertEquals(State.PAUSED, producer.getServiceStatus().state(), "Producer should be in PAUSED state");

        // Capture the message count while paused
        int messageCountWhenPaused = consumer.getReceivedMessageCount();

        // Resume the producer
        serviceManager.resumeService("test-producer");
        assertEquals(State.RUNNING, producer.getServiceStatus().state(), "Producer should be in RUNNING state");

        // Wait for the pipeline to complete
        Thread.sleep(2000);

        serviceManager.stopAll();
        assertTrue(consumer.getReceivedMessageCount() > messageCountWhenPaused, "Consumer should have received more messages after resume");
        assertEquals(100, consumer.getReceivedMessageCount(), "Consumer should have received all 100 messages after resume");
    }
}
