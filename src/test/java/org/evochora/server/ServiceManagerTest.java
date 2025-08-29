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
        queue = new InMemoryTickQueue();
        config = new SimulationConfiguration();
        
        // Initialize simulation config
        config.simulation = new SimulationConfiguration.SimulationConfig();
        config.simulation.environment = new SimulationConfiguration.EnvironmentConfig();
        config.simulation.environment.shape = new int[]{10, 10};
        config.simulation.environment.toroidal = true;
        config.simulation.seed = 12345L;
        config.simulation.organisms = new SimulationConfiguration.OrganismDefinition[0];
        config.simulation.energyStrategies = new java.util.ArrayList<>();
        
        // Initialize pipeline config
        config.pipeline = new SimulationConfiguration.PipelineConfig();
        config.pipeline.simulation = new SimulationConfiguration.SimulationServiceConfig();
        config.pipeline.persistence = new SimulationConfiguration.PersistenceServiceConfig();
        config.pipeline.indexer = new SimulationConfiguration.IndexerServiceConfig();
        config.pipeline.server = new SimulationConfiguration.ServerServiceConfig();
        
        // Set default values with unique cache names to avoid shared cache locking
        config.pipeline.persistence.batchSize = 1000;
        config.pipeline.persistence.jdbcUrl = "jdbc:sqlite:file:memdb_sm_persistence?mode=memory&cache=shared";
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
     * Verifies that the ServiceManager can be created without errors.
     * This is an integration test.
     */
    @Test
    @Tag("integration")
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
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testStartAllServices() throws InterruptedException {
        // Start all services
        serviceManager.startAll();
        
        // Wait for services to start
        Thread.sleep(200);
        
        // Get status to verify services are running
        String status = serviceManager.getStatus();
        assertNotNull(status);
        assertTrue(status.contains("Simulation: started"));
        assertTrue(status.contains("Persistence: started"));
        assertTrue(status.contains("Indexer: started"));
        assertTrue(status.contains("DebugServer: running"));
    }

    /**
     * Verifies that the ServiceManager can pause all registered services.
     * This is an integration test of the service lifecycle.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testPauseAllServices() throws InterruptedException {
        // Start all services
        serviceManager.startAll();
        Thread.sleep(200);
        
        // Pause all services
        serviceManager.pauseAll();
        Thread.sleep(100);
        
        // Get status to verify services are paused
        String status = serviceManager.getStatus();
        assertNotNull(status);
        assertTrue(status.contains("Simulation: paused"));
        assertTrue(status.contains("Persistence: paused"));
        assertTrue(status.contains("Indexer: paused"));
        // DebugServer should be stopped when paused
        assertTrue(status.contains("DebugServer: stopped"));
    }

    /**
     * Verifies that the ServiceManager can resume all paused services.
     * This is an integration test of the service lifecycle.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testResumeAllServices() throws InterruptedException {
        // Start all services
        serviceManager.startAll();
        Thread.sleep(200);
        
        // Pause all services
        serviceManager.pauseAll();
        Thread.sleep(100);
        
        // Resume all services
        serviceManager.resumeAll();
        Thread.sleep(100);
        
        // Get status to verify services are running again
        String status = serviceManager.getStatus();
        assertNotNull(status);
        assertTrue(status.contains("Simulation: started"));
        assertTrue(status.contains("Persistence: started"));
        assertTrue(status.contains("Indexer: started"));
        assertTrue(status.contains("DebugServer: running"));
    }

    /**
     * Verifies that the ServiceManager can start a single, specific service by name.
     * This is an integration test of the service lifecycle.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testStartSpecificService() throws InterruptedException {
        // Start only simulation service
        serviceManager.startService("simulation");
        Thread.sleep(100);
        
        String status = serviceManager.getStatus();
        assertTrue(status.contains("Simulation: started"));
        assertTrue(status.contains("Persistence: NOT_STARTED"));
        assertTrue(status.contains("Indexer: NOT_STARTED"));
        assertTrue(status.contains("DebugServer: NOT_STARTED"));
    }

    /**
     * Verifies that the ServiceManager can pause a single, specific service by name.
     * This is an integration test of the service lifecycle.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testPauseSpecificService() throws InterruptedException {
        // Start all services
        serviceManager.startAll();
        Thread.sleep(200);
        
        // Pause only persistence service
        serviceManager.pauseService("persistence");
        Thread.sleep(100);
        
        String status = serviceManager.getStatus();
        assertTrue(status.contains("Simulation: started"));
        assertTrue(status.contains("Persistence: paused"));
        assertTrue(status.contains("Indexer: started"));
        assertTrue(status.contains("DebugServer: running"));
    }

    /**
     * Verifies that the ServiceManager can resume a single, specific service by name.
     * This is an integration test of the service lifecycle.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testResumeSpecificService() throws InterruptedException {
        // Start all services
        serviceManager.startAll();
        Thread.sleep(200);
        
        // Pause persistence service
        serviceManager.pauseService("persistence");
        Thread.sleep(100);
        
        // Resume persistence service
        serviceManager.resumeService("persistence");
        Thread.sleep(100);
        
        String status = serviceManager.getStatus();
        assertTrue(status.contains("Simulation: started"));
        assertTrue(status.contains("Persistence: started"));
        assertTrue(status.contains("Indexer: started"));
        assertTrue(status.contains("DebugServer: running"));
    }

    /**
     * Verifies that the ServiceManager can gracefully shut down all services.
     * This is an integration test of the service lifecycle.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testShutdownAllServices() throws InterruptedException {
        // Start all services
        serviceManager.startAll();
        Thread.sleep(200);
        
        // Verify services are running
        String runningStatus = serviceManager.getStatus();
        assertTrue(runningStatus.contains("started") || runningStatus.contains("running"));
        
        // Shutdown all services
        serviceManager.stopAll();
        Thread.sleep(100);
        
        // Verify all services are stopped
        String stoppedStatus = serviceManager.getStatus();
        assertTrue(stoppedStatus.contains("NOT_STARTED") || stoppedStatus.contains("stopped"));
    }

    /**
     * Verifies that the ServiceManager can be initialized with a custom configuration.
     * This is an integration test.
     */
    @Test
    @Tag("integration")
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
    @Tag("integration")
    void testUnknownServiceName() {
        // Test handling of unknown service names
        serviceManager.startService("unknown");
        serviceManager.pauseService("unknown");
        serviceManager.resumeService("unknown");
        
        // Should not throw exceptions, just log warnings
        assertNotNull(serviceManager.getStatus());
    }
}
