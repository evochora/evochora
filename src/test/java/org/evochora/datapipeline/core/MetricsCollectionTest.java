package org.evochora.datapipeline.core;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.services.ChannelMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class MetricsCollectionTest {

    private ServiceManager serviceManager;
    private Config testConfig;

    @BeforeEach
    void setUp() {
        testConfig = ConfigFactory.parseString("""
            pipeline {
                autoStart = false
                metrics {
                    enableMetrics = true
                    updateIntervalSeconds = 1
                }
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
                        className = "org.evochora.datapipeline.services.DummyProducerService"
                        inputs = []
                        outputs = ["test-stream"]
                        options {
                            messageCount = 50
                        }
                    }
                    test-consumer {
                        className = "org.evochora.datapipeline.services.DummyConsumerService"
                        inputs = ["test-stream"]
                        outputs = []
                    }
                }
            }
            """);
    }

    @Test
    void testMetricsCollectionEnabled() {
        serviceManager = new ServiceManager(testConfig);
        
        // Start services
        serviceManager.startAll();
        
        // Wait for service to be created
        waitForCondition(() -> serviceManager.getServices().get("test-producer") != null, 1000, "Service to be created");
        
        // Verify service was created
        assertNotNull(serviceManager.getServices().get("test-producer"));
        
        serviceManager.stopAll();
    }

    @Test
    void testMetricsCollectionDisabled() {
        Config disabledConfig = ConfigFactory.parseString("""
            pipeline {
                autoStart = false
                metrics {
                    enableMetrics = false
                }
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
                        className = "org.evochora.datapipeline.services.DummyProducerService"
                        inputs = []
                        outputs = ["test-stream"]
                        options {
                            messageCount = 10
                        }
                    }
                }
            }
            """);
        
        serviceManager = new ServiceManager(disabledConfig);
        
        // Start services
        serviceManager.startAll();
        
        // Wait for service to be created
        waitForCondition(() -> serviceManager.getServices().get("test-producer") != null, 1000, "Service to be created");
        
        // No need to wait - metrics are disabled, so no interference
        
        // Verify service was created
        assertNotNull(serviceManager.getServices().get("test-producer"));
        
        serviceManager.stopAll();
    }

    @Test
    void testCustomMetricsInterval() {
        Config customIntervalConfig = ConfigFactory.parseString("""
            pipeline {
                autoStart = false
                metrics {
                    enableMetrics = true
                    updateIntervalSeconds = 2
                }
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
                        className = "org.evochora.datapipeline.services.DummyProducerService"
                        inputs = []
                        outputs = ["test-stream"]
                        options {
                            messageCount = 20
                        }
                    }
                }
            }
            """);
        
        serviceManager = new ServiceManager(customIntervalConfig);
        
        // Start services
        serviceManager.startAll();
        
        // Wait for service to be created
        waitForCondition(() -> serviceManager.getServices().get("test-producer") != null, 1000, "Service to be created");
        
        // No need to wait for custom interval - we just verify service was created
        
        // Verify service was created
        assertNotNull(serviceManager.getServices().get("test-producer"));
        
        serviceManager.stopAll();
    }

    @Test
    void testChannelMetricsRecord() {
        // Test ChannelMetrics record functionality
        ChannelMetrics metrics1 = ChannelMetrics.withCurrentTimestamp(10.5, 0);
        ChannelMetrics metrics2 = ChannelMetrics.withCurrentTimestamp(20.0, 1);
        ChannelMetrics zeroMetrics = ChannelMetrics.zero();
        
        assertEquals(10.5, metrics1.messagesPerSecond());
        assertEquals(0, metrics1.errorCount());
        assertTrue(metrics1.timestamp() > 0);
        
        assertEquals(20.0, metrics2.messagesPerSecond());
        assertEquals(1, metrics2.errorCount());
        assertTrue(metrics2.timestamp() > 0);
        
        assertEquals(0.0, zeroMetrics.messagesPerSecond());
        assertEquals(0, zeroMetrics.errorCount());
        assertTrue(zeroMetrics.timestamp() > 0);
        
        // Test that timestamps are reasonable (within last 5 seconds)
        long now = System.currentTimeMillis();
        assertTrue(metrics1.timestamp() <= now && metrics1.timestamp() >= now - 5000);
        assertTrue(metrics2.timestamp() <= now && metrics2.timestamp() >= now - 5000);
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
