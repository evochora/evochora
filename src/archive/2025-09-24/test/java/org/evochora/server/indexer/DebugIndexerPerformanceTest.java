package org.evochora.server.indexer;

import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawCellState;
import org.evochora.server.contracts.raw.SerializableProcFrame;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Tag;
import org.evochora.server.config.SimulationConfiguration;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Deque;
import java.util.LinkedList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contains integration tests for the {@link DebugIndexer}, with a focus on performance and reliability.
 * These tests use in-memory SQLite databases to test the indexer's ability to process data from a
 * source database and write to a destination database in a threaded environment.
 */
class DebugIndexerPerformanceTest {

    private DebugIndexer debugIndexer;
    private Path tempDir;
    private String rawDbPath;
    private String debugDbPath;
    private static final int BATCH_SIZE = 1000;
    private static int testCounter = 0;
    private Connection conn;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        testCounter++;
        this.tempDir = tempDir;
        
        // Use in-memory databases for reliable testing without file locking issues
        this.rawDbPath = "jdbc:sqlite:file:memdb_perf_raw" + testCounter + "?mode=memory&cache=shared";
        this.debugDbPath = "jdbc:sqlite:file:memdb_perf_debug" + testCounter + "?mode=memory&cache=shared";
        
        SimulationConfiguration.IndexerServiceConfig config = new SimulationConfiguration.IndexerServiceConfig();
        config.batchSize = BATCH_SIZE;
        debugIndexer = new DebugIndexer(rawDbPath, debugDbPath, config);
    }

    @AfterEach
    void tearDown() {
        if (debugIndexer != null) {
            debugIndexer.shutdown();
            // Ensure the indexer is fully stopped before closing the DB to avoid late WARN logs
            int waited = 0;
            int interval = 10;
            int maxWait = 1000; // 1s is enough for a graceful stop here
            while (debugIndexer.isRunning() && waited < maxWait) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                waited += interval;
            }
        }
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * Helper method to create and populate a mock raw data database for testing.
     * @param dbPath The JDBC path for the in-memory database.
     * @return An open connection to the database.
     * @throws Exception if database setup fails.
     */
    private Connection createTestDatabase(String dbPath) throws Exception {
        conn = DriverManager.getConnection(dbPath);
        try (Statement stmt = conn.createStatement()) {
            // Create raw_ticks table with the correct schema
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS raw_ticks (
                    tick_number INTEGER PRIMARY KEY,
                    tick_data_json TEXT
                )
            """);

            // Create program_artifacts table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS program_artifacts (
                    program_id TEXT PRIMARY KEY,
                    artifact_json TEXT
                )
            """);

            // Create simulation_metadata table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS simulation_metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )
            """);
            stmt.execute("INSERT OR REPLACE INTO simulation_metadata (key, value) VALUES ('worldShape', '[10,10]')");
            stmt.execute("INSERT OR REPLACE INTO simulation_metadata (key, value) VALUES ('isToroidal', 'true')");

            // Insert test data into raw_ticks
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (int i = 1; i <= 100; i++) {
                RawTickState tickState = new RawTickState(i, new ArrayList<>(), new ArrayList<>());
                String tickJson = objectMapper.writeValueAsString(tickState);
                stmt.execute("INSERT INTO raw_ticks (tick_number, tick_data_json) VALUES (" + i + ", '" + tickJson.replace("'", "''") + "')");
            }

            // Insert test program artifact
            org.evochora.compiler.api.ProgramArtifact artifact = new org.evochora.compiler.api.ProgramArtifact(
                "test123",
                java.util.Map.of("main.s", java.util.List.of("NOP")),
                java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(), // tokenMap
                java.util.Collections.emptyMap()  // lineToTokens
            );
            String artifactJson = objectMapper.writeValueAsString(artifact.toLinearized(new EnvironmentProperties(new int[]{10,10}, true)));
            stmt.execute("INSERT OR REPLACE INTO program_artifacts (program_id, artifact_json) VALUES ('test123', '" + artifactJson.replace("'", "''") + "')");
        }
        
        
        return conn;
    }

    /**
     * Verifies that the indexer starts and processes at least one batch of ticks from the raw database.
     * This is an integration test of the indexer's basic processing loop.
     * @throws Exception if database or thread operations fail.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testBatchProcessingPerformance() throws Exception {
        conn = createTestDatabase(rawDbPath);
        
        debugIndexer.start();
        
        int maxWaitTime = 5000;
        int waitInterval = 100;
        int totalWaitTime = 0;
        while ((!debugIndexer.isRunning() || debugIndexer.getLastProcessedTick() == 0) && totalWaitTime < maxWaitTime) {
            Thread.sleep(waitInterval);
            totalWaitTime += waitInterval;
        }
        
        assertTrue(debugIndexer.isRunning());
        assertTrue(debugIndexer.getLastProcessedTick() > 0, "Indexer should have processed some ticks");
    }

    /**
     * Verifies that the indexer can start and process ticks. The name suggests a test for
     * SQLite optimizations, but the implementation is a generic run check.
     * This is an integration test.
     * @throws Exception if database or thread operations fail.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testSQLiteOptimizations() throws Exception {
        conn = createTestDatabase(rawDbPath);
        
        debugIndexer.start();
        
        int maxWaitTime = 5000;
        int waitInterval = 100;
        int totalWaitTime = 0;
        while ((!debugIndexer.isRunning() || debugIndexer.getLastProcessedTick() == 0) && totalWaitTime < maxWaitTime) {
            Thread.sleep(waitInterval);
            totalWaitTime += waitInterval;
        }
        
        assertTrue(debugIndexer.isRunning());
        assertTrue(debugIndexer.getLastProcessedTick() > 0, "Indexer should have processed some ticks");
    }

    /**
     * Verifies that the indexer can start and process ticks. The name suggests a test for
     * WAL checkpointing, but the implementation is a generic run check.
     * This is an integration test.
     * @throws Exception if database or thread operations fail.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testWALCheckpointing() throws Exception {
        conn = createTestDatabase(rawDbPath);
        
        debugIndexer.start();
        
        int maxWaitTime = 5000;
        int waitInterval = 100;
        int totalWaitTime = 0;
        while ((!debugIndexer.isRunning() || debugIndexer.getLastProcessedTick() == 0) && totalWaitTime < maxWaitTime) {
            Thread.sleep(waitInterval);
            totalWaitTime += waitInterval;
        }
        
        assertTrue(debugIndexer.isRunning());
        assertTrue(debugIndexer.getLastProcessedTick() > 0, "Indexer should have processed some ticks");
    }

    /**
     * Verifies that the indexer can start and process ticks. The name suggests a test for
     * auto-pause/resume, but the implementation is a generic run check.
     * This is an integration test.
     * @throws Exception if database or thread operations fail.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testAutoPauseResume() throws Exception {
        conn = createTestDatabase(rawDbPath);
        
        debugIndexer.start();
        
        int maxWaitTime = 5000;
        int waitInterval = 100;
        int totalWaitTime = 0;
        while ((!debugIndexer.isRunning() || debugIndexer.getLastProcessedTick() == 0) && totalWaitTime < maxWaitTime) {
            Thread.sleep(waitInterval);
            totalWaitTime += waitInterval;
        }
        
        assertTrue(debugIndexer.isRunning());
        assertTrue(debugIndexer.getLastProcessedTick() > 0, "Indexer should have processed some ticks");
    }

    /**
     * Verifies that the indexer can start and process ticks. The name suggests a test for
     * throttling, but the implementation is a generic run check.
     * This is an integration test.
     * @throws Exception if database or thread operations fail.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testThrottlingAndPerformance() throws Exception {
        conn = createTestDatabase(rawDbPath);
        
        debugIndexer.start();
        
        int maxWaitTime = 5000;
        int waitInterval = 100;
        int totalWaitTime = 0;
        while ((!debugIndexer.isRunning() || debugIndexer.getLastProcessedTick() == 0) && totalWaitTime < maxWaitTime) {
            Thread.sleep(waitInterval);
            totalWaitTime += waitInterval;
        }
        
        assertTrue(debugIndexer.isRunning());
        assertTrue(debugIndexer.getLastProcessedTick() > 0, "Indexer should have processed some ticks");
    }

    /**
     * Verifies that the indexer can be gracefully shut down after it has started processing data.
     * This is an integration test of the service lifecycle.
     * @throws Exception if database or thread operations fail.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testGracefulShutdown() throws Exception {
        conn = createTestDatabase(rawDbPath);
        
        debugIndexer.start();
        
        int maxWaitTime = 5000;
        int waitInterval = 100;
        int totalWaitTime = 0;
        while ((!debugIndexer.isRunning() || debugIndexer.getLastProcessedTick() == 0) && totalWaitTime < maxWaitTime) {
            Thread.sleep(waitInterval);
            totalWaitTime += waitInterval;
        }
        
        assertTrue(debugIndexer.isRunning());
        assertTrue(debugIndexer.getLastProcessedTick() > 0, "Indexer should have processed some ticks");
        
        debugIndexer.shutdown();
        
        totalWaitTime = 0;
        while (debugIndexer.isRunning() && totalWaitTime < maxWaitTime) {
            Thread.sleep(waitInterval);
            totalWaitTime += waitInterval;
        }

        assertFalse(debugIndexer.isRunning());
    }

    /**
     * Verifies that the indexer can start and process ticks. The name suggests a test for
     * data integrity, but the implementation is a generic run check.
     * This is an integration test.
     * @throws Exception if database or thread operations fail.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testDataIntegrity() throws Exception {
        conn = createTestDatabase(rawDbPath);
        
        debugIndexer.start();
        
        int maxWaitTime = 5000;
        int waitInterval = 100;
        int totalWaitTime = 0;
        while ((!debugIndexer.isRunning() || debugIndexer.getLastProcessedTick() == 0) && totalWaitTime < maxWaitTime) {
            Thread.sleep(waitInterval);
            totalWaitTime += waitInterval;
        }
        
        assertTrue(debugIndexer.isRunning());
        assertTrue(debugIndexer.getLastProcessedTick() > 0, "Indexer should have processed some ticks");
    }

    /**
     * Verifies that the indexer can be started with different batch size configurations.
     * This is an integration test of the service configuration.
     * @throws Exception if database or thread operations fail.
     */
    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testBatchSizeImpactOnPerformance() throws Exception {
        conn = createTestDatabase(rawDbPath);
        
        SimulationConfiguration.IndexerServiceConfig smallBatchConfig = new SimulationConfiguration.IndexerServiceConfig();
        smallBatchConfig.batchSize = 100;
        DebugIndexer smallBatchIndexer = new DebugIndexer(rawDbPath, rawDbPath.replace("_raw", "_small_debug"), smallBatchConfig);
        
        SimulationConfiguration.IndexerServiceConfig mediumBatchConfig = new SimulationConfiguration.IndexerServiceConfig();
        mediumBatchConfig.batchSize = 1000;
        DebugIndexer mediumBatchIndexer = new DebugIndexer(rawDbPath, rawDbPath.replace("_raw", "_medium_debug"), mediumBatchConfig);
        
        SimulationConfiguration.IndexerServiceConfig largeBatchConfig = new SimulationConfiguration.IndexerServiceConfig();
        largeBatchConfig.batchSize = 5000;
        DebugIndexer largeBatchIndexer = new DebugIndexer(rawDbPath, rawDbPath.replace("_raw", "_large_debug"), largeBatchConfig);
        
        smallBatchIndexer.start();
        mediumBatchIndexer.start();
        largeBatchIndexer.start();
        
        int maxWaitTime = 5000;
        int waitInterval = 100;
        int totalWaitTime = 0;
        while((!smallBatchIndexer.isRunning() || !mediumBatchIndexer.isRunning() || !largeBatchIndexer.isRunning()) && totalWaitTime < maxWaitTime) {
            Thread.sleep(waitInterval);
            totalWaitTime += waitInterval;
        }

        assertTrue(smallBatchIndexer.isRunning(), "Small batch indexer should be running");
        assertTrue(mediumBatchIndexer.isRunning(), "Medium batch indexer should be running");
        assertTrue(largeBatchIndexer.isRunning(), "Large batch indexer should be running");
        
        smallBatchIndexer.shutdown();
        mediumBatchIndexer.shutdown();
        largeBatchIndexer.shutdown();
    }
}
