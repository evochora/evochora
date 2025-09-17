package org.evochora.datapipeline.core;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
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

        // The producer sends 100 messages, sleeping 10ms after each.
        // It will take at least 100 * 10ms = 1000ms.
        // We wait a bit longer to be safe.
        Thread.sleep(1500);

        serviceManager.stopAll();

        DummyConsumerService consumer = (DummyConsumerService) serviceManager.getServices().get("test-consumer");
        assertEquals(100, consumer.getReceivedMessageCount(), "Consumer should have received all 100 messages");
    }

    @Test
    void pauseAndResumeShouldControlFlow() throws InterruptedException {
        ServiceManager serviceManager = new ServiceManager(config);
        DummyConsumerService consumer = (DummyConsumerService) serviceManager.getServices().get("test-consumer");
        IService producer = serviceManager.getServices().get("test-producer");

        serviceManager.startAll();
        Thread.sleep(100); // Give producer time to send a few messages (e.g., ~10)

        serviceManager.pauseService("test-producer");
        assertEquals(State.PAUSED, producer.getServiceStatus().state(), "Producer state should be PAUSED");

        int countWhenPaused = consumer.getReceivedMessageCount();
        assertTrue(countWhenPaused > 0, "Producer should have sent some messages before pausing");
        assertTrue(countWhenPaused < 100, "Producer should not have sent all messages before pausing");

        // Wait a bit to make sure no more messages are coming through while paused
        Thread.sleep(200);
        assertEquals(countWhenPaused, consumer.getReceivedMessageCount(), "Message count should not change while paused");

        serviceManager.resumeService("test-producer");
        assertEquals(State.RUNNING, producer.getServiceStatus().state(), "Producer state should be RUNNING after resume");

        // Wait for the rest of the messages to be sent.
        // The producer was paused after ~100ms, so it sent ~10 messages.
        // It needs to send ~90 more, which will take ~900ms. We wait for 1.5s to be safe.
        Thread.sleep(1500);

        serviceManager.stopAll();
        assertEquals(100, consumer.getReceivedMessageCount(), "All messages should be received after resuming");
    }
}
