package org.evochora.datapipeline;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.resume.ResumeException;
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
import org.junit.jupiter.api.Timeout;
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
        // Stopping an already stopped service should throw an exception (consistent with pause/resume)
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

    /**
     * Configuration with a service that is defined but left out of the startup sequence, which is
     * what an operator gets when a service is switched on for one run only.
     */
    private Config createConfigWithServiceOutsideStartupSequence() {
        return ConfigFactory.parseString("""
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
                  options { capacity = 50, primaryQueueName = "test-queue" }
                }
                "consumer-idempotency-tracker" {
                  className = "org.evochora.datapipeline.resources.idempotency.InMemoryIdempotencyTracker"
                  options { ttlSeconds = 3600, cleanupThresholdMessages = 100, cleanupIntervalSeconds = 60 }
                }
              }
              services {
                producer {
                  className = "org.evochora.datapipeline.services.DummyProducerService"
                  resources { output = "queue-out:test-queue?window=5" }
                  options { intervalMs = 10, maxMessages = -1 }
                }
                consumer {
                  className = "org.evochora.datapipeline.services.DummyConsumerService"
                  resources {
                    input = "queue-in:test-queue"
                    idempotencyTracker = "tracker:consumer-idempotency-tracker"
                    dlq = "queue-out:consumer-dlq"
                  }
                  options { maxMessages = -1 }
                }
                "extra-producer" {
                  className = "org.evochora.datapipeline.services.DummyProducerService"
                  resources { output = "queue-out:test-queue?window=5" }
                  options { intervalMs = 10, maxMessages = -1 }
                }
              }
            }
        """);
    }

    /**
     * A restart has to give back what it took. Stopping reaches every running service, so starting
     * has to reach them as well and not just the startup sequence, or a service switched on for the
     * current run disappears without anything saying so.
     */
    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = ".*")
    void restartAllStartsTheServicesThatWereRunning_notOnlyTheStartupSequence() {
        ServiceManager sm = new ServiceManager(createConfigWithServiceOutsideStartupSequence());

        sm.startAll();
        sm.startService("extra-producer");
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(IService.State.RUNNING, sm.getServiceStatus("producer").state());
            assertEquals(IService.State.RUNNING, sm.getServiceStatus("consumer").state());
            assertEquals(IService.State.RUNNING, sm.getServiceStatus("extra-producer").state());
        });

        sm.restartAll();

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(IService.State.RUNNING, sm.getServiceStatus("producer").state());
            assertEquals(IService.State.RUNNING, sm.getServiceStatus("consumer").state());
            assertEquals(IService.State.RUNNING, sm.getServiceStatus("extra-producer").state(),
                    "a service started outside the startup sequence must come back too");
        });

        sm.stopAll();
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

    /**
     * A failure that was given a reason on the way out must still be recognised by that reason.
     * <p>
     * Startup failures arrive wrapped in whatever reflection put around them, so the handler looks
     * along the chain of causes. Looking only at its far end finds the technical exception and misses
     * the one that says what went wrong — and the more carefully a failure is explained, the more
     * reliably it would be missed, because explaining it is what gives it a cause of its own.
     */
    @Test
    void findInChain_FindsAnExplainedFailureRatherThanItsTechnicalCause() {
        ResumeException explained = new ResumeException(
                "Checkpoint carries an unreadable RNG state", new java.nio.BufferUnderflowException());
        RuntimeException asThrownByReflection = new RuntimeException("Failed to create instance", explained);

        assertSame(explained, ServiceManager.findInChain(asThrownByReflection, ResumeException.class),
                "the failure that carries the reason");
        assertNull(ServiceManager.findInChain(asThrownByReflection, IllegalStateException.class),
                "a type that does not occur in the chain");
        assertSame(explained, ServiceManager.findInChain(explained, ResumeException.class),
                "a failure that is its own outermost link");
    }

    /**
     * A checkpoint that cannot be read is not a configuration mistake, even when it says so at the
     * bottom of its chain of causes.
     * <p>
     * A restorer reports the value it could not make sense of, which frequently leaves an
     * {@link IllegalArgumentException} as the innermost cause. Deciding on that innermost cause would
     * announce a configuration error and send whoever reads it to the configuration file, while the
     * checkpoint stays unmentioned.
     */
    @Test
    void findInChain_PrefersTheResumeFailureOverItsArgumentCause() {
        ResumeException explained = new ResumeException(
                "Unknown token type: 42", new IllegalArgumentException("No enum constant TokenType.42"));
        RuntimeException asThrownByReflection = new RuntimeException("Failed to create instance", explained);

        assertSame(explained, ServiceManager.findInChain(asThrownByReflection, ResumeException.class),
                "the resume failure, not the argument it names");
        assertNotNull(ServiceManager.findInChain(asThrownByReflection, IllegalArgumentException.class),
                "the argument cause is present too — which is why the order of the checks decides");
    }

    /**
     * A chain of causes that leads back into itself is walked to an end rather than forever.
     * <p>
     * Java rejects a throwable given itself as its cause, but not a ring of two: the second is
     * constructed with the first as its cause, and the first is then given the second. Nothing in
     * this code base builds one, and every startup failure passes through here — including ones
     * raised inside libraries. Running into a ring would not produce a wrong message but a node that
     * neither starts nor stops.
     */
    @Test
    // In its own thread: a walk that does not end would otherwise hang the suite rather than fail it,
    // because the default timeout is only read once the test returns.
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void findInChain_ReturnsOnACauseThatLeadsBackIntoItself() {
        Exception first = new Exception("A");
        Exception second = new Exception("B", first);
        first.initCause(second);

        assertNull(ServiceManager.findInChain(first, ResumeException.class),
                "a type that does not occur — the walk has to end on its own");
        assertSame(first, ServiceManager.findInChain(first, Exception.class),
                "a type that occurs is still found");
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
    @ExpectLog(level = LogLevel.INFO, messagePattern = "Built factory for service 'producer' of type .*")
    @ExpectLog(level = LogLevel.INFO, messagePattern = "Built factory for service 'consumer' of type .*")
    @ExpectLog(level = LogLevel.INFO, messagePattern = "ServiceManager initialized with 3 resources and 2 service factories\\.")
    @ExpectLog(level = LogLevel.INFO, messagePattern = "Auto-start is disabled\\. Services must be started manually via API\\.")
    void testInitializationLogging() {
        new ServiceManager(createTestConfig(false));
    }

    @Test
    void testResourceNamesAreCorrectlyAssigned() {
        ServiceManager serviceManager = new ServiceManager(createTestConfig(false));
        serviceManager.startAll();

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            ServiceStatus producerStatus = serviceManager.getServiceStatus("producer");
            assertFalse(producerStatus.resourceBindings().isEmpty());
            IResource producerResource = producerStatus.resourceBindings().get(0).resource();
            assertEquals("test-queue", producerResource.getResourceName());

            ServiceStatus consumerStatus = serviceManager.getServiceStatus("consumer");
            assertFalse(consumerStatus.resourceBindings().isEmpty());
            IResource consumerResource = consumerStatus.resourceBindings().get(0).resource();
            assertEquals("test-queue", consumerResource.getResourceName());
        });
    }
}