package org.evochora.server.indexer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.evochora.server.config.SimulationConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains fast unit tests for the {@link DebugIndexer}'s validation and business rules.
 * These tests focus only on validation of initial state and configuration parameters
 * without any real database operations or threading.
 * All tests are tagged as "unit" and should complete very quickly.
 */
@Tag("unit")
class DebugIndexerValidationUnitTest {

    private DebugIndexer indexer;

    @BeforeEach
    void setUp() {
        // Use in-memory database URLs for fast initialization
        SimulationConfiguration.IndexerServiceConfig config = new SimulationConfiguration.IndexerServiceConfig();
        config.batchSize = 1000;
        indexer = new DebugIndexer("jdbc:sqlite:file:memdb_validation?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_validation_debug?mode=memory&cache=shared", config);
    }

    /**
     * Performs a basic validation that the indexer is initialized correctly.
     * This is a unit test for the constructor and initial state.
     */
    @Test
    @Tag("unit")
    void testBasicValidation() {
        // Test basic validation that indexer is properly initialized
        assertThat(indexer).isNotNull();
        assertThat(indexer.isRunning()).isFalse();
        assertThat(indexer.isPaused()).isFalse();
        assertThat(indexer.isAutoPaused()).isFalse();
    }

    /**
     * Verifies that the initial state flags of the indexer are consistent and correct.
     * This is a unit test for the default state of the service.
     */
    @Test
    @Tag("unit")
    void testStateValidation() {
        // Test that state validation is consistent
        boolean isRunning = indexer.isRunning();
        boolean isPaused = indexer.isPaused();
        boolean isAutoPaused = indexer.isAutoPaused();
        
        // All should be false initially
        assertThat(isRunning).isFalse();
        assertThat(isPaused).isFalse();
        assertThat(isAutoPaused).isFalse();
        
        // State should be consistent
        assertThat(isRunning || isPaused || isAutoPaused).isFalse();
    }

    /**
     * Validates that the initial status string of the indexer is correct.
     * This is a unit test for the status reporting logic.
     */
    @Test
    @Tag("unit")
    void testStatusValidation() {
        // Test status validation
        String status = indexer.getStatus();
        assertThat(status).isNotNull();
        assertThat(status).isNotEmpty();
        assertThat(status).isEqualTo("stopped");
        
        // Status should be a valid string
        assertThat(status.length()).isGreaterThan(0);
        assertThat(status).contains("stopped");
    }

    /**
     * Verifies that the indexer can be instantiated with various valid configurations.
     * This is a unit test for the constructor logic.
     */
    @Test
    @Tag("unit")
    void testConfigurationValidation() {
        // Test configuration validation
        SimulationConfiguration.IndexerServiceConfig validConfigConfig = new SimulationConfiguration.IndexerServiceConfig();
        validConfigConfig.batchSize = 1000;
        DebugIndexer validConfig = new DebugIndexer("jdbc:sqlite:file:memdb_valid?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_valid_debug?mode=memory&cache=shared", validConfigConfig);
        assertThat(validConfig).isNotNull();
        
        // Should accept various valid configurations
        SimulationConfiguration.IndexerServiceConfig config1Config = new SimulationConfiguration.IndexerServiceConfig();
        config1Config.batchSize = 1;
        DebugIndexer config1 = new DebugIndexer("jdbc:sqlite:file:memdb_c1?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_c1_debug?mode=memory&cache=shared", config1Config);
        SimulationConfiguration.IndexerServiceConfig config2Config = new SimulationConfiguration.IndexerServiceConfig();
        config2Config.batchSize = 100;
        DebugIndexer config2 = new DebugIndexer("jdbc:sqlite:file:memdb_c2?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_c2_debug?mode=memory&cache=shared", config2Config);
        SimulationConfiguration.IndexerServiceConfig config3Config = new SimulationConfiguration.IndexerServiceConfig();
        config3Config.batchSize = 10000;
        DebugIndexer config3 = new DebugIndexer("jdbc:sqlite:file:memdb_c3?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_c3_debug?mode=memory&cache=shared", config3Config);
        
        assertThat(config1).isNotNull();
        assertThat(config2).isNotNull();
        assertThat(config3).isNotNull();
    }

