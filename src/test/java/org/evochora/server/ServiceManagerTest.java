package org.evochora.server;

import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.indexer.DebugIndexer;
import org.evochora.server.persistence.PersistenceService;
import org.evochora.server.http.DebugServer;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.config.SimulationConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Tag;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contains integration tests for the {@link ServiceManager}.
 * These tests verify that the manager can correctly orchestrate the lifecycle
 * (start, pause, resume, stop) of the entire suite of server services.
 * These are integration tests as they manage the interaction of multiple threaded services.
 */
class ServiceManagerTest {

    private ServiceManager serviceManager;
    private InMemoryTickQueue queue;
    private SimulationConfiguration config;

    @BeforeEach
    void setUp() {
        queue = new InMemoryTickQueue(10000);
        config = new SimulationConfiguration();
        
        // Initialize simulation config
        config.simulation = new SimulationConfiguration.SimulationConfig();
        config.simulation.environment = new SimulationConfiguration.EnvironmentConfig();
        config.simulation.environment.shape = new int[]{10, 10};
        config.simulation.environment.toroidal = true;
        config.simulation.seed = 12345L;

        config.simulation.energyStrategies = new java.util.ArrayList<>();
        
        // Initialize pipeline config
        config.pipeline = new SimulationConfiguration.PipelineConfig();
        config.pipeline.simulation = new SimulationConfiguration.SimulationServiceConfig();
        config.pipeline.persistence = new SimulationConfiguration.PersistenceServiceConfig();
        config.pipeline.indexer = new SimulationConfiguration.IndexerServiceConfig();
        config.pipeline.server = new SimulationConfiguration.ServerServiceConfig();
        
        // Set default values with in-memory databases for faster tests
        config.pipeline.persistence.batchSize = 1000;
        config.pipeline.persistence.jdbcUrl = "jdbc:sqlite::memory:";
        config.pipeline.indexer.batchSize = 1000;
        config.pipeline.server.port = 0;
        
        serviceManager = new ServiceManager(queue, config);
    }

    @AfterEach
    void tearDown() {
        if (serviceManager != null) {
            serviceManager.stopAll();
        }
    }

