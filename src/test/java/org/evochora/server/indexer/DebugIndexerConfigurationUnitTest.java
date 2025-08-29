package org.evochora.server.indexer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains fast unit tests for the {@link DebugIndexer}'s configuration and validation logic.
 * These tests focus only on constructor logic and parameter handling
 * without any real database operations or threading.
 * All tests are tagged as "unit" and should complete very quickly.
 */
@Tag("unit")
class DebugIndexerConfigurationUnitTest {

    private DebugIndexer indexer;

    @BeforeEach
    void setUp() {
        // Use in-memory database URLs for fast initialization
        indexer = new DebugIndexer("jdbc:sqlite:file:memdb_config?mode=memory&cache=shared", 1000);
    }

    /**
     * Verifies that a new DebugIndexer instance has the correct default state (not running, not paused).
     * This is a unit test for the initial state of the service.
     */
    @Test
    void testDefaultConfiguration() {
        // Test default configuration values
        assertThat(indexer).isNotNull();
        assertThat(indexer.isRunning()).isFalse();
        assertThat(indexer.isPaused()).isFalse();
        assertThat(indexer.isAutoPaused()).isFalse();
    }

    /**
     * Verifies that the DebugIndexer can be instantiated with various valid batch sizes.
     * This is a unit test for the constructor logic.
     */
    @Test
    void testBatchSizeValidation() {
        // Test different batch sizes
        DebugIndexer smallBatch = new DebugIndexer("jdbc:sqlite:file:memdb_small?mode=memory&cache=shared", 1);
        assertThat(smallBatch).isNotNull();
        
        DebugIndexer mediumBatch = new DebugIndexer("jdbc:sqlite:file:memdb_medium?mode=memory&cache=shared", 100);
        assertThat(mediumBatch).isNotNull();
        
        DebugIndexer largeBatch = new DebugIndexer("jdbc:sqlite:file:memdb_large?mode=memory&cache=shared", 10000);
        assertThat(largeBatch).isNotNull();
    }

    /**
     * Verifies that the DebugIndexer can be instantiated with various valid database URL formats.
     * This is a unit test for the constructor logic.
     */
    @Test
    void testDatabaseUrlValidation() {
        // Test various database URL formats
        DebugIndexer jdbcUrl = new DebugIndexer("jdbc:sqlite:file:memdb_jdbc?mode=memory&cache=shared", 1000);
        assertThat(jdbcUrl).isNotNull();
        
        DebugIndexer filePath = new DebugIndexer("test_db.sqlite", 1000);
        assertThat(filePath).isNotNull();
        
        DebugIndexer inMemory = new DebugIndexer(":memory:", 1000);
        assertThat(inMemory).isNotNull();
    }

    /**
     * Verifies that all constructor overloads of the DebugIndexer can be called without error.
     * This is a unit test for the constructor logic.
     */
    @Test
    void testConstructorOverloads() {
        // Test constructor overloads
        DebugIndexer singleParam = new DebugIndexer("jdbc:sqlite:file:memdb_single?mode=memory&cache=shared", 1000);
        assertThat(singleParam).isNotNull();
        
        DebugIndexer twoParams = new DebugIndexer("jdbc:sqlite:file:memdb_raw?mode=memory&cache=shared", 
                                                 "jdbc:sqlite:file:memdb_debug?mode=memory&cache=shared", 1000);
        assertThat(twoParams).isNotNull();
    }

    /**
     * Verifies that multiple instances of the DebugIndexer are created with the same consistent initial state.
     * This is a unit test for the initial state of the service.
     */
    @Test
    void testConfigurationConsistency() {
        // Test that configuration is consistent across multiple instances
        DebugIndexer indexer1 = new DebugIndexer("jdbc:sqlite:file:memdb_consist1?mode=memory&cache=shared", 500);
        DebugIndexer indexer2 = new DebugIndexer("jdbc:sqlite:file:memdb_consist2?mode=memory&cache=shared", 500);
        
        assertThat(indexer1).isNotNull();
        assertThat(indexer2).isNotNull();
        
        // Both should have same initial state
        assertThat(indexer1.isRunning()).isEqualTo(indexer2.isRunning());
        assertThat(indexer1.isPaused()).isEqualTo(indexer2.isPaused());
        assertThat(indexer1.isAutoPaused()).isEqualTo(indexer2.isAutoPaused());
    }

    /**
     * Verifies that creating DebugIndexer instances with in-memory databases is a fast operation.
     * This is a performance-based unit test.
     */
    @Test
    void testMemoryDatabasePerformance() {
        // Test that memory databases are fast to initialize
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 20; i++) {
            DebugIndexer fastIndexer = new DebugIndexer("jdbc:sqlite:file:memdb_perf" + i + "?mode=memory&cache=shared", 1000);
            assertThat(fastIndexer).isNotNull();
        }
        
        long endTime = System.nanoTime();
        long durationNanos = endTime - startTime;
        double durationSeconds = durationNanos / 1_000_000_000.0;
        
        // Should complete in well under 0.1 seconds
        assertThat(durationSeconds).isLessThan(0.1);
    }

    /**
     * Verifies that the state of one DebugIndexer instance is isolated and does not affect another.
     * This is a unit test for state isolation.
     */
    @Test
    void testConfigurationIsolation() {
        // Test that different configurations don't interfere
        DebugIndexer config1 = new DebugIndexer("jdbc:sqlite:file:memdb_isol1?mode=memory&cache=shared", 100);
        DebugIndexer config2 = new DebugIndexer("jdbc:sqlite:file:memdb_isol2?mode=memory&cache=shared", 2000);
        DebugIndexer config3 = new DebugIndexer("jdbc:sqlite:file:memdb_isol3?mode=memory&cache=shared", 500);
        
        assertThat(config1).isNotNull();
        assertThat(config2).isNotNull();
        assertThat(config3).isNotNull();
        
        // All should be in same initial state regardless of configuration
        assertThat(config1.isRunning()).isFalse();
        assertThat(config2.isRunning()).isFalse();
        assertThat(config3.isRunning()).isFalse();
    }

    /**
     * Verifies that the initial status string is consistent.
     * This is a unit test for the status reporting logic.
     */
    @Test
    void testStatusFormatConsistency() {
        // Test that status format is consistent
        String status = indexer.getStatus();
        assertThat(status).isNotNull();
        assertThat(status).isEqualTo("stopped");
        
        // Test multiple indexers have consistent status format
        DebugIndexer indexer2 = new DebugIndexer("jdbc:sqlite:file:memdb_status?mode=memory&cache=shared", 1000);
        String status2 = indexer2.getStatus();
        assertThat(status2).isEqualTo("stopped");
    }

    /**
     * Verifies that the configuration state is not mutated by calls to status methods.
     * This is a unit test for the immutability of the service state.
     */
    @Test
    void testConfigurationPersistence() {
        // Test that configuration persists across method calls
        assertThat(indexer.isRunning()).isFalse();
        assertThat(indexer.isPaused()).isFalse();
        assertThat(indexer.isAutoPaused()).isFalse();
        
        // Call status method multiple times
        String status1 = indexer.getStatus();
        String status2 = indexer.getStatus();
        String status3 = indexer.getStatus();
        
        // Configuration should remain unchanged
        assertThat(indexer.isRunning()).isFalse();
        assertThat(indexer.isPaused()).isFalse();
        assertThat(indexer.isAutoPaused()).isFalse();
        
        // Status should be consistent
        assertThat(status1).isEqualTo(status2);
        assertThat(status2).isEqualTo(status3);
    }
}
