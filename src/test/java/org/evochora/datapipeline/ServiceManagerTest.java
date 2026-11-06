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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@ExtendWith(LogWatchExtension.class)
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

    /** A service whose construction fails for a reason the manager does not recognise. */
    public static class ConstructionFailingService extends org.evochora.datapipeline.services.AbstractService {
        public ConstructionFailingService(String name, Config options, java.util.Map<String, java.util.List<IResource>> resources) {
            super(name, options, resources);
            throw new IllegalStateException("test-induced defect");
        }

        @Override
        protected void run() {
        }
    }

    /** A service that starts its thread and then fails, as a defect after the start would. */
    public static class StartAnnouncementFailingService extends org.evochora.datapipeline.services.AbstractService {
        public StartAnnouncementFailingService(String name, Config options, java.util.Map<String, java.util.List<IResource>> resources) {
            super(name, options, resources);
        }

        @Override
        protected void run() {
        }

        @Override
        protected void logStarted() {
            throw new LinkageError("test-induced defect");
        }
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, messagePattern = "Failed to create and start a new instance for service 'broken'\\.")
    void aServiceFailingToStartIsLoggedLeftWithoutAnInstanceAndTheOthersStartAllTheSame() {
        Config config = ConfigFactory.parseString("""
            pipeline {
              autoStart = true
              startupSequence = ["consumer", "broken", "producer"]
              services.broken.className = "org.evochora.datapipeline.ServiceManagerTest$ConstructionFailingService"
            }
        """).withFallback(createTestConfig(true));

        ServiceManager sm = new ServiceManager(config);
        try {
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertEquals(IService.State.RUNNING, sm.getServiceStatus("consumer").state());
                assertEquals(IService.State.RUNNING, sm.getServiceStatus("producer").state(),
                        "a service after the failed one in the startup sequence is started all the same");
            });
            // It never ran, so it has no instance: it is reported as stopped, its reason is in the log
            assertEquals(IService.State.STOPPED, sm.getServiceStatus("broken").state());
        } finally {
            sm.shutdown();
        }
    }

    /**
     * A single start, as the HTTP API makes it, passes the defect on to its caller, unlogged: the
     * caller handles it and logs it, and no other service is involved.
     */
    @Test
    void aSingleStartPassesADefectOnWithoutLoggingIt() {
        Config config = ConfigFactory.parseString("""
            pipeline {
              autoStart = false
              services.broken.className = "org.evochora.datapipeline.ServiceManagerTest$ConstructionFailingService"
            }
        """).withFallback(createTestConfig(true));

        ServiceManager sm = new ServiceManager(config);
        try {
            ServiceStartException thrown = assertThrows(ServiceStartException.class, () -> sm.startService("broken"));
            assertTrue(thrown.getMessage().contains("'broken'"), thrown.getMessage());
            assertNotNull(thrown.getCause());
            assertEquals(IService.State.STOPPED, sm.getServiceStatus("broken").state());
        } finally {
            sm.shutdown();
        }
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, messagePattern = "Failed to create and start a new instance for service 'broken'\\.")
    void anErrorDuringAStartIsHandledLikeAnExceptionAndTheOthersStartAllTheSame() {
        Config config = ConfigFactory.parseString("""
            pipeline {
              autoStart = true
              startupSequence = ["broken", "producer"]
              services.broken.className = "org.evochora.datapipeline.ServiceManagerTest$StartAnnouncementFailingService"
            }
        """).withFallback(createTestConfig(true));

        ServiceManager sm = new ServiceManager(config);
        try {
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                assertEquals(IService.State.RUNNING, sm.getServiceStatus("producer").state()));
            assertEquals(IService.State.STOPPED, sm.getServiceStatus("broken").state());
        } finally {
            sm.shutdown();
        }
    }

    /** A resource whose construction fails with a cause wrapped in a failure of its own. */
    public static class ConstructionFailingResource {
        public ConstructionFailingResource(String name, Config options) {
            throw new IllegalStateException("cannot build " + name,
                    new java.io.IOException("test-induced root cause"));
        }
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, messagePattern = "Failed to instantiate resource 'broken': test-induced root cause\\. Skipping this resource\\.")
    void aResourceThatCannotBeBuiltIsSkippedWithItsRootCauseAndTheOthersAreBuilt() {
        Config config = ConfigFactory.parseString("""
            pipeline.resources.broken.className = "org.evochora.datapipeline.ServiceManagerTest$ConstructionFailingResource"
        """).withFallback(createTestConfig(false));

        ServiceManager sm = new ServiceManager(config);
        try {
            assertEquals(3, sm.getMetrics().get("resources_total"), "the three resources that can be built are there");
        } finally {
            sm.shutdown();
        }
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
    void pausingAndResumingPassesOverAServiceThatHasFinished() {
        ServiceManager sm = new ServiceManager(createTestConfig(true));

        sm.startAll();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(IService.State.RUNNING, sm.getServiceStatus("producer").state());
            assertEquals(IService.State.RUNNING, sm.getServiceStatus("consumer").state());
        });

        // A one-shot service that has done its work leaves the same trace: it is simply stopped
        sm.stopService("consumer");
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(
                () -> assertEquals(IService.State.STOPPED, sm.getServiceStatus("consumer").state()));

        sm.pauseAll();
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(IService.State.PAUSED, sm.getServiceStatus("producer").state());
            assertEquals(IService.State.STOPPED, sm.getServiceStatus("consumer").state());
        });

        sm.resumeAll();
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(IService.State.RUNNING, sm.getServiceStatus("producer").state());
            assertEquals(IService.State.STOPPED, sm.getServiceStatus("consumer").state());
        });

        sm.stopAll();
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
    void stopAllDoesNotWarnAboutAServiceThatStoppedByItselfInTheMeantime() {
        ServiceManager sm = new ServiceManager(ConfigFactory.parseString("""
            pipeline {
              autoStart = false
              startupSequence = ["one-shot"]
              resources {}
              services {
                one-shot {
                  className = "org.evochora.datapipeline.SelfStoppingTestService"
                }
              }
            }
            """));
        sm.startAll();
        assertEquals(IService.State.RUNNING, sm.getServiceStatus("one-shot").state());

        // The log watch fails this test on any warning
        sm.stopAll();

        assertEquals(IService.State.STOPPED, sm.getServiceStatus("one-shot").state());
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
    @ExpectLog(level = LogLevel.ERROR, messagePattern = "Failed to build factory for service 'test': Service 'test' references unknown resource 'non-existent-queue' for port 'input'\\. Skipping this service\\.")
    @ExpectLog(level = LogLevel.ERROR, messagePattern = "Failed to build factory for service 'test': com\\.example\\.NonExistent\\. Skipping this service\\.")
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
    void constructorRejectsInvalidEngineOptionsBeforeStartingAnything() {
        // A node without a local engine still reads the engine's options for its memory estimate;
        // an option the estimate cannot be built on fails the constructor, so no service exists yet.
        Config config = ConfigFactory.parseString("""
            pipeline {
              autoStart = true
              startupSequence = ["producer"]
              resources {
                "test-queue" {
                  className = "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue"
                  options { capacity = 100 }
                }
              }
              services {
                producer {
                  className = "org.evochora.datapipeline.services.DummyProducerService"
                  resources { output = "queue-out:test-queue?window=5" }
                  options { intervalMs = 10, maxMessages = -1 }
                }
                "simulation-engine" {
                  className = "org.evochora.datapipeline.services.SimulationEngine"
                  options {
                    environment { shape = [32, 32] }
                    samplingInterval = 0
                  }
                }
              }
            }
        """);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ServiceManager(config));
        assertTrue(exception.getMessage().contains("samplingInterval"), exception.getMessage());
    }

    /** A service that needs no resources and runs until it is stopped. */
    private static class IdleService extends org.evochora.datapipeline.services.AbstractService {
        IdleService(String name) {
            super(name, ConfigFactory.empty(), java.util.Map.of());
        }

        @Override
        protected void run() throws InterruptedException {
            while (!isStopRequested()) {
                checkPause();
                Thread.sleep(10);
            }
        }
    }

    /**
     * A start that fails while a second start of the same service waits must leave the second one
     * to come up on its own: without the manager's lock the second would register a running
     * instance during the first one's construction, and the first one's failure would then remove
     * that running instance from the registry, so that the service is reported as stopped while it
     * runs.
     */
    @Test
    void aStartWaitsForAnotherStartOfTheSameServiceToEnd() throws InterruptedException {
        ServiceManager sm = new ServiceManager(createTestConfig(true));
        CountDownLatch firstConstructing = new CountDownLatch(1);
        CountDownLatch firstMayFail = new CountDownLatch(1);
        AtomicInteger creations = new AtomicInteger();
        sm.registerCustomFactory("producer", () -> {
            if (creations.incrementAndGet() == 1) {
                firstConstructing.countDown();
                try {
                    firstMayFail.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("test-induced defect");
            }
            return new IdleService("producer");
        });
        java.util.concurrent.atomic.AtomicReference<RuntimeException> firstFailure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                sm.startService("producer");
            } catch (ServiceStartException e) {
                firstFailure.set(e);
            }
        }, "first-start");
        Thread second = new Thread(() -> sm.startService("producer"), "second-start");
        try {
            first.start();
            assertTrue(firstConstructing.await(5, TimeUnit.SECONDS), "the first start is constructing");
            second.start();
            // Waiting for the first start, or, without the lock, already through
            await().atMost(5, TimeUnit.SECONDS).until(() ->
                    second.getState() == Thread.State.BLOCKED || second.getState() == Thread.State.TERMINATED);
            firstMayFail.countDown();
            first.join(5000);
            second.join(5000);

            assertEquals(2, creations.get());
            assertNotNull(firstFailure.get(), "the first start passes its failure on");
            ServiceStatus producer = sm.getServiceStatus("producer");
            assertEquals(IService.State.RUNNING, producer.state());
        } finally {
            firstMayFail.countDown();
            sm.shutdown();
        }
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
    @ExpectLog(level = LogLevel.INFO, messagePattern = ".*========== Resource Initialization ==========.*")
    @ExpectLog(level = LogLevel.INFO, messagePattern = "Instantiated resource 'test-queue' of type .*")
    @ExpectLog(level = LogLevel.INFO, messagePattern = "Instantiated resource 'consumer-dlq' of type .*")
    @ExpectLog(level = LogLevel.INFO, messagePattern = "Instantiated resource 'consumer-idempotency-tracker' of type .*")
    @ExpectLog(level = LogLevel.INFO, messagePattern = ".*========== Service Initialization ==========.*")
    @ExpectLog(level = LogLevel.INFO, messagePattern = "Built factory for service 'producer' of type .*")
    @ExpectLog(level = LogLevel.INFO, messagePattern = "Built factory for service 'consumer' of type .*")
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

    /**
     * A resource set to {@code null} in the configuration is not there: not instantiated, not
     * listed, and no error about it. That is how a configuration that includes the defaults takes
     * a resource out again.
     */
    @Test
    void aResourceSetToNullIsNotThere() {
        ServiceManager sm = new ServiceManager(ConfigFactory.parseString("""
            pipeline {
              autoStart = false
              startupSequence = ["producer"]
              resources {
                "test-queue" {
                  className = "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue"
                  options { capacity = 100 }
                }
                gone = null
              }
              services {
                producer {
                  className = "org.evochora.datapipeline.services.DummyProducerService"
                  resources { output = "queue-out:test-queue?window=5" }
                  options { intervalMs = 10, maxMessages = 1 }
                }
              }
            }
            """));

        assertNotNull(sm.getResource("test-queue", IResource.class));
        assertThrows(IllegalArgumentException.class, () -> sm.getResource("gone", IResource.class));
    }

    /** A service set to {@code null} gets no factory and is unknown to the manager. */
    @Test
    void aServiceSetToNullIsNotThere() {
        ServiceManager sm = new ServiceManager(ConfigFactory.parseString("""
            pipeline {
              autoStart = false
              startupSequence = ["producer"]
              resources {
                "test-queue" {
                  className = "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue"
                  options { capacity = 100 }
                }
              }
              services {
                producer {
                  className = "org.evochora.datapipeline.services.DummyProducerService"
                  resources { output = "queue-out:test-queue?window=5" }
                  options { intervalMs = 10, maxMessages = 1 }
                }
                gone = null
              }
            }
            """));

        assertEquals(IService.State.STOPPED, sm.getServiceStatus("producer").state());
        assertThrows(IllegalArgumentException.class, () -> sm.getServiceStatus("gone"));
    }

    /**
     * A resource binding set to {@code null} is not bound: the service starts with the bindings
     * that remain. A service that binds a resource only for a mode that is switched off can so
     * be run in a configuration where that resource is gone.
     */
    @Test
    void aBindingSetToNullIsNotBound() {
        ServiceManager sm = new ServiceManager(ConfigFactory.parseString("""
            pipeline {
              autoStart = false
              startupSequence = ["producer"]
              resources {
                "test-queue" {
                  className = "org.evochora.datapipeline.resources.queues.InMemoryBlockingQueue"
                  options { capacity = 100 }
                }
              }
              services {
                producer {
                  className = "org.evochora.datapipeline.services.DummyProducerService"
                  resources {
                    output = "queue-out:test-queue?window=5"
                    gone = null
                  }
                  options { intervalMs = 10, maxMessages = -1 }
                }
              }
            }
            """));

        sm.startAll();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                assertEquals(IService.State.RUNNING, sm.getServiceStatus("producer").state()));
        assertEquals(1, sm.getServiceStatus("producer").resourceBindings().size(),
                "only the binding that was not taken out");
        sm.stopAll();
    }
}