    /**
     * Wait for a condition to be true, checking every 10ms
     * @param condition The condition to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     * @param description Description of what we're waiting for
     * @return true if condition was met, false if timeout occurred
     */
    private boolean waitForCondition(BooleanSupplier condition, long timeoutMs, String description) {
        long startTime = System.currentTimeMillis();
        long checkInterval = 10; // Check every 10ms for faster response

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                // Timeout waiting for: " + description
                return false;
            }
            try {
                Thread.sleep(checkInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Wait for services to start running
     */
    private boolean waitForServicesStarted(long timeoutMs) {
        return waitForCondition(
            () -> {
                String status = serviceManager.getStatus();

                return status.contains("sim") && status.contains("started") &&
                        status.contains("persist") && status.contains("started") &&
                        status.contains("indexer") && status.contains("started") &&
                        status.contains("web") && status.contains("started");
            },
            timeoutMs,
            "all services to start"
        );
    }

    /**
     * Wait for services to pause
     */
    private boolean waitForServicesPaused(long timeoutMs) {
        return waitForCondition(
            () -> {
                String status = serviceManager.getStatus();
                // Check for the actual status format used by ServiceManager.getStatus()
                return status.contains("sim") && status.contains("paused") &&
                       status.contains("persist") && status.contains("paused") &&
                       status.contains("indexer") && status.contains("paused") &&
                       status.contains("web") && status.contains("stopped");
            },
            timeoutMs,
            "all services to pause"
        );
    }

    /**
     * Wait for services to resume
     */
    private boolean waitForServicesResumed(long timeoutMs) {
        return waitForCondition(
            () -> {
                String status = serviceManager.getStatus();
                return status.contains("sim") && status.contains("started") &&
                       status.contains("persist") && status.contains("paused") &&
                       status.contains("indexer") && status.contains("started") &&
                       status.contains("web") && status.contains("started");
            },
            timeoutMs,
            "all services to resume"
        );
    }

    /**
     * Wait for services to stop
     */
    private boolean waitForServicesStopped(long timeoutMs) {
        return waitForCondition(
            () -> {
                String status = serviceManager.getStatus();
                return status.contains("NOT_STARTED") || status.contains("stopped");
            },
            timeoutMs,
            "all services to stop"
        );
    }

    /**
     * Verifies that the ServiceManager can be created without errors.
     * This is an integration test.
     */
    @Test
    @Tag("unit")
    void testServiceManagerCreation() {
        assertNotNull(serviceManager);
    }

    /**
     * Verifies that the ServiceManager can start all registered services.
     * This is an integration test of the service lifecycle.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testStartAllServices() throws InterruptedException {
        // Start all services
        serviceManager.startAll();
        
        // Wait for services to start
        assertTrue(waitForServicesStarted(2000));
        
        // Get status to verify services are running
        String status = serviceManager.getStatus();
        assertNotNull(status);
        assertTrue(status.contains("sim") && status.contains("started"));
        assertTrue(status.contains("persist") && status.contains("started"));
        assertTrue(status.contains("indexer") && status.contains("started"));
        assertTrue(status.contains("web") && status.contains("started"));
    }

    /**
     * Verifies that the ServiceManager can pause all registered services.
     * This is an integration test of the service lifecycle.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testPauseAllServices() throws InterruptedException {
        // Start all services
        serviceManager.startAll();
        assertTrue(waitForServicesStarted(2000));
        
        // Pause all services
        serviceManager.pauseAll();
        assertTrue(waitForServicesPaused(2000));
        
        // Get status to verify services are paused
        String status = serviceManager.getStatus();
        assertNotNull(status);
        assertTrue(status.contains("sim") && status.contains("paused"));
        assertTrue(status.contains("persist") && status.contains("paused"));
        assertTrue(status.contains("indexer") && status.contains("paused"));
        // DebugServer should be stopped when paused
        assertTrue(status.contains("web") && status.contains("stopped"));
    }

    /**
     * Verifies that the ServiceManager can resume all paused services.
     * This is an integration test of the service lifecycle.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testResumeAllServices() throws InterruptedException {
        // Start all services
        serviceManager.startAll();
        assertTrue(waitForServicesStarted(2000));
        
        // Pause all services
        serviceManager.pauseAll();
        assertTrue(waitForServicesPaused(2000));
        
        // Resume all services
        serviceManager.resumeAll();
        assertTrue(waitForServicesResumed(2000));
        
        // Get status to verify services are running again
        String status = serviceManager.getStatus();
        assertNotNull(status);
        assertTrue(status.contains("sim") && status.contains("started"));
        assertTrue(status.contains("persist") && status.contains("started"));
        assertTrue(status.contains("indexer") && status.contains("started"));
        assertTrue(status.contains("web") && status.contains("started"));
    }

    /**
     * Verifies that the ServiceManager can start a single, specific service by name.
     * This is an integration test of the service lifecycle.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testStartSpecificService() throws InterruptedException {
        // Start only simulation service
        serviceManager.startService("simulation");
        assertTrue(waitForCondition(
            () -> {
                String status = serviceManager.getStatus();
                return status.contains("sim") && status.contains("started");
            },
            1000,
            "simulation service to start"
        ));
        
        String status = serviceManager.getStatus();
        assertTrue(status.contains("sim") && status.contains("started"));
        assertTrue(status.contains("persist") && status.contains("NOT_STARTED"));
        assertTrue(status.contains("indexer") && status.contains("NOT_STARTED"));
        assertTrue(status.contains("web") && status.contains("NOT_STARTED"));
    }

    /**
     * Verifies that the ServiceManager can pause a single, specific service by name.
     * This is an integration test of the service lifecycle.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testPauseSpecificService() throws InterruptedException {
        // Start all services
        serviceManager.startAll();
        assertTrue(waitForServicesStarted(2000));
        
        // Pause only persistence service
        serviceManager.pauseService("persistence");
        assertTrue(waitForCondition(
            () -> {
                String status = serviceManager.getStatus();
                return status.contains("persist") && status.contains("paused");
            },
            2000,
            "persistence service to pause"
        ));
        
        String status = serviceManager.getStatus();
        assertTrue(status.contains("sim") && status.contains("started"));
        assertTrue(status.contains("persist") && status.contains("paused"));
        assertTrue(status.contains("indexer") && status.contains("started"));
        assertTrue(status.contains("web") && status.contains("started"));
    }

    /**
     * Verifies that the ServiceManager can resume a single, specific service by name.
     * This is an integration test of the service lifecycle.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testResumeSpecificService() throws InterruptedException {
        // Start all services
        serviceManager.startAll();
        assertTrue(waitForServicesStarted(2000));
        
        // Pause persistence service
        serviceManager.pauseService("persistence");
        assertTrue(waitForCondition(
            () -> {
                String status = serviceManager.getStatus();
                return status.contains("persist") && status.contains("paused");
            },
            2000,
            "persistence service to pause"
        ));
        
        // Resume persistence service
        serviceManager.resumeService("persistence");
        assertTrue(waitForCondition(
            () -> {
                String status = serviceManager.getStatus();
                return status.contains("persist") && status.contains("started");
            },
            2000,
            "persistence service to resume"
        ));
        
        String status = serviceManager.getStatus();
        assertTrue(status.contains("sim") && status.contains("started"));
        assertTrue(status.contains("persist") && status.contains("started"));
        assertTrue(status.contains("indexer") && status.contains("started"));
        assertTrue(status.contains("web") && status.contains("started"));
    }

    /**
     * Verifies that the ServiceManager can gracefully shut down all services.
     * This is an integration test of the service lifecycle.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testShutdownAllServices() throws InterruptedException {
        // Start all services
        serviceManager.startAll();
        assertTrue(waitForServicesStarted(2000));
        
        // Verify services are running
        String runningStatus = serviceManager.getStatus();
        assertTrue(runningStatus.contains("started") || runningStatus.contains("running"));
        
        // Shutdown all services
        serviceManager.stopAll();
        assertTrue(waitForServicesStopped(2000));
        
        // Verify all services are stopped
        String stoppedStatus = serviceManager.getStatus();
        assertTrue(stoppedStatus.contains("NOT_STARTED") || stoppedStatus.contains("stopped"));
    }

    /**
     * Verifies that the ServiceManager can be initialized with a custom configuration.
     * This is an integration test.
     */
    @Test
    @Tag("unit")
    void testServiceManagerWithCustomConfig() {
        // Test with custom batch sizes
        config.pipeline.persistence.batchSize = 500;
        config.pipeline.indexer.batchSize = 2000;
        
        ServiceManager customManager = new ServiceManager(queue, config);
        assertNotNull(customManager);
        
        customManager.stopAll();
    }

    /**
     * Verifies that the ServiceManager handles requests for unknown service names gracefully.
     * This is an integration test for error handling.
     */
    @Test
    @Tag("unit")
    void testUnknownServiceName() {
        // Test handling of unknown service names
        serviceManager.startService("unknown");
        serviceManager.pauseService("unknown");
        serviceManager.resumeService("unknown");
        
        // Should not throw exceptions, just log warnings
        assertNotNull(serviceManager.getStatus());
    }
}
