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
        // Create test configuration inline - no external dependencies
        String testConfig = """
            pipeline {
                channels {
                    test-stream {
                        className = "org.evochora.datapipeline.channels.InMemoryChannel"
                        options {
                            capacity = 1000
                        }
                    }
                }
                services {
                    test-producer {
                        className = "org.evochora.datapipeline.services.testing.DummyProducerService"
                        inputs = []
                        outputs = ["test-stream"]
                        options {
                            messageCount = 100
                        }
                    }
                    test-consumer {
                        className = "org.evochora.datapipeline.services.testing.DummyConsumerService"
                        inputs = ["test-stream"]
                        outputs = []
                    }
                }
            }
            """;
        config = ConfigFactory.parseString(testConfig);
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

        // Wait for services to complete their work
        DummyProducerService producer = (DummyProducerService) serviceManager.getServices().get("test-producer");
        DummyConsumerService consumer = (DummyConsumerService) serviceManager.getServices().get("test-consumer");
        
        // Wait for producer to finish (it sends 100 messages then stops)
        waitForCondition(() -> producer.getServiceStatus().state() == State.STOPPED, 5000, "Producer to finish");
        
        // Wait for consumer to process all messages
        waitForCondition(() -> consumer.getReceivedMessageCount() >= 100, 5000, "Consumer to receive all messages");
        
        // Stop all services
        serviceManager.stopAll();
        
        // Assert the final count
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

        // Wait for producer to finish
        waitForCondition(() -> producer.getServiceStatus().state() == State.STOPPED, 5000, "Producer to finish");
        
        // Wait for consumer to process all messages
        waitForCondition(() -> consumer.getReceivedMessageCount() >= 100, 5000, "Consumer to receive all messages");
        
        // Stop all services
        serviceManager.stopAll();

        assertEquals(100, consumer.getReceivedMessageCount(), "All messages should be received after resuming");
    }
    
    private void waitForCondition(java.util.function.Supplier<Boolean> condition, long timeoutMs, String description) {
        long startTime = System.currentTimeMillis();
        while (!condition.get() && (System.currentTimeMillis() - startTime) < timeoutMs) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for: " + description, e);
            }
        }
        if (!condition.get()) {
            throw new AssertionError("Timeout waiting for: " + description);
        }
    }
}
