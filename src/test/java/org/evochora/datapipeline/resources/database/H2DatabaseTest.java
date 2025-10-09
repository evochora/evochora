/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.resources.database;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for H2Database resource.
 * <p>
 * Tests focus on schema name sanitization logic without requiring actual database operations.
 */
@Tag("unit")
class H2DatabaseTest {

    private H2Database database;

    @BeforeEach
    void setUp() {
        // Use in-memory database for testing
        String dbUrl = "jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        Config config = ConfigFactory.parseString("jdbcUrl = \"" + dbUrl + "\"");
        database = new H2Database("test-db", config);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.stop();
        }
    }

    @Test
    void testToSchemaName_ValidRunId() {
        // Standard UUID-based runId
        String runId = "20251006143025-550e8400-e29b-41d4-a716-446655440000";
        String schemaName = database.toSchemaName(runId);
        
        assertEquals("SIM_20251006143025_550E8400_E29B_41D4_A716_446655440000", schemaName);
        assertTrue(schemaName.startsWith("SIM_"));
        assertFalse(schemaName.contains("-"));
    }

    @Test
    void testToSchemaName_ShortRunId() {
        String runId = "test-run-123";
        String schemaName = database.toSchemaName(runId);
        
        assertEquals("SIM_TEST_RUN_123", schemaName);
    }

    @Test
    void testToSchemaName_SpecialCharacters() {
        // RunId with various special characters that should be replaced with underscores
        String runId = "run@2025#10-06!test$";
        String schemaName = database.toSchemaName(runId);
        
        // All special characters replaced with underscore
        assertEquals("SIM_RUN_2025_10_06_TEST_", schemaName);
        assertTrue(schemaName.matches("^[A-Z0-9_]+$"));
    }

    @Test
    void testToSchemaName_NullRunId() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> database.toSchemaName(null)
        );
        
        assertEquals("Simulation run ID cannot be null or empty", exception.getMessage());
    }

    @Test
    void testToSchemaName_EmptyRunId() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> database.toSchemaName("")
        );
        
        assertEquals("Simulation run ID cannot be null or empty", exception.getMessage());
    }

    @Test
    void testToSchemaName_TooLongRunId() {
        // Create a runId that results in a schema name > 256 characters
        // "sim_" = 4 chars, so we need runId > 252 chars
        String longRunId = "x".repeat(253);
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> database.toSchemaName(longRunId)
        );
        
        assertTrue(exception.getMessage().contains("Schema name too long"));
        assertTrue(exception.getMessage().contains("max 256"));
    }

    @Test
    void testToSchemaName_MaxLengthRunId() {
        // Test boundary: exactly 256 chars should work
        // "sim_" = 4 chars, so runId can be 252 chars
        String maxLengthRunId = "x".repeat(252);
        
        String schemaName = database.toSchemaName(maxLengthRunId);
        
        assertEquals(256, schemaName.length());
        assertTrue(schemaName.startsWith("SIM_"));
    }

    @Test
    void testToSchemaName_AlphanumericOnly() {
        String runId = "ABC123xyz789";
        String schemaName = database.toSchemaName(runId);
        
        assertEquals("SIM_ABC123XYZ789", schemaName);
        assertTrue(schemaName.matches("^[A-Z0-9_]+$"));
    }

    @Test
    void testToSchemaName_Uppercase() {
        // Verify that result is always uppercase (H2 requirement)
        String runId = "lowercase-UPPERCASE-MiXeD";
        String schemaName = database.toSchemaName(runId);
        
        assertEquals("SIM_LOWERCASE_UPPERCASE_MIXED", schemaName);
        assertEquals(schemaName, schemaName.toUpperCase());
    }

    @Test
    void testToSchemaName_ConsecutiveSpecialChars() {
        // Multiple consecutive special characters should result in consecutive underscores
        String runId = "test---run___id";
        String schemaName = database.toSchemaName(runId);
        
        assertEquals("SIM_TEST___RUN___ID", schemaName);
    }

    // ===== Tests for JDBC URL Construction =====

    @Test
    void testJdbcUrl_DirectJdbcUrlProvided() {
        // When jdbcUrl is directly provided, it should be used as-is
        String expectedUrl = "jdbc:h2:mem:custom-test;MODE=PostgreSQL";
        Config config = ConfigFactory.parseString("jdbcUrl = \"" + expectedUrl + "\"");
        
        H2Database db = new H2Database("test", config);
        
        // We can't directly test getJdbcUrl(), but we can verify the database was created successfully
        // and that it's using the correct URL by checking it doesn't throw an exception
        assertNotNull(db);
        db.stop();
    }

    @Test
    void testJdbcUrl_DataDirectoryWithoutVariables() {
        // When dataDirectory is provided without variables, it should construct JDBC URL
        String testDir = System.getProperty("java.io.tmpdir") + "/h2-test-" + UUID.randomUUID();
        Config config = ConfigFactory.parseString("dataDirectory = \"" + testDir + "\"");
        
        H2Database db = new H2Database("test", config);
        
        // Database should be created successfully
        assertNotNull(db);
        db.stop();
    }

    @Test
    void testJdbcUrl_DataDirectoryWithVariables() {
        // When dataDirectory contains variables, they should be expanded
        System.setProperty("test.db.dir", System.getProperty("java.io.tmpdir"));
        Config config = ConfigFactory.parseString("dataDirectory = \"${test.db.dir}/h2-test\"");
        
        H2Database db = new H2Database("test", config);
        
        // Database should be created successfully with expanded path
        assertNotNull(db);
        db.stop();
        System.clearProperty("test.db.dir");
    }

    @Test
    void testJdbcUrl_NeitherJdbcUrlNorDataDirectory() {
        // When neither jdbcUrl nor dataDirectory is provided, it should throw exception
        Config config = ConfigFactory.parseString("maxPoolSize = 5");
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new H2Database("test", config)
        );
        
        assertTrue(exception.getMessage().contains("Either 'jdbcUrl' or 'dataDirectory' must be configured"));
    }

    @Test
    void testJdbcUrl_JdbcUrlTakesPrecedenceOverDataDirectory() {
        // When both are provided, jdbcUrl should take precedence
        String expectedUrl = "jdbc:h2:mem:priority-test;MODE=PostgreSQL";
        Config config = ConfigFactory.parseString(
            "jdbcUrl = \"" + expectedUrl + "\"\n" +
            "dataDirectory = \"/this/should/be/ignored\""
        );
        
        H2Database db = new H2Database("test", config);
        
        // Should use jdbcUrl, not dataDirectory
        // (dataDirectory would fail if actually used because it's not absolute/valid)
        assertNotNull(db);
        db.stop();
    }

    @Test
    void testJdbcUrl_DataDirectoryWithUndefinedVariable() {
        // When dataDirectory contains undefined variable, it should throw exception
        Config config = ConfigFactory.parseString(
            "dataDirectory = \"${this_var_does_not_exist_9999}/data\""
        );
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new H2Database("test", config)
        );
        
        assertTrue(exception.getMessage().contains("Undefined variable"));
    }

    // ===== Tests for Metrics =====

    @Test
    void testMetrics_PoolMetricsAvailable() {
        // Verify that HikariCP pool metrics are exposed
        Map<String, Number> metrics = database.getMetrics();
        
        // Base metrics (from AbstractDatabaseResource)
        assertNotNull(metrics.get("queries_executed"));
        assertNotNull(metrics.get("rows_inserted"));
        
        // H2-specific pool metrics (should be available even without active queries)
        assertNotNull(metrics.get("h2_pool_active_connections"), "Should expose active connections metric");
        assertNotNull(metrics.get("h2_pool_idle_connections"), "Should expose idle connections metric");
        assertNotNull(metrics.get("h2_pool_total_connections"), "Should expose total connections metric");
        assertNotNull(metrics.get("h2_pool_threads_awaiting"), "Should expose threads awaiting metric");
        
        // Verify initial state (no active connections yet)
        assertEquals(0, metrics.get("h2_pool_active_connections").intValue(),
                "Active connections should be 0 initially");
    }

    @Test
    void testMetrics_DiskWriteRate() {
        // Verify disk write rate metric is available
        Map<String, Number> metrics = database.getMetrics();
        
        assertNotNull(metrics.get("h2_disk_writes_per_sec"), "Should expose disk writes per second");
        
        // Initial value should be 0 (no operations yet)
        assertEquals(0.0, metrics.get("h2_disk_writes_per_sec").doubleValue(), 0.01);
    }

    @Test
    void testMetrics_CacheSizeAvailable() {
        // Verify H2 cache size metric is queryable
        Map<String, Number> metrics = database.getMetrics();
        
        // Cache size might not be available in all H2 configurations, but method should not throw
        // If available, it should be a non-negative number
        Number cacheSize = metrics.get("h2_cache_size_bytes");
        if (cacheSize != null) {
            assertTrue(cacheSize.longValue() >= 0, "Cache size should be non-negative");
        }
    }
}


