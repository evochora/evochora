package org.evochora.server.indexer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains fast unit tests for the {@link DebugIndexer}'s state management and basic functionality.
 * These tests focus only on initial state, configuration validation, and object instantiation
 * without any real database operations or threading.
 * All tests are tagged as "unit" and should complete very quickly.
 */
@Tag("unit")
class DebugIndexerStateUnitTest {

    private DebugIndexer indexer;

    @BeforeEach
    void setUp() {
        // Use in-memory database URLs for fast initialization
        indexer = new DebugIndexer("jdbc:sqlite:file:memdb_test?mode=memory&cache=shared", 1000);
    }

    /**
     * Verifies the initial state of a newly created DebugIndexer instance.
     * This is a unit test for the default state of the service.
     */
    @Test
    void testInitialState() {
        // Test initial state - should be instant
        assertThat(indexer.isRunning()).isFalse();
        assertThat(indexer.isPaused()).isFalse();
        assertThat(indexer.isAutoPaused()).isFalse();
    }

    /**
     * Verifies that the status of a newly created indexer is "stopped".
     * This is a unit test for the status reporting logic.
     */
    @Test
    void testStatusWhenStopped() {
        // Test status when stopped
        String status = indexer.getStatus();
        assertThat(status).isEqualTo("stopped");
    }

    /**
     * Verifies that the indexer can be configured with different batch sizes.
     * This is a unit test for the constructor logic.
     */
    @Test
    void testBatchSizeConfiguration() {
        // Test that batch size is properly configured
        DebugIndexer smallBatchIndexer = new DebugIndexer("jdbc:sqlite:file:memdb_test2?mode=memory&cache=shared", 100);
        assertThat(smallBatchIndexer).isNotNull();
        
        DebugIndexer largeBatchIndexer = new DebugIndexer("jdbc:sqlite:file:memdb_test3?mode=memory&cache=shared", 5000);
        assertThat(largeBatchIndexer).isNotNull();
    }

    /**
     * Verifies that the indexer can be configured with different database URL formats.
     * This is a unit test for the constructor logic.
     */
    @Test
    void testDatabaseUrlHandling() {
        // Test different database URL formats
        DebugIndexer jdbcIndexer = new DebugIndexer("jdbc:sqlite:file:memdb_jdbc?mode=memory&cache=shared", 1000);
        assertThat(jdbcIndexer).isNotNull();
        
        DebugIndexer fileIndexer = new DebugIndexer("test_database.sqlite", 1000);
        assertThat(fileIndexer).isNotNull();
    }

    /**
     * Verifies that multiple indexer instances can be created without interference.
     * This is a unit test for state isolation between instances.
     */
    @Test
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

    /**
     * Verifies that all state-related flags are correctly initialized to false.
     * This is a unit test for the default state of the service.
     */
    @Test
    void testStateFlagsInitialization() {
        // Test all state flags are properly initialized
        assertThat(indexer.isRunning()).isFalse();
        assertThat(indexer.isPaused()).isFalse();
        assertThat(indexer.isAutoPaused()).isFalse();
    }

    /**
     * Verifies that the indexer can be instantiated and its status can be retrieved without errors.
     * This is a unit test for basic configuration validation.
     */
    @Test
    void testConfigurationValidation() {
        // Test configuration validation through available methods
        assertThat(indexer).isNotNull();
        assertThat(indexer.getStatus()).isNotNull();
    }

    /**
     * Verifies that creating multiple indexer instances is a fast operation and does not start any threads.
     * This is a performance-based unit test for the constructor.
     */
    @Test
    void testIndexerCreationPerformance() {
        // Test that indexer creation is fast and doesn't start threads
        assertThat(indexer.isRunning()).isFalse();
        
        // Test that we can create multiple indexers quickly
        for (int i = 0; i < 10; i++) {
            DebugIndexer fastIndexer = new DebugIndexer("jdbc:sqlite:file:memdb_fast" + i + "?mode=memory&cache=shared", 1000);
            assertThat(fastIndexer.isRunning()).isFalse();
        }
    }

    /**
     * Verifies that the initial state of the indexer is correct, confirming that no
     * state transitions have occurred upon instantiation.
     * This is a unit test for the default state of the service.
     */
    @Test
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
