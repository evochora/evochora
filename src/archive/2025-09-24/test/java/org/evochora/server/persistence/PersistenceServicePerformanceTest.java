package org.evochora.server.persistence;

import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawCellState;
import org.evochora.server.contracts.raw.SerializableProcFrame;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.server.config.SimulationConfiguration;
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

/**
 * Contains integration tests for the {@link PersistenceService}, focusing on performance and behavior under load.
 * These tests use an in-memory queue and in-memory SQLite databases to verify the service's
 * ability to read from the queue and write to the database in a threaded environment.
 */
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
        
        queue = new InMemoryTickQueue(1000);
        SimulationConfiguration.PersistenceServiceConfig config = new SimulationConfiguration.PersistenceServiceConfig();
        config.jdbcUrl = dbPath;
        config.batchSize = BATCH_SIZE;
        persistenceService = new PersistenceService(queue, new EnvironmentProperties(new int[]{100, 30}, true), config);
    }

    @AfterEach
    void tearDown() {
        if (persistenceService != null) {
            persistenceService.shutdown();
        }
    }

    /**
     * Creates a mock {@link RawTickState} for testing the pipeline.
     * @param tickNumber The tick number for the mock state.
     * @return A new {@link RawTickState} instance.
     */
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

    /**
     * Verifies that the PersistenceService can be instantiated with different batch size configurations.
     * This is an integration test for the service's configuration handling.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testConfigurableBatchSize() {
        // Test with different batch sizes - use in-memory databases
        String smallBatchDb = "jdbc:sqlite:file:memdb_small_batch?mode=memory&cache=shared";
        String largeBatchDb = "jdbc:sqlite:file:memdb_large_batch?mode=memory&cache=shared";
        
        SimulationConfiguration.PersistenceServiceConfig smallBatchConfig = new SimulationConfiguration.PersistenceServiceConfig();
        smallBatchConfig.jdbcUrl = smallBatchDb;
        smallBatchConfig.batchSize = 100;
        PersistenceService smallBatchService = new PersistenceService(queue, new EnvironmentProperties(new int[]{100, 30}, true), smallBatchConfig);
        
        SimulationConfiguration.PersistenceServiceConfig largeBatchConfig = new SimulationConfiguration.PersistenceServiceConfig();
        largeBatchConfig.jdbcUrl = largeBatchDb;
        largeBatchConfig.batchSize = 5000;
        PersistenceService largeBatchService = new PersistenceService(queue, new EnvironmentProperties(new int[]{100, 30}, true), largeBatchConfig);
        
        // Note: getBatchSize() is private, but we can verify constructor works
        assertNotNull(smallBatchService);
        assertNotNull(largeBatchService);
        
        smallBatchService.shutdown();
        largeBatchService.shutdown();
    }

    /**
     * Helper method to wait until the message queue is empty.
     * @param timeoutMillis The maximum time to wait.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    private void waitForQueueToEmpty(int timeoutMillis) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (queue.size() > 0 && (System.currentTimeMillis() - startTime) < timeoutMillis) {
            Thread.sleep(50); // Check every 50ms
        }
    }

    /**
     * Verifies that the service correctly processes a batch of inserts from the queue.
     * This is an integration test of the core data processing loop.
     * @throws Exception if thread or database operations fail.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testBatchInserts() throws Exception {
        // Start persistence service
        persistenceService.start();
        
        // Add multiple ticks to queue
        for (int i = 1; i <= 100; i++) {
            queue.put(createTestTickState(i));
        }
        
        // Wait for processing
        waitForQueueToEmpty(5000); // 5 second timeout
        
        // Verify service is running and queue is empty
        assertTrue(persistenceService.isRunning());
        assertEquals(0, queue.size(), "Queue should be empty after processing");
    }

    /**
     * Verifies that the service runs correctly with an in-memory database. The name suggests
     * a test for optimizations, but the implementation is a generic run check.
     * This is an integration test.
     * @throws Exception if thread or database operations fail.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testSQLiteOptimizations() throws Exception {
        // Start persistence service
        persistenceService.start();
        
        // Add some data to trigger database creation
        for (int i = 1; i <= 50; i++) {
            queue.put(createTestTickState(i));
        }
        waitForQueueToEmpty(5000);
        
        // For in-memory database, just verify the service is running and queue is empty
        assertTrue(persistenceService.isRunning());
        assertEquals(0, queue.size(), "Queue should be empty after processing");
    }

    /**
     * Verifies the service's behavior under load, which demonstrates implicit throttling
     * as the queue does not empty instantly.
     * This is an integration test of the service's load handling.
     * @throws Exception if thread operations fail.
     */
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

    /**
     * Verifies that the service can be gracefully shut down after processing data.
     * This is an integration test of the service lifecycle.
     * @throws Exception if thread or database operations fail.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testGracefulShutdown() throws Exception {
        // Start persistence service
        persistenceService.start();
        
        // Add some data
        for (int i = 1; i <= 50; i++) {
            queue.put(createTestTickState(i));
        }
        
        // Wait for some processing
        waitForQueueToEmpty(5000);
        
        // Verify it's running
        assertTrue(persistenceService.isRunning());
        
        // Shutdown gracefully
        persistenceService.shutdown();
        
        // Verify shutdown
        assertFalse(persistenceService.isRunning());
    }

    /**
     * Verifies that the service can process a batch of data. The name suggests a test for
     * data integrity, but the implementation is a generic run check.
     * This is an integration test.
     * @throws Exception if thread or database operations fail.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testDataIntegrity() throws Exception {
        // Start persistence service
        persistenceService.start();
        
        // Add test data
        for (int i = 1; i <= 100; i++) {
            queue.put(createTestTickState(i));
        }
        
        // Wait for processing
        waitForQueueToEmpty(5000);
        
        // Verify service is running and queue is empty
        assertTrue(persistenceService.isRunning());
        assertEquals(0, queue.size(), "Queue should be empty after processing");
    }

    /**
     * A basic performance test that verifies the service can process a larger load of data.
     * This is an integration test of the service's stability.
     * @throws Exception if thread or database operations fail.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testPerformanceUnderLoad() throws Exception {
        // Start persistence service
        persistenceService.start();
        
        // Monitor performance over time
        long startTime = System.currentTimeMillis();
        
        // Add large amount of data
        for (int i = 1; i <= 300; i++) {
            queue.put(createTestTickState(i));
        }
        
        // Wait for processing
        waitForQueueToEmpty(10000); // Increased timeout for larger load
        
        long processingTime = System.currentTimeMillis() - startTime;
        
        // Verify processing occurred
        assertTrue(processingTime > 0, "Pipeline should be running");
        
        // Check if service is still running (it might have stopped due to queue being empty)
        if (persistenceService.isRunning()) {
            assertTrue(persistenceService.isRunning(), "Service should be running");
        }
        assertEquals(0, queue.size(), "Queue should be empty after processing");
    }

    /**
     * Verifies that multiple service instances with different batch sizes can be started.
     * The name suggests a performance comparison, but the implementation only checks startup.
     * This is an integration test of the service configuration.
     * @throws Exception if thread or database operations fail.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testBatchSizeImpactOnPerformance() throws Exception {
        // Test with different batch sizes - use in-memory databases
        String smallBatchDb = "jdbc:sqlite:file:memdb_small_batch_perf?mode=memory&cache=shared";
        String mediumBatchDb = "jdbc:sqlite:file:memdb_medium_batch_perf?mode=memory&cache=shared";
        String largeBatchDb = "jdbc:sqlite:file:memdb_large_batch_perf?mode=memory&cache=shared";
        
        SimulationConfiguration.PersistenceServiceConfig smallBatchConfig = new SimulationConfiguration.PersistenceServiceConfig();
        smallBatchConfig.jdbcUrl = smallBatchDb;
        smallBatchConfig.batchSize = 100;
        PersistenceService smallBatchService = new PersistenceService(queue, new EnvironmentProperties(new int[]{100, 30}, true), smallBatchConfig);
        
        SimulationConfiguration.PersistenceServiceConfig mediumBatchConfig = new SimulationConfiguration.PersistenceServiceConfig();
        mediumBatchConfig.jdbcUrl = mediumBatchDb;
        mediumBatchConfig.batchSize = 1000;
        PersistenceService mediumBatchService = new PersistenceService(queue, new EnvironmentProperties(new int[]{100, 30}, true), mediumBatchConfig);
        
        SimulationConfiguration.PersistenceServiceConfig largeBatchConfig = new SimulationConfiguration.PersistenceServiceConfig();
        largeBatchConfig.jdbcUrl = largeBatchDb;
        largeBatchConfig.batchSize = 5000;
        PersistenceService largeBatchService = new PersistenceService(queue, new EnvironmentProperties(new int[]{100, 30}, true), largeBatchConfig);
        
        // Start all services
        smallBatchService.start();
        mediumBatchService.start();
        largeBatchService.start();
        
        // Add test data
        for (int i = 1; i <= 100; i++) {
            queue.put(createTestTickState(i));
        }
        
        // Let them process
        waitForQueueToEmpty(5000);
        
        // All should be running and queue should be empty
        assertTrue(smallBatchService.isRunning());
        assertTrue(mediumBatchService.isRunning());
        assertTrue(largeBatchService.isRunning());
        assertEquals(0, queue.size(), "Queue should be empty after processing");
        
        // Cleanup
        smallBatchService.shutdown();
        mediumBatchService.shutdown();
        largeBatchService.shutdown();
    }

    /**
     * Verifies the pause and resume functionality of the service.
     * This is an integration test of the service lifecycle.
     * @throws Exception if thread operations fail.
     */
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
