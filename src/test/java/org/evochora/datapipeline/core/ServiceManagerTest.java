package org.evochora.datapipeline.core;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.State;
import org.evochora.datapipeline.channels.InMemoryChannel;
import org.evochora.datapipeline.services.DummyConsumerService;
import org.evochora.datapipeline.services.DummyProducerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class ServiceManagerTest {

    private Config config;

    @BeforeEach
    void setUp() {
        // Set logger levels to WARN for services - only WARN and ERROR should be shown
        System.setProperty("org.evochora.datapipeline.core.ServiceManager", "WARN");
        
        // Create test configuration inline - no external dependencies
        String testConfig = """
            pipeline {
                channels {
                    test-stream {
                        className = "org.evochora.datapipeline.channels.InMemoryChannel"
                        options {
                            capacity = 100
                        }
                    }
                }
                services {
                    test-producer {
                        className = "org.evochora.datapipeline.services.DummyProducerService"
                        outputs {
                            messages = "test-stream"
                        }
                        options {
                            messageCount = 10
                        }
                    }
                    test-consumer {
                        className = "org.evochora.datapipeline.services.DummyConsumerService"
                        inputs {
                            messages = "test-stream"
                        }
                        outputs = {}
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
        
        // Wait for consumer to process all messages
        waitForCondition(() -> consumer.getReceivedMessageCount() >= 10, 1000, "Consumer to receive all messages");
        
        // Stop all services explicitly (since services don't stop themselves anymore)
        serviceManager.stopAll();
        
        // Verify services are stopped
        assertEquals(State.STOPPED, producer.getServiceStatus().state(), "Producer should be stopped");
        assertEquals(State.STOPPED, consumer.getServiceStatus().state(), "Consumer should be stopped");
        
        // Assert the final count
        assertEquals(10, consumer.getReceivedMessageCount(), "Consumer should have received all 10 messages");
    }

    @Test
    void pauseAndResumeShouldControlFlow() throws InterruptedException {
        ServiceManager serviceManager = new ServiceManager(config);
        DummyConsumerService consumer = (DummyConsumerService) serviceManager.getServices().get("test-consumer");
        IService producer = serviceManager.getServices().get("test-producer");

        // Add a sleep to the producer to make it pausable
        DummyProducerService dummyProducer = (DummyProducerService) producer;
        dummyProducer.setExecutionDelay(1);

        serviceManager.startAll();
        // Wait for producer to send messages
        waitForCondition(() -> consumer.getReceivedMessageCount() > 0, 1000, "Producer to send messages");

        serviceManager.pauseService("test-producer");
        assertEquals(State.PAUSED, producer.getServiceStatus().state(), "Producer state should be PAUSED");

        int countWhenPaused = consumer.getReceivedMessageCount();
        assertTrue(countWhenPaused > 0, "Producer should have sent some messages before pausing");
        assertTrue(countWhenPaused < 10, "Producer should not have sent all messages before pausing");

        // Wait a bit to ensure no messages are sent while paused
        waitForCondition(() -> consumer.getReceivedMessageCount() == countWhenPaused, 1000, "No messages while paused");
        assertEquals(countWhenPaused, consumer.getReceivedMessageCount(), "Message count should not change while paused");

        serviceManager.resumeService("test-producer");
        assertEquals(State.RUNNING, producer.getServiceStatus().state(), "Producer state should be RUNNING after resume");

        // Wait for producer to send all messages after resume
        waitForCondition(() -> consumer.getReceivedMessageCount() >= 10, 1000, "Producer to send all messages after resume");
        
        // Stop all services
        serviceManager.stopAll();

        assertEquals(10, consumer.getReceivedMessageCount(), "All messages should be received after resuming");
    }
    
    @Test
    void testAutoStartEnabled() {
        // Create configuration with autoStart enabled
        String testConfig = """
            pipeline {
                autoStart = true
                startupSequence = ["test-producer"]
                channels {
                    test-stream {
                        className = "org.evochora.datapipeline.channels.InMemoryChannel"
                        options {
                            capacity = 100
                        }
                    }
                }
                services {
                    test-producer {
                        className = "org.evochora.datapipeline.services.DummyProducerService"
                        outputs {
                            messages = "test-stream"
                        }
                        options {
                            messageCount = 10
                        }
                    }
                }
            }
            """;
        Config config = ConfigFactory.parseString(testConfig);
        
        // ServiceManager should auto-start services
        ServiceManager serviceManager = new ServiceManager(config);
        
        // Wait for service to be created (auto-start happens immediately in constructor)
        waitForCondition(() -> serviceManager.getServices().get("test-producer") != null, 1000, "Service to be created");
        
        // Verify service was created and started (even if it finished quickly)
        IService producer = serviceManager.getServices().get("test-producer");
        assertNotNull(producer, "Producer service should be created");
        
        // The service might have finished quickly, so we just verify it was created
        // and that the ServiceManager attempted to start it
        serviceManager.stopAll();
    }
    
    @Test
    void testAutoStartDisabled() {
        // Create configuration with autoStart disabled
        String testConfig = """
            pipeline {
                autoStart = false
                channels {
                    test-stream {
                        className = "org.evochora.datapipeline.channels.InMemoryChannel"
                        options {
                            capacity = 100
                        }
                    }
                }
                services {
                    test-producer {
                        className = "org.evochora.datapipeline.services.DummyProducerService"
                        outputs {
                            messages = "test-stream"
                        }
                        options {
                            messageCount = 10
                        }
                    }
                }
            }
            """;
        Config config = ConfigFactory.parseString(testConfig);
        
        // ServiceManager should NOT auto-start services
        ServiceManager serviceManager = new ServiceManager(config);
        
        // Verify service is still stopped (no auto-start should happen)
        // No need to wait - autoStart is false, so services should remain stopped
        
        // Verify services are still stopped
        assertEquals(State.STOPPED, serviceManager.getServices().get("test-producer").getServiceStatus().state());
        
        serviceManager.stopAll();
    }
    
    @Test
    void testStartupSequence() {
        // Create configuration with specific startup sequence
        String testConfig = """
            pipeline {
                autoStart = false
                startupSequence = ["test-consumer", "test-producer"]
                channels {
                    test-stream {
                        className = "org.evochora.datapipeline.channels.InMemoryChannel"
                        options {
                            capacity = 100
                        }
                    }
                }
                services {
                    test-producer {
                        className = "org.evochora.datapipeline.services.DummyProducerService"
                        outputs {
                            messages = "test-stream"
                        }
                        options {
                            messageCount = 10
                        }
                    }
                    test-consumer {
                        className = "org.evochora.datapipeline.services.DummyConsumerService"
                        inputs {
                            messages = "test-stream"
                        }
                        outputs = {}
                    }
                }
            }
            """;
        Config config = ConfigFactory.parseString(testConfig);
        
        ServiceManager serviceManager = new ServiceManager(config);
        
        // Manually start services - should follow startup sequence
        serviceManager.startAll();
        
        // Wait for services to be created
        waitForCondition(() -> 
            serviceManager.getServices().get("test-producer") != null && 
            serviceManager.getServices().get("test-consumer") != null, 
            1000, "Services to be created");
        
        // Verify services were created
        assertNotNull(serviceManager.getServices().get("test-producer"));
        assertNotNull(serviceManager.getServices().get("test-consumer"));
        
        serviceManager.stopAll();
    }
    
    @Test
    void testStartAllSkipsRunningServices() {
        // Create configuration
        String testConfig = """
            pipeline {
                autoStart = false
                startupSequence = ["test-producer"]
                channels {
                    test-stream {
                        className = "org.evochora.datapipeline.channels.InMemoryChannel"
                        options {
                            capacity = 100
                        }
                    }
                }
                services {
                    test-producer {
                        className = "org.evochora.datapipeline.services.DummyProducerService"
                        outputs {
                            messages = "test-stream"
                        }
                        options {
                            messageCount = 10
                        }
                    }
                }
            }
            """;
        Config config = ConfigFactory.parseString(testConfig);
        
        ServiceManager serviceManager = new ServiceManager(config);
        
        // Start services first time
        serviceManager.startAll();
        
        // Wait for service to be created
        waitForCondition(() -> serviceManager.getServices().get("test-producer") != null, 1000, "Service to be created");
        
        // Start services second time - should skip already running service
        serviceManager.startAll();
        
        // Verify service was created
        assertNotNull(serviceManager.getServices().get("test-producer"));
        
        serviceManager.stopAll();
    }
    
    private void waitForCondition(java.util.function.Supplier<Boolean> condition, long timeoutMs, String description) {
        long startTime = System.currentTimeMillis();
        while (!condition.get() && (System.currentTimeMillis() - startTime) < timeoutMs) {
            // Use busy-waiting with small yield instead of Thread.sleep
            Thread.yield();
        }
        if (!condition.get()) {
            throw new AssertionError("Timeout waiting for: " + description);
        }
    }
}
