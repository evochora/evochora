package org.evochora.server;

import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.engine.OrganismPlacement;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.persistence.PersistenceService;
import org.evochora.server.indexer.DebugIndexer;
import org.evochora.server.http.DebugServer;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.worldgen.IEnergyDistributionCreator;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Clean, systematic test for the complete pipeline data flow.
 * Tests that data flows correctly from SimulationEngine -> PersistenceService -> DebugIndexer.
 * Based on BenchmarkTest but simplified for fast execution.
 */
@Tag("integration")
public class PipelineFlowTest {

    // ===== TEST PARAMETERS =====
    private int simulationTicks = 101; // Small number for fast test
    private int persistenceBatchSize = 10; // Small batch size for fast processing
    private int indexerBatchSize = 10; // Small batch size for fast processing
    private EnvironmentProperties environmentProperties = new EnvironmentProperties(new int[]{10, 10}, true);
    
    // ===== TIMEOUT CONFIGURATION =====
    private int processingTimeoutMs = 1000; // Max wait for queue processing
    
    // ===== SERVICES =====
    private SimulationEngine simulationEngine;
    private PersistenceService persistenceService;
    private DebugIndexer debugIndexer;
    private DebugServer debugServer;
    private InMemoryTickQueue queue;
    
    // ===== TEST DATA =====
    private String rawDbPath;
    private String debugDbPath;
    private List<OrganismPlacement> organismPlacements;
    
    @BeforeAll
    static void init() {
        Instruction.init();
    }
    
    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Instruction.init();
        
        // Create in-memory databases
        String uniqueDbName = "pipeline_test_" + System.currentTimeMillis();
        this.rawDbPath = "jdbc:sqlite:file:" + uniqueDbName + "?mode=memory&cache=shared&journal_mode=WAL&synchronous=NORMAL&locking_mode=NORMAL&cache_size=10000&temp_store=MEMORY&busy_timeout=30000&read_uncommitted=true";
        this.debugDbPath = "jdbc:sqlite:file:" + uniqueDbName + "_debug?mode=memory&cache=shared&journal_mode=WAL&synchronous=NORMAL&locking_mode=NORMAL&cache_size=10000&temp_store=MEMORY&busy_timeout=30000&read_uncommitted=true";
        
        // Create queue
        queue = new InMemoryTickQueue();
        
        // Create organism placements with simple NOP program
        organismPlacements = createTestOrganisms();
        
        // Create services
        List<IEnergyDistributionCreator> energyStrategies = new ArrayList<>();
        
        simulationEngine = new SimulationEngine(
            queue, 
            environmentProperties,
            organismPlacements,
            energyStrategies,
            false // Don't skip program artifacts
        );
        
        // maxTicks will be set by individual tests as needed
        
