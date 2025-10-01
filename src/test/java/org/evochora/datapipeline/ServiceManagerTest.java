package org.evochora.datapipeline;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.services.IService;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.FailOnLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*")
public class ServiceManagerTest {

    private Config createTestConfig(boolean longRunning) {
        String maxMessages = longRunning ? "-1" : "10";
        return ConfigFactory.parseString(String.format("""
            pipeline {
              autoStart = false
              startupSequence = ["consumer", "producer"]
              resources {
                "test-queue" {
                  className = "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue"
                  options { capacity = 100 }
                }
                "consumer-dlq" {
                  className = "org.evochora.datapipeline.resources.queues.InMemoryDeadLetterQueue"
                  options {
                    capacity = 50
                    primaryQueueName = "test-queue"
                  }
                }
                "consumer-idempotency-tracker" {
                  className = "org.evochora.datapipeline.resources.idempotency.InMemoryIdempotencyTracker"
                  options {
                    ttlSeconds = 3600
                    cleanupThresholdMessages = 100
                    cleanupIntervalSeconds = 60
                  }
                }
              }
              services {
                producer {
                  className = "org.evochora.datapipeline.services.DummyProducerService"
                  resources { output = "queue-out:test-queue?window=5" }
                  options { intervalMs = 10, maxMessages = %s }
                }
                consumer {
                  className = "org.evochora.datapipeline.services.DummyConsumerService"
                  resources {
                    input = "queue-in:test-queue"
                    idempotencyTracker = "tracker:consumer-idempotency-tracker"
                    dlq = "queue-out:consumer-dlq"
                  }
                  options { maxMessages = %s }
                }
              }
            }
        """, maxMessages, maxMessages));
    }

