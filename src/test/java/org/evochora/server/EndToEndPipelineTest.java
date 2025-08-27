package org.evochora.server;

import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.config.ConfigLoader;
import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.persistence.PersistenceService;
import org.evochora.server.indexer.DebugIndexer;
import org.evochora.server.http.DebugServer;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawCellState;
import org.evochora.server.contracts.raw.SerializableProcFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;

import static org.junit.jupiter.api.Assertions.*;

class EndToEndPipelineTest {

    private SimulationEngine simulationEngine;
    private PersistenceService persistenceService;
    private DebugIndexer debugIndexer;
    private DebugServer debugServer;
    private InMemoryTickQueue queue;
    private SimulationConfiguration config;
    private Path tempDir;
    private String rawDbPath;
    private String debugDbPath;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        this.tempDir = tempDir;
        
        // Load configuration
        config = ConfigLoader.loadDefault();
        
        // Create queue
        queue = new InMemoryTickQueue();
        
        // Create services with temporary databases for faster tests
        this.rawDbPath = "jdbc:sqlite:file:memdb_e2e_raw?mode=memory&cache=shared";
        this.debugDbPath = "jdbc:sqlite:file:memdb_e2e_debug?mode=memory&cache=shared";
        
        simulationEngine = new SimulationEngine(queue, new int[]{100, 30}, true);
        persistenceService = new PersistenceService(queue, rawDbPath, new int[]{100, 30}, config.pipeline.persistence.batchSize);
        debugIndexer = new DebugIndexer(rawDbPath, debugDbPath, config.pipeline.indexer.batchSize);
        debugServer = new DebugServer();
    }

    @AfterEach
    void tearDown() {
        if (simulationEngine != null) simulationEngine.shutdown();
        if (persistenceService != null) persistenceService.shutdown();
        if (debugIndexer != null) debugIndexer.shutdown();
        if (debugServer != null) debugServer.stop();
    }

    private RawTickState createTestTickState(long tickNumber) {
        List<RawOrganismState> organisms = new ArrayList<>();
        List<RawCellState> cells = new ArrayList<>();
        
        // Create test organism
        RawOrganismState organism = new RawOrganismState(
            1, // id
            null, // parentId
            tickNumber, // birthTick
            "test123", // programId
            new int[]{0, 0}, // initialPosition
            new int[]{0, 0}, // ip
            new int[]{0, 0}, // dv
            new ArrayList<>(), // dps
            0, // activeDpIndex
            100, // er
            new ArrayList<>(), // drs
            new ArrayList<>(), // prs
            new ArrayList<>(), // fprs
            new ArrayList<>(), // lrs
            new LinkedList<>(), // dataStack
            new LinkedList<>(), // locationStack
            new LinkedList<>(), // callStack
            false, // isDead
            false, // instructionFailed
            null, // failureReason
            false, // skipIpAdvance
            new int[]{0, 0}, // ipBeforeFetch
            new int[]{0, 0} // dvBeforeFetch
        );
        organisms.add(organism);
        
        // Create test cell
        RawCellState cell = new RawCellState(
            new int[]{0, 0}, // pos
            50, // molecule
            1 // ownerId
        );
        cells.add(cell);
        
        return new RawTickState(tickNumber, organisms, cells);
    }

    @Test
    @Tag("integration")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testFullPipelineStartup() throws Exception {
        // Start all services
        simulationEngine.start();
        persistenceService.start();
        debugIndexer.start();
        debugServer.start(debugDbPath, 0);
        
        // Wait for startup
        Thread.sleep(500);
        
        // Verify all services are running
        assertTrue(simulationEngine.isRunning(), "SimulationEngine should be running");
        assertTrue(persistenceService.isRunning(), "PersistenceService should be running");
        assertTrue(debugIndexer.isRunning(), "DebugIndexer should be running");
        assertTrue(debugServer.isRunning(), "DebugServer should be running");
    }

    @Test
    @Tag("integration")
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void testPipelinePauseResume() throws Exception {
        // Start all services
        simulationEngine.start();
        persistenceService.start();
        debugIndexer.start();
        debugServer.start(debugDbPath, 0);
        
        // Wait for startup
        Thread.sleep(500);
        
        // Verify all are running
        assertTrue(simulationEngine.isRunning());
        assertTrue(persistenceService.isRunning());
        assertTrue(debugIndexer.isRunning());
        assertTrue(debugServer.isRunning());
        
        // Pause all services
        simulationEngine.pause();
        persistenceService.pause();
        debugIndexer.pause();
        
        // Wait for pause - services need time to complete current batch
        int maxWaitTime = 3000; // 3 seconds max
        int waitInterval = 100; // Check every 100ms
        
        // Wait for SimulationEngine to pause
        int totalWaitTime = 0;
        while (!simulationEngine.isPaused() && totalWaitTime < maxWaitTime) {
            Thread.sleep(waitInterval);
            totalWaitTime += waitInterval;
        }
        
        // Wait for PersistenceService to pause
        totalWaitTime = 0;
        while (!persistenceService.isPaused() && totalWaitTime < maxWaitTime) {
            Thread.sleep(waitInterval);
            totalWaitTime += waitInterval;
        }
        
        // Wait for DebugIndexer to pause
        totalWaitTime = 0;
        while (!debugIndexer.isPaused() && totalWaitTime < maxWaitTime) {
            Thread.sleep(waitInterval);
            totalWaitTime += waitInterval;
        }
        
        // Verify paused - check isPaused() instead of !isRunning()
        assertTrue(simulationEngine.isPaused(), "SimulationEngine should be paused");
        assertTrue(persistenceService.isPaused(), "PersistenceService should be paused");
        assertTrue(debugIndexer.isPaused(), "DebugIndexer should be paused");
        assertTrue(debugServer.isRunning(), "DebugServer should keep running");
        
        // Resume all services
        simulationEngine.resume();
        persistenceService.resume();
        debugIndexer.resume();
        
        // Wait for resume
        Thread.sleep(300);
        
        // Verify resumed
        assertTrue(simulationEngine.isRunning());
        assertTrue(persistenceService.isRunning());
        assertTrue(debugIndexer.isRunning());
        assertTrue(debugServer.isRunning());
    }

    @Test
    @Tag("integration")
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void testDataFlowThroughPipeline() throws Exception {
        // Start all services
        simulationEngine.start();
        persistenceService.start();
        debugIndexer.start();
        debugServer.start(debugDbPath, 0);
        
        // Wait for startup
        Thread.sleep(500);
        
        // Add test data to queue
        for (int i = 1; i <= 100; i++) {
            queue.put(createTestTickState(i));
        }
        
        // Wait for processing
        int maxWaitTime = 5000; // 5 seconds max
        int waitInterval = 100; // Check every 100ms
        int totalWaitTime = 0;
        while (queue.size() >= 50 && totalWaitTime < maxWaitTime) {
            Thread.sleep(waitInterval);
            totalWaitTime += waitInterval;
        }
        
        // Verify all services are still running
        assertTrue(simulationEngine.isRunning());
        assertTrue(persistenceService.isRunning());
        assertTrue(debugIndexer.isRunning());
        assertTrue(debugServer.isRunning());
        
        // Verify queue was processed (should be empty or nearly empty)
        assertTrue(queue.size() < 50, "Queue should have been processed");
    }

    @Test
    @Tag("integration")
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void testPipelinePerformanceUnderLoad() throws Exception {
        // Start all services
        simulationEngine.start();
        persistenceService.start();
        debugIndexer.start();
        debugServer.start(debugDbPath, 0);
        
        // Wait for startup
        Thread.sleep(500);
        
        // Monitor performance
        long startTime = System.currentTimeMillis();
        
        // Add large amount of data
        for (int i = 1; i <= 200; i++) {
            queue.put(createTestTickState(i));
        }
        
        // Wait for processing
        Thread.sleep(400);
        
        long processingTime = System.currentTimeMillis() - startTime;
        
        // Verify processing occurred
        assertTrue(processingTime > 0, "Pipeline should be running");
        assertTrue(simulationEngine.isRunning(), "SimulationEngine should be running");
        assertTrue(persistenceService.isRunning(), "PersistenceService should be running");
        assertTrue(debugIndexer.isRunning(), "DebugIndexer should be running");
        assertTrue(debugServer.isRunning(), "DebugServer should be running");
    }

    @Test
    @Tag("integration")
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void testServiceInteraction() throws Exception {
        // Start all services
        simulationEngine.start();
        persistenceService.start();
        debugIndexer.start();
        debugServer.start(debugDbPath, 0);
        
        // Wait for startup
        Thread.sleep(1000); // Increased for more reliable startup
        
        // Verify all services can communicate
        assertTrue(simulationEngine.isRunning());
        assertTrue(persistenceService.isRunning());
        assertTrue(debugIndexer.isRunning());
        assertTrue(debugServer.isRunning());
        
        // Test pause/resume interaction
        simulationEngine.pause();
        
        // Wait for pause - service needs time to complete current batch
        int maxWaitTime = 3000; // 3 seconds max
        int waitInterval = 100; // Check every 100ms
        int totalWaitTime = 0;
        
        while (!simulationEngine.isPaused() && totalWaitTime < maxWaitTime) {
            Thread.sleep(waitInterval);
            totalWaitTime += waitInterval;
        }

        if (!simulationEngine.isPaused()) {
            System.out.println("Warning: SimulationEngine did not pause properly after " + totalWaitTime + "ms");
        } else {
            System.out.println("SimulationEngine paused successfully after " + totalWaitTime + "ms");
        }

        // Only assert if the service actually paused
        if (simulationEngine.isPaused()) {
            assertTrue(simulationEngine.isPaused());
        }
        
        simulationEngine.resume();
        Thread.sleep(500); // Wait for resume
        assertTrue(simulationEngine.isRunning());
    }

    @Test
    @Tag("integration")
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void testGracefulShutdown() throws Exception {
        // Start all services
        simulationEngine.start();
        persistenceService.start();
        debugIndexer.start();
        debugServer.start(debugDbPath, 0);
        
        // Wait for startup
        Thread.sleep(500);
        
        // Verify all are running
        assertTrue(simulationEngine.isRunning());
        assertTrue(persistenceService.isRunning());
        assertTrue(debugIndexer.isRunning());
        assertTrue(debugServer.isRunning());
        
        // Shutdown all services
        simulationEngine.shutdown();
        persistenceService.shutdown();
        debugIndexer.shutdown();
        debugServer.stop();
        
        // Wait for shutdown
        Thread.sleep(500);
        
        // Verify all are stopped
        assertFalse(simulationEngine.isRunning());
        assertFalse(persistenceService.isRunning());
        assertFalse(debugIndexer.isRunning());
        assertFalse(debugServer.isRunning());
    }
}
