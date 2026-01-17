package org.evochora.datapipeline.resources.database.h2;

import org.evochora.datapipeline.api.contracts.EnvironmentConfig;
import org.evochora.datapipeline.CellStateTestHelper;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataWriter;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.SpatialRegion;
import org.evochora.datapipeline.resources.database.H2Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 error handling tests: Strategy failures and edge cases.
 * <p>
 * Tests error handling for chunk-based environment storage (RowPerChunkStrategy).
 */
@Tag("integration")
class StrategyErrorHandlingTest {
    
    private H2Database database;
    private String testRunId;
    
    @BeforeEach
    void setUp() throws SQLException {
        Config config = ConfigFactory.parseString(
            "jdbcUrl = \"jdbc:h2:mem:test-strategy-errors-" + System.nanoTime() + ";MODE=PostgreSQL\"\n" +
            "maxPoolSize = 2\n" +
            "h2EnvironmentStrategy {\n" +
            "  className = \"org.evochora.datapipeline.resources.database.h2.RowPerChunkStrategy\"\n" +
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
            // Ensure table exists with a chunk, otherwise we get a SQLSyntaxErrorException
            insertChunk(testRunId, 1L, 1L);

            SpatialRegion region = new SpatialRegion(new int[]{0, 10, 0, 10});
            
            // When/Then: Query non-existent tick should throw specific exception
            assertThrows(TickNotFoundException.class, () -> 
                reader.readEnvironmentRegion(999999, region)
            );
        }
    }
    
