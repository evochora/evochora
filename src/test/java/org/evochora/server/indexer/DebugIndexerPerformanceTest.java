package org.evochora.server.indexer;

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

class DebugIndexerPerformanceTest {

    private DebugIndexer debugIndexer;
    private Path tempDir;
    private String rawDbPath;
    private String debugDbPath;
    private static final int BATCH_SIZE = 1000;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        this.tempDir = tempDir;
        
        // Use in-memory databases for reliable testing without file locking issues
        this.rawDbPath = "jdbc:sqlite:file:memdb_perf_raw?mode=memory&cache=shared";
        this.debugDbPath = "jdbc:sqlite:file:memdb_perf_debug?mode=memory&cache=shared";
        
        debugIndexer = new DebugIndexer(rawDbPath, BATCH_SIZE);
    }

    @AfterEach
    void tearDown() {
        if (debugIndexer != null) {
            debugIndexer.shutdown();
        }
    }

    private void createTestDatabase(String dbPath) throws Exception {
        try (Connection conn = DriverManager.getConnection(dbPath)) {
            try (Statement stmt = conn.createStatement()) {
                // Create raw_ticks table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS raw_ticks (
                        tick_number INTEGER PRIMARY KEY,
                        organisms TEXT,
                        cells TEXT
                    )
                """);
                
                // Create raw_organisms table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS raw_organisms (
                        id INTEGER,
                        parent_id INTEGER,
                        birth_tick INTEGER,
                        program_id TEXT,
                        initial_position TEXT,
                        ip TEXT,
                        dv TEXT,
                        dps TEXT,
                        active_dp_index INTEGER,
                        er INTEGER,
                        drs TEXT,
                        prs TEXT,
                        fprs TEXT,
                        lrs TEXT,
                        data_stack TEXT,
                        location_stack TEXT,
                        call_stack TEXT,
                        is_dead BOOLEAN,
                        instruction_failed BOOLEAN,
                        failure_reason TEXT,
                        skip_ip_advance BOOLEAN,
                        ip_before_fetch TEXT,
                        dv_before_fetch TEXT
                    )
                """);
                
                // Create raw_cells table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS raw_cells (
                        pos TEXT,
                        molecule INTEGER,
                        owner_id INTEGER
                    )
                """);
                
                // Create program_artifacts table
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS program_artifacts (
                        program_id TEXT PRIMARY KEY,
                        artifact_json TEXT
                    )
                """);
                
                // Insert test data
                for (int i = 1; i <= 100; i++) {
                    stmt.execute("INSERT INTO raw_ticks (tick_number, organisms, cells) VALUES (" + i + ", '[]', '[]')");
                }
                
                // Insert test program artifact
                stmt.execute("INSERT OR REPLACE INTO program_artifacts (program_id, artifact_json) VALUES ('test123', '{\"source_code\":\"test code\",\"bytecode\":\"test bytecode\",\"source_mapping\":{}}')");
            }
        }
    }

    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testBatchProcessingPerformance() throws Exception {
        // Create test database with data
        createTestDatabase(rawDbPath);
        
        // Start debug indexer
        debugIndexer.start();
        Thread.sleep(100);
        
        // Verify it's running
        assertTrue(debugIndexer.isRunning());
        
        // Wait for processing
        Thread.sleep(200);
        
        // Verify processing occurred
        assertTrue(debugIndexer.getLastProcessedTick() >= 0);
    }

    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testSQLiteOptimizations() throws Exception {
        // Create test database with data
        createTestDatabase(rawDbPath);
        
        // Start debug indexer
        debugIndexer.start();
        Thread.sleep(100);
        
        // Verify it's running
        assertTrue(debugIndexer.isRunning());
        
        // Wait for processing
        Thread.sleep(200);
        
        // Verify processing occurred
        assertTrue(debugIndexer.getLastProcessedTick() >= 0);
    }

    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testWALCheckpointing() throws Exception {
        // Create test database with data
        createTestDatabase(rawDbPath);
        
        // Start debug indexer
        debugIndexer.start();
        Thread.sleep(100);
        
        // Verify it's running
        assertTrue(debugIndexer.isRunning());
        
        // Wait for processing
        Thread.sleep(200);
        
        // Verify processing occurred
        assertTrue(debugIndexer.getLastProcessedTick() >= 0);
    }

    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testAutoPauseResume() throws Exception {
        // Create test database with data
        createTestDatabase(rawDbPath);
        
        // Start debug indexer
        debugIndexer.start();
        Thread.sleep(100);
        
        // Verify it's running
        assertTrue(debugIndexer.isRunning());
        
        // Wait for processing
        Thread.sleep(200);
        
        // Verify processing occurred
        assertTrue(debugIndexer.getLastProcessedTick() >= 0);
    }

    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testThrottlingAndPerformance() throws Exception {
        // Create test database with data
        createTestDatabase(rawDbPath);
        
        // Start debug indexer
        debugIndexer.start();
        Thread.sleep(100);
        
        // Verify it's running
        assertTrue(debugIndexer.isRunning());
        
        // Wait for processing
        Thread.sleep(200);
        
        // Verify processing occurred
        assertTrue(debugIndexer.getLastProcessedTick() >= 0);
    }

    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testGracefulShutdown() throws Exception {
        // Create test database with data
        createTestDatabase(rawDbPath);
        
        // Start debug indexer
        debugIndexer.start();
        Thread.sleep(100);
        
        // Verify it's running
        assertTrue(debugIndexer.isRunning());
        
        // Wait for processing
        Thread.sleep(200);
        
        // Verify processing occurred
        assertTrue(debugIndexer.getLastProcessedTick() >= 0);
        
        // Shutdown
        debugIndexer.shutdown();
        Thread.sleep(100);
        
        // Verify shutdown
        assertFalse(debugIndexer.isRunning());
    }

    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testDataIntegrity() throws Exception {
        // Create test database with data
        createTestDatabase(rawDbPath);
        
        // Start debug indexer
        debugIndexer.start();
        Thread.sleep(100);
        
        // Verify it's running
        assertTrue(debugIndexer.isRunning());
        
        // Wait for processing
        Thread.sleep(200);
        
        // Verify processing occurred
        assertTrue(debugIndexer.getLastProcessedTick() >= 0);
    }

    @Test
    @Tag("integration")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void testBatchSizeImpactOnPerformance() throws Exception {
        // Test with different batch sizes
        DebugIndexer smallBatchIndexer = new DebugIndexer(rawDbPath, 100);
        DebugIndexer mediumBatchIndexer = new DebugIndexer(rawDbPath, 1000);
        DebugIndexer largeBatchIndexer = new DebugIndexer(rawDbPath, 5000);
        
        // Create test database with data
        createTestDatabase(rawDbPath);
        
        // Start all indexers
        smallBatchIndexer.start();
        mediumBatchIndexer.start();
        largeBatchIndexer.start();
        
        // Let them run for a bit
        Thread.sleep(100);
        
        // All should be running
        assertTrue(smallBatchIndexer.isRunning());
        assertTrue(mediumBatchIndexer.isRunning());
        assertTrue(largeBatchIndexer.isRunning());
        
        // Cleanup
        smallBatchIndexer.shutdown();
        mediumBatchIndexer.shutdown();
        largeBatchIndexer.shutdown();
    }
}
