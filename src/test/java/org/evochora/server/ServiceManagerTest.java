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

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

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
        
        // Set default values
        config.pipeline.persistence.batchSize = 1000;
        config.pipeline.persistence.jdbcUrl = "jdbc:sqlite:file:memdb_sm?mode=memory&cache=shared";
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

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testServiceManagerCreation() {
        assertNotNull(serviceManager);
    }

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testServiceManagerWithCustomConfig() {
        // Test with custom batch sizes
        config.pipeline.persistence.batchSize = 500;
        config.pipeline.indexer.batchSize = 2000;
        
        ServiceManager customManager = new ServiceManager(queue, config);
        assertNotNull(customManager);
        
        customManager.stopAll();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testUnknownServiceName() {
        // Test handling of unknown service names
        serviceManager.startService("unknown");
        serviceManager.pauseService("unknown");
        serviceManager.resumeService("unknown");
        
        // Should not throw exceptions, just log warnings
        assertNotNull(serviceManager.getStatus());
    }
}