    @Test
    void readEnvironmentRegion_handlesCorruptedBlob() throws SQLException {
        // Given: Corrupted BLOB data in database
        insertCorruptedChunk(testRunId, 100L);
        
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            SpatialRegion region = new SpatialRegion(new int[]{0, 10, 0, 10});
            
            // When/Then: Should handle corruption gracefully
            // This test verifies that corrupted data doesn't crash the application
            // The exact behavior (exception vs empty list) depends on implementation
            assertDoesNotThrow(() -> {
                try {
                    reader.readEnvironmentRegion(100, region);
                } catch (SQLException e) {
                    // SQLException is acceptable for corrupted data
                    assertNotNull(e.getMessage());
                } catch (TickNotFoundException e) {
                    // Also acceptable - corrupted chunk may fail to parse
                    assertNotNull(e.getMessage());
                }
            });
        }
    }
    
    @Test
    void readEnvironmentRegion_handlesEmptyBlob() throws SQLException, TickNotFoundException {
        // Given: Empty BLOB (no cells in snapshot)
        insertEmptyChunk(testRunId, 100L);
        
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            SpatialRegion region = new SpatialRegion(new int[]{0, 10, 0, 10});
            
            // When: Query tick with empty snapshot
            var cells = reader.readEnvironmentRegion(100, region);
            
            // Then: Should return empty list (not error)
            assertNotNull(cells);
            assertTrue(cells.isEmpty());
        }
    }
    
    @Test
    void readEnvironmentRegion_handlesInvalidRegion() throws SQLException, TickNotFoundException {
        // Given: Valid chunk with data
        insertChunkWithCells(testRunId, 100L);
        
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            // Given: Out-of-bounds region
            SpatialRegion outOfBoundsRegion = new SpatialRegion(new int[]{200, 300, 200, 300});
            
            // When: Query with out-of-bounds region
            var cells = reader.readEnvironmentRegion(100, outOfBoundsRegion);
            
            // Then: Should return empty list (no cells in that region)
            assertNotNull(cells);
            assertTrue(cells.isEmpty());
        }
    }
    
    @Test
    void readEnvironmentRegion_handlesNullRegion() throws SQLException, TickNotFoundException {
        // Given: Valid chunk with data
        insertChunkWithCells(testRunId, 100L);
        
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            // When: Query with null region (all cells)
            var cells = reader.readEnvironmentRegion(100, null);
            
            // Then: Should return all cells
            assertNotNull(cells);
            assertTrue(cells.size() > 0);
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
    
    private void insertCorruptedChunk(String runId, long tick) throws SQLException {
        try {
            java.lang.reflect.Field dataSourceField = H2Database.class.getDeclaredField("dataSource");
            dataSourceField.setAccessible(true);
            com.zaxxer.hikari.HikariDataSource dataSource = (com.zaxxer.hikari.HikariDataSource) dataSourceField.get(database);
            
            try (Connection conn = dataSource.getConnection()) {
                org.evochora.datapipeline.utils.H2SchemaUtil.setSchema(conn, runId);
                
                // Create chunk table
                conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS environment_chunks (" +
                    "first_tick BIGINT PRIMARY KEY, " +
                    "last_tick BIGINT NOT NULL, " +
                    "chunk_blob BYTEA NOT NULL" +
                    ")"
                );
                
                PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO environment_chunks (first_tick, last_tick, chunk_blob) VALUES (?, ?, ?)"
                );
                stmt.setLong(1, tick);
                stmt.setLong(2, tick);
                stmt.setBytes(3, new byte[]{1, 2, 3, 4});  // Corrupted data
                stmt.executeUpdate();
            }
        } catch (Exception e) {
            throw new SQLException("Failed to insert corrupted chunk", e);
        }
    }
    
    private void insertEmptyChunk(String runId, long tick) throws SQLException {
        try {
            java.lang.reflect.Field dataSourceField = H2Database.class.getDeclaredField("dataSource");
            dataSourceField.setAccessible(true);
            com.zaxxer.hikari.HikariDataSource dataSource = (com.zaxxer.hikari.HikariDataSource) dataSourceField.get(database);
            
            try (Connection conn = dataSource.getConnection()) {
                org.evochora.datapipeline.utils.H2SchemaUtil.setSchema(conn, runId);
                
                // Create chunk table
                conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS environment_chunks (" +
                    "first_tick BIGINT PRIMARY KEY, " +
                    "last_tick BIGINT NOT NULL, " +
                    "chunk_blob BYTEA NOT NULL" +
                    ")"
                );
                
                // Create valid chunk with empty snapshot (no cells)
                TickData emptySnapshot = TickData.newBuilder()
                    .setTickNumber(tick)
                    .build();
                
                TickDataChunk chunk = TickDataChunk.newBuilder()
                    .setSnapshot(emptySnapshot)
                    .setFirstTick(tick)
                    .setTickCount(1)  // Must be deltas.size() + 1
                    .build();
                
                PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO environment_chunks (first_tick, last_tick, chunk_blob) VALUES (?, ?, ?)"
                );
                stmt.setLong(1, tick);
                stmt.setLong(2, tick);
                stmt.setBytes(3, chunk.toByteArray());
                stmt.executeUpdate();
            }
        } catch (Exception e) {
            throw new SQLException("Failed to insert empty chunk", e);
        }
    }
    
    private void insertChunk(String runId, long firstTick, long lastTick) throws SQLException {
        try {
            java.lang.reflect.Field dataSourceField = H2Database.class.getDeclaredField("dataSource");
            dataSourceField.setAccessible(true);
            com.zaxxer.hikari.HikariDataSource dataSource = (com.zaxxer.hikari.HikariDataSource) dataSourceField.get(database);
            
            try (Connection conn = dataSource.getConnection()) {
                org.evochora.datapipeline.utils.H2SchemaUtil.setSchema(conn, runId);
                
                // Create chunk table
                conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS environment_chunks (" +
                    "first_tick BIGINT PRIMARY KEY, " +
                    "last_tick BIGINT NOT NULL, " +
                    "chunk_blob BYTEA NOT NULL" +
                    ")"
                );
                
                // Create minimal valid chunk
                TickData snapshot = TickData.newBuilder()
                    .setTickNumber(firstTick)
                    .build();
                
                TickDataChunk chunk = TickDataChunk.newBuilder()
                    .setSnapshot(snapshot)
                    .setFirstTick(firstTick)
                    .setTickCount(1)  // Must be deltas.size() + 1
                    .build();
                
                PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO environment_chunks (first_tick, last_tick, chunk_blob) VALUES (?, ?, ?)"
                );
                stmt.setLong(1, firstTick);
                stmt.setLong(2, lastTick);
                stmt.setBytes(3, chunk.toByteArray());
                stmt.executeUpdate();
            }
        } catch (Exception e) {
            throw new SQLException("Failed to insert chunk", e);
        }
    }
    
    private void insertChunkWithCells(String runId, long tick) throws SQLException {
        try {
            java.lang.reflect.Field dataSourceField = H2Database.class.getDeclaredField("dataSource");
            dataSourceField.setAccessible(true);
            com.zaxxer.hikari.HikariDataSource dataSource = (com.zaxxer.hikari.HikariDataSource) dataSourceField.get(database);
            
            try (Connection conn = dataSource.getConnection()) {
                org.evochora.datapipeline.utils.H2SchemaUtil.setSchema(conn, runId);
                
                // Create chunk table
                conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS environment_chunks (" +
                    "first_tick BIGINT PRIMARY KEY, " +
                    "last_tick BIGINT NOT NULL, " +
                    "chunk_blob BYTEA NOT NULL" +
                    ")"
                );
                
                // Create valid chunk with cells
                TickData snapshot = TickData.newBuilder()
                    .setTickNumber(tick)
                    .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                        CellStateTestHelper.createCellState(0, 0, 1, 255, 0),
                        CellStateTestHelper.createCellState(1, 0, 1, 128, 0)
                    )))
                    .build();
                
                TickDataChunk chunk = TickDataChunk.newBuilder()
                    .setSnapshot(snapshot)
                    .setFirstTick(tick)
                    .setTickCount(1)  // Must be deltas.size() + 1
                    .build();
                
                PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO environment_chunks (first_tick, last_tick, chunk_blob) VALUES (?, ?, ?)"
                );
                stmt.setLong(1, tick);
                stmt.setLong(2, tick);
                stmt.setBytes(3, chunk.toByteArray());
                stmt.executeUpdate();
            }
        } catch (Exception e) {
            throw new SQLException("Failed to insert chunk with cells", e);
        }
    }
}
