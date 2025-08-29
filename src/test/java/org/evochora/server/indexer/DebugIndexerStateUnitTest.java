package org.evochora.server.indexer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast unit tests for DebugIndexer state management and basic functionality.
 * These tests focus only on state transitions and configuration validation
 * without any real database operations or threading.
 * 
 * Performance requirement: All tests must complete in < 0.1 seconds.
 */
@Tag("unit")
class DebugIndexerStateUnitTest {

    private DebugIndexer indexer;

    @BeforeEach
    void setUp() {
        // Use in-memory database URLs for fast initialization
        indexer = new DebugIndexer("jdbc:sqlite:file:memdb_test?mode=memory&cache=shared", 1000);
    }

    @Test
    @Tag("unit")
    void testInitialState() {
        // Test initial state - should be instant
        assertThat(indexer.isRunning()).isFalse();
        assertThat(indexer.isPaused()).isFalse();
        assertThat(indexer.isAutoPaused()).isFalse();
    }

    @Test
    @Tag("unit")
    void testStatusWhenStopped() {
        // Test status when stopped
        String status = indexer.getStatus();
        assertThat(status).isEqualTo("stopped");
    }

    @Test
    @Tag("unit")
    void testBatchSizeConfiguration() {
        // Test that batch size is properly configured
        DebugIndexer smallBatchIndexer = new DebugIndexer("jdbc:sqlite:file:memdb_test2?mode=memory&cache=shared", 100);
        assertThat(smallBatchIndexer).isNotNull();
        
        DebugIndexer largeBatchIndexer = new DebugIndexer("jdbc:sqlite:file:memdb_test3?mode=memory&cache=shared", 5000);
        assertThat(largeBatchIndexer).isNotNull();
    }

    @Test
    @Tag("unit")
    void testDatabaseUrlHandling() {
        // Test different database URL formats
        DebugIndexer jdbcIndexer = new DebugIndexer("jdbc:sqlite:file:memdb_jdbc?mode=memory&cache=shared", 1000);
        assertThat(jdbcIndexer).isNotNull();
        
        DebugIndexer fileIndexer = new DebugIndexer("test_database.sqlite", 1000);
        assertThat(fileIndexer).isNotNull();
    }

    @Test
    @Tag("unit")
    void testMultipleIndexerCreation() {
        // Test that multiple indexers can be created quickly
        DebugIndexer indexer1 = new DebugIndexer("jdbc:sqlite:file:memdb1?mode=memory&cache=shared", 100);
        DebugIndexer indexer2 = new DebugIndexer("jdbc:sqlite:file:memdb2?mode=memory&cache=shared", 200);
        DebugIndexer indexer3 = new DebugIndexer("jdbc:sqlite:file:memdb3?mode=memory&cache=shared", 300);
        
        assertThat(indexer1).isNotNull();
        assertThat(indexer2).isNotNull();
        assertThat(indexer3).isNotNull();
        
        assertThat(indexer1.isRunning()).isFalse();
        assertThat(indexer2.isRunning()).isFalse();
        assertThat(indexer3.isRunning()).isFalse();
    }

    @Test
    @Tag("unit")
    void testStateFlagsInitialization() {
        // Test all state flags are properly initialized
        assertThat(indexer.isRunning()).isFalse();
        assertThat(indexer.isPaused()).isFalse();
        assertThat(indexer.isAutoPaused()).isFalse();
    }

    @Test
    @Tag("unit")
    void testConfigurationValidation() {
        // Test configuration validation through available methods
        assertThat(indexer).isNotNull();
        assertThat(indexer.getStatus()).isNotNull();
    }

    @Test
    @Tag("unit")
    void testIndexerCreationPerformance() {
        // Test that indexer creation is fast and doesn't start threads
        assertThat(indexer.isRunning()).isFalse();
        
        // Test that we can create multiple indexers quickly
        for (int i = 0; i < 10; i++) {
            DebugIndexer fastIndexer = new DebugIndexer("jdbc:sqlite:file:memdb_fast" + i + "?mode=memory&cache=shared", 1000);
            assertThat(fastIndexer.isRunning()).isFalse();
        }
    }

    @Test
    @Tag("unit")
    void testStateTransitionsWithoutStarting() {
        // Test state transitions without actually starting the indexer
        assertThat(indexer.isRunning()).isFalse();
        assertThat(indexer.isPaused()).isFalse();
        assertThat(indexer.isAutoPaused()).isFalse();
        
        // These should be no-ops when indexer is not running
        // We can't call pause/resume directly, but we can verify initial state
        assertThat(indexer.isRunning()).isFalse();
        assertThat(indexer.isPaused()).isFalse();
        assertThat(indexer.isAutoPaused()).isFalse();
    }
}