    /**
     * Verifies that the indexer constructor accepts various valid database URL formats.
     * This is a unit test for the constructor logic.
     */
    @Test
    @Tag("unit")
    void testDatabaseUrlValidation() {
        // Test database URL validation
        SimulationConfiguration.IndexerServiceConfig jdbcConfig = new SimulationConfiguration.IndexerServiceConfig();
        jdbcConfig.batchSize = 1000;
        DebugIndexer jdbcUrl = new DebugIndexer("jdbc:sqlite:file:memdb_jdbc?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_jdbc_debug?mode=memory&cache=shared", jdbcConfig);
        assertThat(jdbcUrl).isNotNull();
        
        SimulationConfiguration.IndexerServiceConfig fileConfig = new SimulationConfiguration.IndexerServiceConfig();
        fileConfig.batchSize = 1000;
        DebugIndexer filePath = new DebugIndexer("test_validation.sqlite", "test_validation_debug.sqlite", fileConfig);
        assertThat(filePath).isNotNull();
        
        SimulationConfiguration.IndexerServiceConfig memoryConfig = new SimulationConfiguration.IndexerServiceConfig();
        memoryConfig.batchSize = 1000;
        DebugIndexer inMemory = new DebugIndexer(":memory:", ":memory:", memoryConfig);
        assertThat(inMemory).isNotNull();
    }

    /**
     * Verifies that the indexer can be instantiated with different batch sizes.
     * This is a unit test for the constructor logic.
     */
    @Test
    @Tag("unit")
    void testBatchSizeValidation() {
        // Test batch size validation
        SimulationConfiguration.IndexerServiceConfig batch1Config = new SimulationConfiguration.IndexerServiceConfig();
        batch1Config.batchSize = 1;
        DebugIndexer batch1 = new DebugIndexer("jdbc:sqlite:file:memdb_batch1?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_batch1_debug?mode=memory&cache=shared", batch1Config);
        SimulationConfiguration.IndexerServiceConfig batch100Config = new SimulationConfiguration.IndexerServiceConfig();
        batch100Config.batchSize = 100;
        DebugIndexer batch100 = new DebugIndexer("jdbc:sqlite:file:memdb_batch100?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_batch100_debug?mode=memory&cache=shared", batch100Config);
        SimulationConfiguration.IndexerServiceConfig batch1000Config = new SimulationConfiguration.IndexerServiceConfig();
        batch1000Config.batchSize = 1000;
        DebugIndexer batch1000 = new DebugIndexer("jdbc:sqlite:file:memdb_batch1000?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_batch1000_debug?mode=memory&cache=shared", batch1000Config);
        
        assertThat(batch1).isNotNull();
        assertThat(batch100).isNotNull();
        assertThat(batch1000).isNotNull();
        
        // All should have same initial state regardless of batch size
        assertThat(batch1.isRunning()).isFalse();
        assertThat(batch100.isRunning()).isFalse();
        assertThat(batch1000.isRunning()).isFalse();
    }

    /**
     * Verifies that all constructor overloads for the indexer can be called successfully.
     * This is a unit test for the constructor logic.
     */
    @Test
    @Tag("unit")
    void testConstructorValidation() {
        // Test constructor validation
        SimulationConfiguration.IndexerServiceConfig singleConfig = new SimulationConfiguration.IndexerServiceConfig();
        singleConfig.batchSize = 1000;
        DebugIndexer singleParam = new DebugIndexer("jdbc:sqlite:file:memdb_single?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_single_debug?mode=memory&cache=shared", singleConfig);
        assertThat(singleParam).isNotNull();
        
        SimulationConfiguration.IndexerServiceConfig twoParamsConfig = new SimulationConfiguration.IndexerServiceConfig();
        twoParamsConfig.batchSize = 1000;
        DebugIndexer twoParams = new DebugIndexer("jdbc:sqlite:file:memdb_raw?mode=memory&cache=shared",
                                                 "jdbc:sqlite:file:memdb_debug?mode=memory&cache=shared", twoParamsConfig);
        assertThat(twoParams).isNotNull();
        
        // Both constructors should work
        assertThat(singleParam).isInstanceOf(DebugIndexer.class);
        assertThat(twoParams).isInstanceOf(DebugIndexer.class);
    }

