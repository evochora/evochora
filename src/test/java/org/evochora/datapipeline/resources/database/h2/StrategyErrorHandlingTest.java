package org.evochora.datapipeline.resources.database.h2;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.EnvironmentConfig;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.database.*;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.resources.database.H2Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 error handling tests: Strategy failures and edge cases
 */
@Tag("integration")  // Uses database for error simulation
class StrategyErrorHandlingTest {
    
    private H2Database database;
    private String testRunId;
    
    @BeforeEach
    void setUp() throws SQLException {
        Config config = ConfigFactory.parseString(
            "jdbcUrl = \"jdbc:h2:mem:test-strategy-errors-" + System.nanoTime() + ";MODE=PostgreSQL\"\n" +
            "maxPoolSize = 2\n" +
            "h2EnvironmentStrategy {\n" +
            "  className = \"org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy\"\n" +
            "}\n"
        );
        database = new H2Database("test-db", config);
        testRunId = "20251021_140000_TEST";
        setupTestMetadata(testRunId);
    }
    
    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }
    
    @Test
    void readEnvironmentRegion_throwsOnInvalidTick() throws SQLException {
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            SpatialRegion region = new SpatialRegion(new int[]{0, 10, 0, 10});
            
            // When: Query non-existent tick
            SQLException exception = assertThrows(SQLException.class, () -> 
                reader.readEnvironmentRegion(999999, region)
            );
            
            // Then: Should indicate tick not found
            assertTrue(exception.getMessage().contains("tick") || 
                      exception.getMessage().contains("not found"));
        }
    }
    
    @Test
    void readEnvironmentRegion_handlesCorruptedBlob() throws SQLException {
        // Given: Corrupted BLOB data in database
        insertCorruptedBlob(testRunId, 100);
        
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            SpatialRegion region = new SpatialRegion(new int[]{0, 10, 0, 10});
            
            // When/Then: Should handle corruption gracefully
            // This test verifies that corrupted data doesn't crash the application
            // The exact behavior (exception vs empty list) depends on implementation
            assertDoesNotThrow(() -> {
                try {
                    List<CellWithCoordinates> cells = reader.readEnvironmentRegion(100, region);
                    assertNotNull(cells);
                } catch (SQLException e) {
                    // SQLException is acceptable for corrupted data
                    // Just verify it's a reasonable error message
                    assertNotNull(e.getMessage());
                }
            });
        }
    }
    
    @Test
    void readEnvironmentRegion_handlesEmptyBlob() throws SQLException {
        // Given: Empty BLOB (no cells)
        insertEmptyBlob(testRunId, 100);
        
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            SpatialRegion region = new SpatialRegion(new int[]{0, 10, 0, 10});
            
            // When: Query empty tick
            List<CellWithCoordinates> cells = reader.readEnvironmentRegion(100, region);
            
            // Then: Should return empty list (not error)
            assertNotNull(cells);
            assertTrue(cells.isEmpty());
        }
    }
    
    @Test
    void readEnvironmentRegion_handlesInvalidRegion() throws SQLException {
        // Given: Valid tick with data
        insertTestData(testRunId, 100);
        
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            // Given: Out-of-bounds region
            SpatialRegion outOfBoundsRegion = new SpatialRegion(new int[]{200, 300, 200, 300});
            
            // When: Query with out-of-bounds region
            List<CellWithCoordinates> cells = reader.readEnvironmentRegion(100, outOfBoundsRegion);
            
            // Then: Should return empty list (no cells in that region)
            assertNotNull(cells);
            assertTrue(cells.isEmpty());
        }
    }
    
    @Test
    void readEnvironmentRegion_handlesNullRegion() throws SQLException {
        // Given: Valid tick with data
        insertTestData(testRunId, 100);
        
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            // When: Query with null region (all cells)
            List<CellWithCoordinates> cells = reader.readEnvironmentRegion(100, null);
            
            // Then: Should return all cells
            assertNotNull(cells);
            assertTrue(cells.size() > 0);  // Should have some cells
        }
    }
    
    private void setupTestMetadata(String runId) throws SQLException {
        try (IResourceSchemaAwareMetadataWriter writer = (IResourceSchemaAwareMetadataWriter) database.getWrappedResource(
                new ResourceContext("test", "meta-port", "db-meta-write", "test-db", Map.of()))) {
            
            writer.setSimulationRun(runId);
            
            SimulationMetadata metadata = SimulationMetadata.newBuilder()
                .setSimulationRunId(runId)
                .setStartTimeMs(System.currentTimeMillis())
                .setInitialSeed(12345L)
                .setSamplingInterval(1)
                .setEnvironment(EnvironmentConfig.newBuilder()
                    .setDimensions(2)
                    .addShape(100)
                    .addShape(100)
                    .addToroidal(true)
                    .addToroidal(true)
                    .build())
                .build();
            
            writer.insertMetadata(metadata);
        }
    }
    
    private void insertCorruptedBlob(String runId, long tick) throws SQLException {
        // Insert corrupted BLOB data for testing
        // Use reflection to access private dataSource field
        try {
            java.lang.reflect.Field dataSourceField = H2Database.class.getDeclaredField("dataSource");
            dataSourceField.setAccessible(true);
            com.zaxxer.hikari.HikariDataSource dataSource = (com.zaxxer.hikari.HikariDataSource) dataSourceField.get(database);
            
            try (Connection conn = dataSource.getConnection()) {
                // Use H2SchemaUtil.setSchema() - same as H2DatabaseReader
                org.evochora.datapipeline.utils.H2SchemaUtil.setSchema(conn, runId);
                
                // Create table if it doesn't exist
                conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS environment_ticks (" +
                    "tick_number BIGINT PRIMARY KEY, " +
                    "cells_blob BYTEA" +
                    ")"
                );
                
                PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO environment_ticks (tick_number, cells_blob) VALUES (?, ?)"
                );
                stmt.setLong(1, tick);
                stmt.setBytes(2, new byte[]{1, 2, 3, 4});  // Corrupted data
                stmt.executeUpdate();
            }
        } catch (Exception e) {
            throw new SQLException("Failed to insert corrupted blob", e);
        }
    }
    
    private void insertEmptyBlob(String runId, long tick) throws SQLException {
        // Insert empty BLOB for testing
        try {
            java.lang.reflect.Field dataSourceField = H2Database.class.getDeclaredField("dataSource");
            dataSourceField.setAccessible(true);
            com.zaxxer.hikari.HikariDataSource dataSource = (com.zaxxer.hikari.HikariDataSource) dataSourceField.get(database);
            
            try (Connection conn = dataSource.getConnection()) {
                // Use H2SchemaUtil.setSchema() - same as H2DatabaseReader
                org.evochora.datapipeline.utils.H2SchemaUtil.setSchema(conn, runId);
                
                // Create table if it doesn't exist
                conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS environment_ticks (" +
                    "tick_number BIGINT PRIMARY KEY, " +
                    "cells_blob BYTEA" +
                    ")"
                );
                
                PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO environment_ticks (tick_number, cells_blob) VALUES (?, ?)"
                );
                stmt.setLong(1, tick);
                stmt.setBytes(2, new byte[0]);  // Empty BLOB
                stmt.executeUpdate();
            }
        } catch (Exception e) {
            throw new SQLException("Failed to insert empty blob", e);
        }
    }
    
    private void insertTestData(String runId, long tick) throws SQLException {
        // Insert valid test data with a few cells
        try {
            java.lang.reflect.Field dataSourceField = H2Database.class.getDeclaredField("dataSource");
            dataSourceField.setAccessible(true);
            com.zaxxer.hikari.HikariDataSource dataSource = (com.zaxxer.hikari.HikariDataSource) dataSourceField.get(database);
            
            try (Connection conn = dataSource.getConnection()) {
                // Use H2SchemaUtil.setSchema() - same as H2DatabaseReader
                org.evochora.datapipeline.utils.H2SchemaUtil.setSchema(conn, runId);
                
                // Create table if it doesn't exist
                conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS environment_ticks (" +
                    "tick_number BIGINT PRIMARY KEY, " +
                    "cells_blob BYTEA" +
                    ")"
                );
                
                // Build a simple CellStateList protobuf
                org.evochora.datapipeline.api.contracts.CellStateList.Builder builder = 
                    org.evochora.datapipeline.api.contracts.CellStateList.newBuilder();
                
                // Add a few test cells
                builder.addCells(org.evochora.datapipeline.api.contracts.CellState.newBuilder()
                    .setFlatIndex(0)
                    .setMoleculeType(1)
                    .setMoleculeValue(255)
                    .setOwnerId(0)
                    .build());
                
                builder.addCells(org.evochora.datapipeline.api.contracts.CellState.newBuilder()
                    .setFlatIndex(1)
                    .setMoleculeType(1)
                    .setMoleculeValue(128)
                    .setOwnerId(0)
                    .build());
                
                byte[] data = builder.build().toByteArray();
                
                PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO environment_ticks (tick_number, cells_blob) VALUES (?, ?)"
                );
                stmt.setLong(1, tick);
                stmt.setBytes(2, data);
                stmt.executeUpdate();
            }
        } catch (Exception e) {
            throw new SQLException("Failed to insert test data", e);
        }
    }
}