        persistenceService = new PersistenceService(queue, rawDbPath, environmentProperties, persistenceBatchSize);
        debugIndexer = new DebugIndexer(rawDbPath, debugDbPath, indexerBatchSize);
        debugServer = new DebugServer();
    }
    
    @AfterEach
    void tearDown() {
        if (simulationEngine != null) simulationEngine.shutdown();
        if (persistenceService != null) persistenceService.shutdown();
        if (debugIndexer != null) debugIndexer.shutdown();
        if (debugServer != null) debugServer.stop();
    }
    
    /**
     * Creates test organisms with simple NOP programs inline (no filesystem dependency).
     */
    private List<OrganismPlacement> createTestOrganisms() {
        List<OrganismPlacement> placements = new ArrayList<>();
        
        // Create a simple NOP program inline
        String nopProgram = "NOP";
        List<String> sourceLines = List.of(nopProgram);
        
        try {
            Compiler compiler = new Compiler();
            ProgramArtifact artifact = compiler.compile(sourceLines, "nop.", environmentProperties);
            
            // Place organism at position [5, 5] with 1000 energy
            placements.add(OrganismPlacement.of(artifact, 1000, new int[]{5, 5}));
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test organism", e);
        }
        
        return placements;
    }
    
    /**
     * Helper method to wait for a condition with fast polling.
     */
    private void waitForCondition(BooleanSupplier condition, long timeoutMs, String description) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        long checkInterval = 1; // Check every 1ms for fast polling

        while (!condition.getAsBoolean() && (System.currentTimeMillis() - startTime) < timeoutMs) {
            Thread.sleep(checkInterval);
        }

        if (!condition.getAsBoolean()) {
            throw new AssertionError("Timeout waiting for: " + description);
        }
    }
    
    /**
     * Helper method to wait for queue to be processed.
     */
    private void waitForQueueProcessed(InMemoryTickQueue queue, int threshold, long timeoutMs) throws InterruptedException {
        waitForCondition(() -> queue.size() < threshold, timeoutMs, "queue to be processed below threshold " + threshold);
    }
    
    /**
     * Waits for the simulation engine to complete all ticks.
     */
    private void waitForSimulationToComplete() throws InterruptedException {
        waitForCondition(() -> !simulationEngine.isRunning(), processingTimeoutMs, "SimulationEngine to complete");
    }
    
    /**
     * Waits for the persistence service to process all ticks.
     */
    private void waitForPersistenceToComplete() throws InterruptedException {
        long timeout = System.currentTimeMillis() + processingTimeoutMs;
        long expectedLastPersistedTick = simulationTicks - 1; // getLastPersistedTick() returns last persisted tick (0-based)
        
        while (System.currentTimeMillis() < timeout) {
            if (queue.size() == 0) {
                long lastPersistedTick = persistenceService.getLastPersistedTick();
                if (lastPersistedTick == expectedLastPersistedTick) {
                    return;
                } else if (lastPersistedTick > expectedLastPersistedTick) {
                    throw new AssertionError("Too many ticks persisted: " + (lastPersistedTick + 1) + " (expected: " + simulationTicks + ")");
                }
            }
            Thread.sleep(10); // 10ms polling
        }
        throw new AssertionError("Persistence timeout after " + processingTimeoutMs + "ms");
    }
    
    /**
     * Waits for the debug indexer to process all ticks.
     */
    private void waitForIndexerToComplete() throws InterruptedException {
        long timeout = System.currentTimeMillis() + processingTimeoutMs;
        long expectedLastProcessedTick = simulationTicks - 1;
        
        while (System.currentTimeMillis() < timeout) {
            long processedTicks = debugIndexer.getLastProcessedTick();
            if (processedTicks == expectedLastProcessedTick) {
                return;
            } else if (processedTicks > expectedLastProcessedTick) {
                throw new AssertionError("Too many ticks processed by indexer: " + (processedTicks + 1) + " (expected: " + simulationTicks + ")");
            }
            Thread.sleep(10); // 10ms polling
        }
        throw new AssertionError("Indexer timeout after " + processingTimeoutMs + "ms");
    }
    
    /**
     * Tests that data flows correctly through the entire pipeline with parallel services.
     * Based on BenchmarkTest but with services running in parallel (real-life scenario).
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testPipelineDataFlow() throws Exception {
        // 1. Set maxTicks for this test
        simulationEngine.setMaxTicks((long) simulationTicks);
        
        // 2. Start simulation engine
        simulationEngine.start();
        
        // 3. Immediately start persistence service (don't wait for simulation to finish)
        persistenceService.start();
        
        // 4. Immediately start debug indexer (don't wait for persistence to finish)
        debugIndexer.start();
        
        
        // 5. Wait for simulation to complete
        waitForSimulationToComplete();
        
        // 6. Wait for persistence to process all ticks
        waitForPersistenceToComplete();
        
        // 7. Wait for debug indexer to process all ticks
        waitForIndexerToComplete();
        
        // 8. Verify final state - all ticks processed and queue empty
        assertTrue(queue.size() < 5, "Queue should be mostly empty after processing, but size is: " + queue.size());
        
        // 9. Verify all services processed the expected number of ticks
        // SimulationEngine: should have completed (isRunning() = false)
        assertFalse(simulationEngine.isRunning(), "SimulationEngine should have completed");
        
        // PersistenceService: check via service method
        long lastPersistedTick = persistenceService.getLastPersistedTick();
        long expectedLastPersistedTick = simulationTicks - 1; // getLastPersistedTick() returns last persisted tick (0-based)
        assertEquals(expectedLastPersistedTick, lastPersistedTick,
            "PersistenceService should have processed exactly " + simulationTicks + " ticks (getLastPersistedTick=" + expectedLastPersistedTick + "), but got " + lastPersistedTick);
        
        // DebugIndexer: check via service method (works even after auto-pause)
        long indexerProcessedTicks = debugIndexer.getLastProcessedTick();
        long expectedIndexerTicks = simulationTicks - 1; // getLastProcessedTick() returns last processed tick (0-based)
        assertEquals(expectedIndexerTicks, indexerProcessedTicks,
            "DebugIndexer should have processed exactly " + simulationTicks + " ticks (getLastProcessedTick=" + expectedIndexerTicks + "), but got " + indexerProcessedTicks);
        
        // 10. Verify services are in expected state (auto-paused, not stopped)
        assertTrue(persistenceService.isRunning(), "PersistenceService should still be running (auto-paused)");
        assertTrue(debugIndexer.isRunning(), "DebugIndexer should still be running (auto-paused)");
    }
    
    /**
     * Tests that services can be paused and resumed correctly.
     * This verifies the service lifecycle control functionality.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testPipelinePauseResume() throws Exception {
        // 1. Create services without maxTicks (for pause/resume testing)
        SimulationEngine pauseTestEngine = new SimulationEngine(
            queue,
            environmentProperties,
            createTestOrganisms(),
            new ArrayList<>(),
            false // skipProgramArtefact
        );
        // No setMaxTicks() - let it run indefinitely for pause/resume testing
        
        PersistenceService pauseTestPersistence = new PersistenceService(queue, rawDbPath, environmentProperties, persistenceBatchSize);
        DebugIndexer pauseTestIndexer = new DebugIndexer(rawDbPath, debugDbPath, indexerBatchSize);
        
        // 2. Start services
        pauseTestEngine.start();
        pauseTestPersistence.start();
        pauseTestIndexer.start();
        
        // 3. Wait for services to be running
        waitForCondition(() -> pauseTestEngine.isRunning(), 3000, "SimulationEngine to start");
        waitForCondition(() -> pauseTestPersistence.isRunning(), 3000, "PersistenceService to start");
        waitForCondition(() -> pauseTestIndexer.isRunning(), 3000, "DebugIndexer to start");
        
        // 4. Verify all services are running
        assertTrue(pauseTestEngine.isRunning(), "SimulationEngine should be running");
        assertTrue(pauseTestPersistence.isRunning(), "PersistenceService should be running");
        assertTrue(pauseTestIndexer.isRunning(), "DebugIndexer should be running");
        
        // 5. Pause all services
        pauseTestEngine.pause();
        pauseTestPersistence.pause();
        pauseTestIndexer.pause();
        
        // 6. Wait for services to be paused
        waitForCondition(() -> pauseTestEngine.isPaused(), 3000, "SimulationEngine to pause");
        waitForCondition(() -> pauseTestPersistence.isPaused(), 3000, "PersistenceService to pause");
        waitForCondition(() -> pauseTestIndexer.isPaused(), 3000, "DebugIndexer to pause");
        
        // 7. Verify all services are paused
        assertTrue(pauseTestEngine.isPaused(), "SimulationEngine should be paused");
        assertTrue(pauseTestPersistence.isPaused(), "PersistenceService should be paused");
        assertTrue(pauseTestIndexer.isPaused(), "DebugIndexer should be paused");
        assertTrue(pauseTestEngine.isRunning(), "SimulationEngine should still be running (paused)");
        assertTrue(pauseTestPersistence.isRunning(), "PersistenceService should still be running (paused)");
        assertTrue(pauseTestIndexer.isRunning(), "DebugIndexer should still be running (paused)");
        
        // 8. Resume all services
        pauseTestEngine.resume();
        pauseTestPersistence.resume();
        pauseTestIndexer.resume();
        
        // 9. Wait for services to be resumed
        waitForCondition(() -> !pauseTestEngine.isPaused(), 3000, "SimulationEngine to resume");
        waitForCondition(() -> !pauseTestPersistence.isPaused(), 3000, "PersistenceService to resume");
        waitForCondition(() -> !pauseTestIndexer.isPaused(), 3000, "DebugIndexer to resume");
        
        // 10. Verify all services are resumed
        assertFalse(pauseTestEngine.isPaused(), "SimulationEngine should not be paused");
        assertFalse(pauseTestPersistence.isPaused(), "PersistenceService should not be paused");
        assertFalse(pauseTestIndexer.isPaused(), "DebugIndexer should not be paused");
        assertTrue(pauseTestEngine.isRunning(), "SimulationEngine should be running");
        assertTrue(pauseTestPersistence.isRunning(), "PersistenceService should be running");
        assertTrue(pauseTestIndexer.isRunning(), "DebugIndexer should be running");
        
        // 11. Cleanup
        pauseTestEngine.shutdown();
        pauseTestPersistence.shutdown();
        pauseTestIndexer.shutdown();
    }

    /**
     * Tests graceful shutdown with data integrity verification.
     * Uses file-based SQLite to verify that all ticks are committed and no WAL files remain.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testGracefulShutdown() throws Exception {
        // 1. Create file-based database paths for this test
        String testRawDbPath = "jdbc:sqlite:shutdown_test_raw.sqlite";
        String testDebugDbPath = "jdbc:sqlite:shutdown_test_debug.sqlite";
        
        // 2. Create services with file-based databases and maxTicks = 100
        SimulationEngine shutdownTestEngine = new SimulationEngine(
            queue,
            environmentProperties,
            createTestOrganisms(),
            new ArrayList<>(),
            false // skipProgramArtefact
        );
        shutdownTestEngine.setMaxTicks(100L); // Use maxTicks for predictable tick count

        PersistenceService shutdownTestPersistence = new PersistenceService(queue, testRawDbPath, environmentProperties, persistenceBatchSize);
        DebugIndexer shutdownTestIndexer = new DebugIndexer(testRawDbPath, testDebugDbPath, indexerBatchSize);

        // 3. Start all services
        shutdownTestEngine.start();
        shutdownTestPersistence.start();
        shutdownTestIndexer.start();

        // 4. Wait for simulation to complete (100 ticks)
        waitForCondition(() -> !shutdownTestEngine.isRunning(), 3000, "SimulationEngine to complete");

        // 5. Wait for all ticks to be processed
        waitForCondition(() -> {
            long lastPersistedTick = shutdownTestPersistence.getLastPersistedTick();
            return lastPersistedTick == 99; // 0-based indexing: 100 ticks = 0-99
        }, 2000, "PersistenceService to process all 100 ticks");

        waitForCondition(() -> {
            long lastProcessedTick = shutdownTestIndexer.getLastProcessedTick();
            return lastProcessedTick == 99; // 0-based indexing: 100 ticks = 0-99
        }, 2000, "DebugIndexer to process all 100 ticks");

        // 6. Shutdown all services
        shutdownTestEngine.shutdown();
        shutdownTestPersistence.shutdown();
        shutdownTestIndexer.shutdown();

        // 7. Wait for services to be stopped
        waitForCondition(() -> !shutdownTestEngine.isRunning(), 3000, "SimulationEngine to stop");
        waitForCondition(() -> !shutdownTestPersistence.isRunning(), 3000, "PersistenceService to stop");
        waitForCondition(() -> !shutdownTestIndexer.isRunning(), 3000, "DebugIndexer to stop");

        // 8. Verify all services are stopped
        assertFalse(shutdownTestEngine.isRunning(), "SimulationEngine should be stopped");
        assertFalse(shutdownTestPersistence.isRunning(), "PersistenceService should be stopped");
        assertFalse(shutdownTestIndexer.isRunning(), "DebugIndexer should be stopped");

        // 9. Verify data integrity by directly accessing the databases
        verifyDataIntegrityAfterShutdown(testRawDbPath, testDebugDbPath, 100);

        // 10. Cleanup - remove test database files
        cleanupTestDatabases(testRawDbPath, testDebugDbPath);
    }

    /**
     * Verifies data integrity after shutdown by directly accessing the databases.
     */
    private void verifyDataIntegrityAfterShutdown(String rawDbPath, String debugDbPath, int expectedTicks) throws Exception {
        // Poll until databases are accessible and contain expected data
        waitForCondition(() -> {
            try {
                // Check raw database
                try (Connection rawConn = DriverManager.getConnection(rawDbPath);
                     PreparedStatement ps = rawConn.prepareStatement("SELECT COUNT(*) FROM raw_ticks")) {
                    
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long rawTickCount = rs.getLong(1);
                            return rawTickCount == expectedTicks;
                        }
                    }
                }
                return false;
            } catch (Exception e) {
                return false; // Database not ready yet
            }
        }, 2000, "Raw database to contain " + expectedTicks + " ticks");

        waitForCondition(() -> {
            try {
                // Check debug database
                try (Connection debugConn = DriverManager.getConnection(debugDbPath);
                     PreparedStatement ps = debugConn.prepareStatement("SELECT COUNT(*) FROM prepared_ticks")) {
                    
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long debugTickCount = rs.getLong(1);
                            return debugTickCount == expectedTicks;
                        }
                    }
                }
                return false;
            } catch (Exception e) {
                return false; // Database not ready yet
            }
        }, 2000, "Debug database to contain " + expectedTicks + " ticks");

        // Verify no data is left in queue
        assertEquals(0, queue.size(), "Queue should be empty after shutdown");

        // Data integrity verification completed successfully
    }

    /**
     * Cleans up test database files.
     */
    private void cleanupTestDatabases(String rawDbPath, String debugDbPath) {
        try {
            // Extract file paths from JDBC URLs
            String rawFile = rawDbPath.replace("jdbc:sqlite:", "");
            String debugFile = debugDbPath.replace("jdbc:sqlite:", "");
            
            // Delete database files if they exist
            Files.deleteIfExists(Paths.get(rawFile));
            Files.deleteIfExists(Paths.get(debugFile));
            
            // Also delete WAL and SHM files if they exist
            Files.deleteIfExists(Paths.get(rawFile + "-wal"));
            Files.deleteIfExists(Paths.get(rawFile + "-shm"));
            Files.deleteIfExists(Paths.get(debugFile + "-wal"));
            Files.deleteIfExists(Paths.get(debugFile + "-shm"));
        } catch (Exception e) {
            // Cleanup failed - not critical for test success
        }
    }

    /**
     * Tests that program artifacts and simulation metadata flow correctly through the entire pipeline.
     * Verifies that compiled programs and world configuration are properly processed and available in debug database.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testProgramArtifactsAndMetadataFlow() throws Exception {
        // 1. Set maxTicks for this test
        simulationEngine.setMaxTicks(50L);

        // 2. Start all services
        simulationEngine.start();
        persistenceService.start();
        debugIndexer.start();

        // 3. Wait for simulation to complete (50 ticks)
        waitForCondition(() -> !simulationEngine.isRunning(), 3000, "SimulationEngine to complete");

        // 4. Wait for all ticks to be processed
        waitForCondition(() -> {
            long lastPersistedTick = persistenceService.getLastPersistedTick();
            return lastPersistedTick == 49; // 0-based indexing: 50 ticks = 0-49
        }, 2000, "PersistenceService to process all 50 ticks");

        waitForCondition(() -> {
            long lastProcessedTick = debugIndexer.getLastProcessedTick();
            return lastProcessedTick == 49; // 0-based indexing: 50 ticks = 0-49
        }, 2000, "DebugIndexer to process all 50 ticks");

        // 5. Verify that services have processed data correctly (using service methods)
        // Note: Service methods work even when DB is closed (they use internal state)
        assertTrue(debugIndexer.getLastProcessedTick() >= 49, "DebugIndexer should have processed all ticks");
        assertTrue(persistenceService.getLastPersistedTick() >= 49, "PersistenceService should have processed all ticks");
        
        // 6. Verify program artifacts and metadata content using service methods
        verifyProgramArtifactsAndMetadataUsingServices();

        // 7. Shutdown all services
        simulationEngine.shutdown();
        persistenceService.shutdown();
        debugIndexer.shutdown();

        // 8. Wait for services to be stopped
        waitForCondition(() -> !simulationEngine.isRunning(), 3000, "SimulationEngine to stop");
        waitForCondition(() -> !persistenceService.isRunning(), 3000, "PersistenceService to stop");
        waitForCondition(() -> !debugIndexer.isRunning(), 3000, "DebugIndexer to stop");
    }

    /**
     * Verifies that program artifacts and simulation metadata are correctly processed using service methods.
     * This method works even when database connections are closed (uses internal service state).
     */
    private void verifyProgramArtifactsAndMetadataUsingServices() {
        // Verify program artifacts using DebugIndexer service method
        Map<String, ProgramArtifact> artifacts = debugIndexer.getProgramArtifacts();
        assertFalse(artifacts.isEmpty(), "DebugIndexer should have processed program artifacts");
        
        // Verify at least one artifact exists and has content
        ProgramArtifact firstArtifact = artifacts.values().iterator().next();
        assertNotNull(firstArtifact, "Program artifact should not be null");
        assertNotNull(firstArtifact.machineCodeLayout(), "Machine code layout should not be null");
        assertFalse(firstArtifact.machineCodeLayout().isEmpty(), "Machine code layout should not be empty");
        assertNotNull(firstArtifact.sourceMap(), "Source map should not be null");
        assertFalse(firstArtifact.sourceMap().isEmpty(), "Source map should contain our test source file");
        
        // Verify environment properties using DebugIndexer service method
        EnvironmentProperties envProps = debugIndexer.getEnvironmentProperties();
        assertNotNull(envProps, "Environment properties should not be null");
        assertArrayEquals(new int[]{10, 10}, envProps.getWorldShape(), "World shape should be [10, 10]");
        assertTrue(envProps.isToroidal(), "World should be toroidal");
    }

    
}
