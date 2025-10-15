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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
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
    private Path tempDbDirectory;

    @BeforeEach
    void setUp() {
        // Use in-memory database for testing
        String dbUrl = "jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        Config config = ConfigFactory.parseString("jdbcUrl = \"" + dbUrl + "\"");
        database = new H2Database("test-db", config);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (database != null) {
            database.stop();
        }
        // Clean up temporary database directories created during tests
        if (tempDbDirectory != null && Files.exists(tempDbDirectory)) {
            Files.walk(tempDbDirectory)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(file -> {
                    if (!file.delete()) {
                        System.err.println("Failed to delete: " + file);
                    }
                });
            tempDbDirectory = null;
        }
    }

    // ===== Tests for JDBC URL Construction =====
    // Note: Schema name sanitization tests moved to H2SchemaUtilTest

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
        tempDbDirectory = Paths.get(System.getProperty("java.io.tmpdir"), "h2-test-" + UUID.randomUUID());
        Config config = ConfigFactory.parseString("dataDirectory = \"" + tempDbDirectory.toString().replace("\\", "/") + "\"");
        
        H2Database db = new H2Database("test", config);
        
        // Database should be created successfully
        assertNotNull(db);
        db.stop();
    }

    @Test
    void testJdbcUrl_DataDirectoryWithVariables() {
        // When dataDirectory contains variables, they should be expanded
        String tmpDir = System.getProperty("java.io.tmpdir");
        System.setProperty("test.db.dir", tmpDir);
        String uniqueDir = "h2-test-" + UUID.randomUUID();
        tempDbDirectory = Paths.get(tmpDir, uniqueDir);
        Config config = ConfigFactory.parseString("dataDirectory = \"${test.db.dir}/" + uniqueDir + "\"");
        
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


