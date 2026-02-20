package org.evochora.datapipeline.resources.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.evochora.datapipeline.CellStateTestHelper;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Integration tests for H2Database environment storage strategy loading.
 * <p>
 * Tests reflection-based strategy instantiation and error handling.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
class H2DatabaseEnvIntegrationTest {
    
    @TempDir
    Path tempDir;
    
    private H2Database database;
    
    @AfterEach
    void tearDown() throws Exception {
        if (database != null) {
            database.close();
        }
    }
    
    @Test
    void testStrategyLoading_NoStrategyConfigured() throws Exception {
        // Given: H2Database without h2EnvironmentStrategy config
        String dbPath = tempDir.toString().replace("\\", "/");
        Config config = ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:file:%s/test-no-strategy"
            """.formatted(dbPath));

        // When: Create database (succeeds — strategy is not loaded eagerly)
        database = new H2Database("test-db", config);
        assertThat(database).isNotNull();

        // Then: Environment operations should fail with clear error
        Object conn = database.acquireDedicatedConnection();
        try {
            assertThatThrownBy(() -> database.doCreateEnvironmentDataTable(conn, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Environment storage strategy not configured");
        } finally {
            if (conn instanceof java.sql.Connection) {
                ((java.sql.Connection) conn).close();
            }
        }
    }
    
    @Test
    void testStrategyLoading_WithCustomStrategy() throws Exception {
        // Given: H2Database with explicit RowPerChunkStrategy and compression
        // Use forward slashes in path (works on all platforms, avoids Config parsing issues with backslashes)
        String dbPath = tempDir.toString().replace("\\", "/");
        Config config = ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:file:%s/test-custom-strategy"
            h2EnvironmentStrategy {
                className = "org.evochora.datapipeline.resources.database.h2.RowPerChunkStrategy"
                options {
                    chunkDirectory = "%s/env-chunks"
                    compression {
                        codec = "zstd"
                        level = 3
                    }
                }
            }
            """.formatted(dbPath, dbPath));
        
        // When: Create database
        database = new H2Database("test-db", config);
        
        // Then: Should use RowPerChunkStrategy with compression
        assertThat(database).isNotNull();
        
        // Verify it works with compression
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(1L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
            )))
            .build();
        
        byte[] rawBytes = TickDataChunk.newBuilder()
            .setFirstTick(1L).setLastTick(1L).setTickCount(1)
            .setSnapshot(snapshot).build().toByteArray();

        Object conn = database.acquireDedicatedConnection();
        try {
            database.doCreateEnvironmentDataTable(conn, 2);
            database.doWriteRawEnvironmentChunk(conn, 1L, 1L, 1, rawBytes);
            database.doCommitRawEnvironmentChunks(conn);
        } finally {
            if (conn instanceof java.sql.Connection) {
                ((java.sql.Connection) conn).close();
            }
        }
    }
    
    @Test
    void testStrategyLoading_InvalidClassName() {
        // Given: H2Database with non-existent strategy class
        Config config = ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:mem:test-invalid-class"
            h2EnvironmentStrategy {
                className = "org.evochora.NonExistentStrategy"
            }
            """);
        
        // When/Then: Should throw IllegalArgumentException with clear message
        assertThatThrownBy(() -> new H2Database("test-db", config))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Storage strategy class not found")
            .hasMessageContaining("NonExistentStrategy");
    }
    
    @Test
    void testStrategyLoading_NotImplementingInterface() {
        // Given: H2Database with class that doesn't implement IH2EnvStorageStrategy
        Config config = ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:mem:test-wrong-interface"
            h2EnvironmentStrategy {
                className = "java.lang.String"
            }
            """);
        
        // When/Then: Should throw IllegalArgumentException
        assertThatThrownBy(() -> new H2Database("test-db", config))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Storage strategy must have public constructor(Config)")
            .hasMessageContaining("String");
    }
    
    @Test
    void testStrategyLoading_NoValidConstructor() {
        // Given: H2Database with class that has no Config constructor
        // (This would require a custom test class, but we test the error path)
        Config config = ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:mem:test-no-constructor"
            h2EnvironmentStrategy {
                className = "org.evochora.datapipeline.resources.database.H2DatabaseEnvIntegrationTest"
            }
            """);
        
        // When/Then: Should throw IllegalArgumentException
        assertThatThrownBy(() -> new H2Database("test-db", config))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("constructor");
    }
}