    @Test
    void testInitialization() {
        ServiceManager serviceManager = new ServiceManager(createTestConfig(false));
        assertNotNull(serviceManager);
        assertEquals(2, serviceManager.getAllServiceStatus().size());
        assertEquals(3, serviceManager.getMetrics().get("resources_total")); // queue + dlq + idempotency tracker
        assertTrue(serviceManager.getAllServiceStatus().containsKey("producer"));
        assertTrue(serviceManager.getAllServiceStatus().containsKey("consumer"));
    }

    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = ".* is not running or paused. Stop command ignored")
    void testLifecycleMethods() {
        ServiceManager sm = new ServiceManager(createTestConfig(true));

        sm.startAll();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(IService.State.RUNNING, sm.getServiceStatus("producer").state());
            assertEquals(IService.State.RUNNING, sm.getServiceStatus("consumer").state());
            assertEquals(2, (long) sm.getMetrics().get("services_running"));
        });
        // Assert that starting again throws an exception
        assertThrows(IllegalStateException.class, () -> sm.startService("producer"));

        sm.pauseAll();
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(IService.State.PAUSED, sm.getServiceStatus("producer").state());
            assertEquals(IService.State.PAUSED, sm.getServiceStatus("consumer").state());
            assertEquals(2, (long) sm.getMetrics().get("services_paused"));
        });
        assertThrows(IllegalStateException.class, () -> sm.pauseService("producer"));


        sm.resumeAll();
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
             assertEquals(IService.State.RUNNING, sm.getServiceStatus("producer").state());
             assertEquals(IService.State.RUNNING, sm.getServiceStatus("consumer").state());
        });
        assertThrows(IllegalStateException.class, () -> sm.resumeService("producer"));

        sm.stopAll();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(IService.State.STOPPED, sm.getServiceStatus("producer").state());
            assertEquals(IService.State.STOPPED, sm.getServiceStatus("consumer").state());
            assertEquals(2, (long) sm.getMetrics().get("services_stopped"));
        });
        assertThrows(IllegalStateException.class, () -> sm.stopService("producer"));
    }

    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = ".*")
    void testSingleServiceLifecycle() {
        ServiceManager sm = new ServiceManager(createTestConfig(true));
        sm.startService("producer");
        await().atMost(1, TimeUnit.SECONDS).until(() -> sm.getServiceStatus("producer").state() == IService.State.RUNNING);
        assertEquals(IService.State.STOPPED, sm.getServiceStatus("consumer").state());

        sm.stopService("producer");
        await().atMost(1, TimeUnit.SECONDS).until(() -> sm.getServiceStatus("producer").state() == IService.State.STOPPED);

        // This should now work because restartService is more resilient
        sm.restartService("consumer");
        await().atMost(1, TimeUnit.SECONDS).until(() -> sm.getServiceStatus("consumer").state() == IService.State.RUNNING);
        sm.stopService("consumer");
        await().atMost(1, TimeUnit.SECONDS).until(() -> sm.getServiceStatus("consumer").state() == IService.State.STOPPED);
    }

    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = ".*")
    void testStatusAndMetrics() {
        ServiceManager sm = new ServiceManager(createTestConfig(true));
        sm.startAll();
        await().atMost(1, TimeUnit.SECONDS).until(() -> (long) sm.getMetrics().get("services_running") == 2);

        ServiceStatus producerStatus = sm.getServiceStatus("producer");
        assertEquals(IService.State.RUNNING, producerStatus.state());
        assertFalse(producerStatus.resourceBindings().isEmpty());

        sm.stopAll();
        await().atMost(1, TimeUnit.SECONDS).until(() -> (long) sm.getMetrics().get("services_stopped") == 2);
        assertTrue(sm.isHealthy());
    }

    @Test
    @AllowLog(level = LogLevel.ERROR, messagePattern = "Failed to instantiate service '.*': .* Skipping this service\\.")
    void testErrorHandling() {
        Config badResourceConfig = ConfigFactory.parseString("""
             pipeline.services.test {
               className = "org.evochora.datapipeline.services.DummyConsumerService"
               resources.input = "queue-in:non-existent-queue"
             }
        """);
        // Graceful error handling: ServiceManager initializes but service 'test' is skipped
        ServiceManager sm1 = new ServiceManager(badResourceConfig);
        assertThrows(IllegalArgumentException.class, () -> sm1.getServiceStatus("test"));

        Config badClassConfig = ConfigFactory.parseString("""
            pipeline.services.test {
              className = "com.example.NonExistent"
            }
        """);
        // Graceful error handling: ServiceManager initializes but service 'test' is skipped
        ServiceManager sm2 = new ServiceManager(badClassConfig);
        assertThrows(IllegalArgumentException.class, () -> sm2.getServiceStatus("test"));
    }

    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = ".*")
    void testConcurrentLifecycleCalls() throws InterruptedException {
        ServiceManager sm = new ServiceManager(createTestConfig(true));
        ExecutorService executor = Executors.newFixedThreadPool(8);

        for (int i = 0; i < 5; i++) {
            executor.submit(sm::startAll);
            executor.submit(sm::stopAll);
            executor.submit(sm::pauseAll);
            executor.submit(sm::resumeAll);
            executor.submit(() -> sm.startService("producer"));
            executor.submit(() -> sm.stopService("consumer"));
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Tasks should complete without deadlock.");

        // Make sure everything is stopped at the end, tolerating that some may already be stopped.
        sm.stopAll();

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(IService.State.STOPPED, sm.getServiceStatus("producer").state());
            assertEquals(IService.State.STOPPED, sm.getServiceStatus("consumer").state());
        });
    }

    @Test
    @FailOnLog(level = LogLevel.INFO)
    @ExpectLog(level = LogLevel.INFO, messagePattern = "Initializing ServiceManager\\.\\.\\.")
    @ExpectLog(level = LogLevel.INFO, messagePattern = "Instantiated resource 'test-queue' of type .*")
    @ExpectLog(level = LogLevel.INFO, messagePattern = "Instantiated service 'producer' of type .*")
    @ExpectLog(level = LogLevel.INFO, messagePattern = "Instantiated service 'consumer' of type .*")
    @ExpectLog(level = LogLevel.INFO, messagePattern = "ServiceManager initialized with 3 resources and 2 services\\.")
    @ExpectLog(level = LogLevel.INFO, messagePattern = "Auto-start is disabled\\. Services must be started manually via API\\.")
    void testInitializationLogging() {
        new ServiceManager(createTestConfig(false));
    }

    @Test
    void testResourceNamesAreCorrectlyAssigned() {
        ServiceManager serviceManager = new ServiceManager(createTestConfig(false));
        ServiceStatus producerStatus = serviceManager.getServiceStatus("producer");
        assertFalse(producerStatus.resourceBindings().isEmpty());
        IResource producerResource = producerStatus.resourceBindings().get(0).resource();
        assertEquals("test-queue", producerResource.getResourceName());

        ServiceStatus consumerStatus = serviceManager.getServiceStatus("consumer");
        assertFalse(consumerStatus.resourceBindings().isEmpty());
        IResource consumerResource = consumerStatus.resourceBindings().get(0).resource();
        assertEquals("test-queue", consumerResource.getResourceName());
    }
}