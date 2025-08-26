package org.evochora.server.indexer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast unit tests for DebugIndexer configuration and validation logic.
 * These tests focus only on configuration validation and parameter handling
 * without any real database operations or threading.
 * 
 * Performance requirement: All tests must complete in < 0.1 seconds.
 */
@Tag("unit")
class DebugIndexerConfigurationUnitTest {

    private DebugIndexer indexer;

    @BeforeEach
    void setUp() {
        // Use in-memory database URLs for fast initialization
        indexer = new DebugIndexer("jdbc:sqlite:file:memdb_config?mode=memory&cache=shared", 1000);
    }

    @Test
    void testDefaultConfiguration() {
        // Test default configuration values
        assertThat(indexer).isNotNull();
        assertThat(indexer.isRunning()).isFalse();
        assertThat(indexer.isPaused()).isFalse();
        assertThat(indexer.isAutoPaused()).isFalse();
    }

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

    @Test
    void testConstructorOverloads() {
        // Test constructor overloads
        DebugIndexer singleParam = new DebugIndexer("jdbc:sqlite:file:memdb_single?mode=memory&cache=shared", 1000);
        assertThat(singleParam).isNotNull();
        
        DebugIndexer twoParams = new DebugIndexer("jdbc:sqlite:file:memdb_raw?mode=memory&cache=shared", 
                                                 "jdbc:sqlite:file:memdb_debug?mode=memory&cache=shared", 1000);
        assertThat(twoParams).isNotNull();
    }

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