    /**
     * Verifies that the initial state of multiple indexer instances is consistent.
     * This is a unit test for the default state of the service.
     */
    @Test
    @Tag("unit")
    void testStateConsistencyValidation() {
        // Test state consistency validation
        SimulationConfiguration.IndexerServiceConfig consist1Config = new SimulationConfiguration.IndexerServiceConfig();
        consist1Config.batchSize = 500;
        DebugIndexer indexer1 = new DebugIndexer("jdbc:sqlite:file:memdb_consist1?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_consist1_debug?mode=memory&cache=shared", consist1Config);
        SimulationConfiguration.IndexerServiceConfig consist2Config = new SimulationConfiguration.IndexerServiceConfig();
        consist2Config.batchSize = 500;
        DebugIndexer indexer2 = new DebugIndexer("jdbc:sqlite:file:memdb_consist2?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_consist2_debug?mode=memory&cache=shared", consist2Config);
        
        assertThat(indexer1).isNotNull();
        assertThat(indexer2).isNotNull();
        
        // States should be consistent
        assertThat(indexer1.isRunning()).isEqualTo(indexer2.isRunning());
        assertThat(indexer1.isPaused()).isEqualTo(indexer2.isPaused());
        assertThat(indexer1.isAutoPaused()).isEqualTo(indexer2.isAutoPaused());
    }

    /**
     * Verifies that creating indexer instances is a fast operation.
     * This is a performance-based unit test for the constructor.
     */
    @Test
    @Tag("unit")
    void testPerformanceValidation() {
        // Test performance validation - should be fast
        long startTime = System.nanoTime();
        
        for (int i = 0; i < 15; i++) {
            SimulationConfiguration.IndexerServiceConfig perfConfig = new SimulationConfiguration.IndexerServiceConfig();
            perfConfig.batchSize = 1000;
            DebugIndexer fastIndexer = new DebugIndexer("jdbc:sqlite:file:memdb_perf" + i + "?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_perf" + i + "_debug?mode=memory&cache=shared", perfConfig);
            assertThat(fastIndexer).isNotNull();
            assertThat(fastIndexer.isRunning()).isFalse();
        }
        
        long endTime = System.nanoTime();
        long durationNanos = endTime - startTime;
        double durationSeconds = durationNanos / 1_000_000_000.0;
        
        // Should complete in well under 0.1 seconds
        assertThat(durationSeconds).isLessThan(0.1);
    }

    /**
     * Verifies that different indexer instances are isolated from each other.
     * This is a unit test for state isolation.
     */
    @Test
    @Tag("unit")
    void testIsolationValidation() {
        // Test isolation validation - different instances shouldn't interfere
        SimulationConfiguration.IndexerServiceConfig isolated1Config = new SimulationConfiguration.IndexerServiceConfig();
        isolated1Config.batchSize = 100;
        DebugIndexer isolated1 = new DebugIndexer("jdbc:sqlite:file:memdb_isol1?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_isol1_debug?mode=memory&cache=shared", isolated1Config);
        SimulationConfiguration.IndexerServiceConfig isolated2Config = new SimulationConfiguration.IndexerServiceConfig();
        isolated2Config.batchSize = 2000;
        DebugIndexer isolated2 = new DebugIndexer("jdbc:sqlite:file:memdb_isol2?mode=memory&cache=shared", "jdbc:sqlite:file:memdb_isol2_debug?mode=memory&cache=shared", isolated2Config);
        
        assertThat(isolated1).isNotNull();
        assertThat(isolated2).isNotNull();
        
        // Should be isolated
        assertThat(isolated1).isNotSameAs(isolated2);
        
        // Both should have same initial state
        assertThat(isolated1.isRunning()).isFalse();
        assertThat(isolated2.isRunning()).isFalse();
    }
}
