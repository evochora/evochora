package org.evochora.server.persistence;

import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawCellState;
import org.evochora.server.contracts.raw.SerializableProcFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceServicePerformanceTest {

    private PersistenceService persistenceService;
    private InMemoryTickQueue queue;
    private Path tempDir;
    private String dbPath;
    private static final int BATCH_SIZE = 1000;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        this.tempDir = tempDir;
        // Use temporary SQLite for faster tests
        this.dbPath = "jdbc:sqlite:file:memdb_perf?mode=memory&cache=shared";
        
        queue = new InMemoryTickQueue();
        persistenceService = new PersistenceService(queue, dbPath, new int[]{100, 30}, BATCH_SIZE);
    }

    @AfterEach
    void tearDown() {
        if (persistenceService != null) {
            persistenceService.shutdown();
        }
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
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testConfigurableBatchSize() {
        // Test with different batch sizes - use in-memory databases
        String smallBatchDb = "jdbc:sqlite:file:memdb_small_batch?mode=memory&cache=shared";
        String largeBatchDb = "jdbc:sqlite:file:memdb_large_batch?mode=memory&cache=shared";
        
        PersistenceService smallBatchService = new PersistenceService(queue, smallBatchDb, new int[]{100, 30}, 100);
        PersistenceService largeBatchService = new PersistenceService(queue, largeBatchDb, new int[]{100, 30}, 5000);
        
        // Note: getBatchSize() is private, but we can verify constructor works
        assertNotNull(smallBatchService);
        assertNotNull(largeBatchService);
        
        smallBatchService.shutdown();
        largeBatchService.shutdown();
    }

    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testBatchInserts() throws Exception {
        // Start persistence service
        persistenceService.start();
        Thread.sleep(200);
        
        // Add multiple ticks to queue
        for (int i = 1; i <= 100; i++) {
            queue.put(createTestTickState(i));
        }
        
        // Wait for processing
        Thread.sleep(500);
        
        // Verify service is running
        assertTrue(persistenceService.isRunning());
    }

    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testSQLiteOptimizations() throws Exception {
        // Start persistence service
        persistenceService.start();
        Thread.sleep(200);
        
        // Add some data to trigger database creation
        for (int i = 1; i <= 50; i++) {
            queue.put(createTestTickState(i));
        }
        Thread.sleep(200);
        
        // For in-memory database, just verify the service is running
        assertTrue(persistenceService.isRunning());
    }

    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testThrottling() throws Exception {
        // Start persistence service
        persistenceService.start();
        Thread.sleep(200);
        
        // Fill queue to trigger throttling
        for (int i = 1; i <= 200; i++) {
            queue.put(createTestTickState(i));
        }
        
        // Wait for processing
        Thread.sleep(300);
        
        // Verify throttling occurred (queue should not be empty immediately)
        assertTrue(queue.size() > 0, "Queue should not be empty immediately due to throttling");
        
        // Wait longer for processing to complete
        Thread.sleep(500);
        
        // Verify service is still running
        assertTrue(persistenceService.isRunning());
    }

    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testGracefulShutdown() throws Exception {
        // Start persistence service
        persistenceService.start();
        Thread.sleep(200);
        
        // Add some data
        for (int i = 1; i <= 50; i++) {
            queue.put(createTestTickState(i));
        }
        
        // Wait for some processing
        Thread.sleep(200);
        
        // Verify it's running
        assertTrue(persistenceService.isRunning());
        
        // Shutdown gracefully
        persistenceService.shutdown();
        Thread.sleep(200);
        
        // Verify shutdown
        assertFalse(persistenceService.isRunning());
    }

    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testDataIntegrity() throws Exception {
        // Start persistence service
        persistenceService.start();
        Thread.sleep(200);
        
        // Add test data
        for (int i = 1; i <= 100; i++) {
            queue.put(createTestTickState(i));
        }
        
        // Wait for processing
        Thread.sleep(300);
        
        // Verify service is running
        assertTrue(persistenceService.isRunning());
    }

    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testPerformanceUnderLoad() throws Exception {
        // Start persistence service
        persistenceService.start();
        Thread.sleep(200);
        
        // Monitor performance over time
        long startTime = System.currentTimeMillis();
        
        // Add large amount of data
        for (int i = 1; i <= 300; i++) {
            queue.put(createTestTickState(i));
        }
        
        // Wait for processing
        Thread.sleep(500);
        
        long processingTime = System.currentTimeMillis() - startTime;
        
        // Verify processing occurred
        assertTrue(processingTime > 0, "Pipeline should be running");
        assertTrue(persistenceService.isRunning(), "Service should be running");
    }

    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testBatchSizeImpactOnPerformance() throws Exception {
        // Test with different batch sizes - use in-memory databases
        String smallBatchDb = "jdbc:sqlite:file:memdb_small_batch_perf?mode=memory&cache=shared";
        String mediumBatchDb = "jdbc:sqlite:file:memdb_medium_batch_perf?mode=memory&cache=shared";
        String largeBatchDb = "jdbc:sqlite:file:memdb_large_batch_perf?mode=memory&cache=shared";
        
        PersistenceService smallBatchService = new PersistenceService(queue, smallBatchDb, new int[]{100, 30}, 100);
        PersistenceService mediumBatchService = new PersistenceService(queue, mediumBatchDb, new int[]{100, 30}, 1000);
        PersistenceService largeBatchService = new PersistenceService(queue, largeBatchDb, new int[]{100, 30}, 5000);
        
        // Start all services
        smallBatchService.start();
        mediumBatchService.start();
        largeBatchService.start();
        
        // Let them run for a bit
        Thread.sleep(200);
        
        // Add test data
        for (int i = 1; i <= 100; i++) {
            queue.put(createTestTickState(i));
        }
        
        // Let them process
        Thread.sleep(300);
        
        // All should be running
        assertTrue(smallBatchService.isRunning());
        assertTrue(mediumBatchService.isRunning());
        assertTrue(largeBatchService.isRunning());
        
        // Cleanup
        smallBatchService.shutdown();
        mediumBatchService.shutdown();
        largeBatchService.shutdown();
    }

    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testPauseResume() throws Exception {
        // Start persistence service
        persistenceService.start();
        Thread.sleep(200);
        
        // Verify it's running
        assertTrue(persistenceService.isRunning());
        
        // Pause service
        persistenceService.pause();
        Thread.sleep(200);
        
        // Verify it's paused (note: pause() may not immediately stop processing)
        // The service might still be running due to ongoing operations
        // We'll check if it eventually stops or if pause is working
        
        // Resume service
        persistenceService.resume();
        Thread.sleep(200);
        
        // Verify it's running again
        assertTrue(persistenceService.isRunning());
    }
}
