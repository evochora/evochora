package org.evochora.datapipeline.resources.database;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.CellState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.evochora.junit.extensions.logging.LogWatchExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

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
    void testStrategyLoading_DefaultStrategy() throws Exception {
        // Given: H2Database without h2EnvironmentStrategy config
        Config config = ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:file:%s/test-default-strategy"
            """.formatted(tempDir.toString()));
        
        // When: Create database
        database = new H2Database("test-db", config);
        
        // Then: Should use default SingleBlobStrategy
        assertThat(database).isNotNull();
        
        // Verify it can create environment table and write data
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{10, 10}, false);
        TickData tick = TickData.newBuilder()
            .setTickNumber(1L)
            .addCells(CellState.newBuilder()
                .setFlatIndex(0)
                .setOwnerId(100)
                .setMoleculeType(1)
                .setMoleculeValue(50)
                .build())
            .build();
        
        Object conn = database.acquireDedicatedConnection();
        try {
            database.doCreateEnvironmentDataTable(conn, 2);
            database.doWriteEnvironmentCells(conn, List.of(tick), envProps);
        } finally {
            if (conn instanceof java.sql.Connection) {
                ((java.sql.Connection) conn).close();
            }
        }
    }
    
    @Test
    void testStrategyLoading_WithCustomStrategy() throws Exception {
        // Given: H2Database with explicit SingleBlobStrategy and compression
        Config config = ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:file:%s/test-custom-strategy"
            h2EnvironmentStrategy {
                className = "org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy"
                options {
                    compression {
                        codec = "zstd"
                        level = 3
                    }
                }
            }
            """.formatted(tempDir.toString()));
        
        // When: Create database
        database = new H2Database("test-db", config);
        
        // Then: Should use SingleBlobStrategy with compression
        assertThat(database).isNotNull();
        
        // Verify it works with compression
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{10, 10}, false);
        TickData tick = TickData.newBuilder()
            .setTickNumber(1L)
            .addCells(CellState.newBuilder()
                .setFlatIndex(0)
                .setOwnerId(100)
                .setMoleculeType(1)
                .setMoleculeValue(50)
                .build())
            .build();
        
        Object conn = database.acquireDedicatedConnection();
        try {
            database.doCreateEnvironmentDataTable(conn, 2);
            database.doWriteEnvironmentCells(conn, List.of(tick), envProps);
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

