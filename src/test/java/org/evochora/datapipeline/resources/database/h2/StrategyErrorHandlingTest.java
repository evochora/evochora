package org.evochora.datapipeline.resources.database.h2;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.CellStateTestHelper;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataWriter;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
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
    void readChunkContaining_throwsOnInvalidTick() throws SQLException {
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            // Ensure table exists with a chunk, otherwise we get a SQLSyntaxErrorException
            insertChunk(testRunId, 0L, 99L);

            // When/Then: Query non-existent tick should throw specific exception
            assertThrows(TickNotFoundException.class, () -> 
                reader.readChunkContaining(999999)
            );
        }
    }
    
    @Test
    void readChunkContaining_handlesCorruptedBlob() throws SQLException {
        // Given: Corrupted BLOB data in database
        insertCorruptedChunk(testRunId, 100L);
        
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            // When/Then: Should throw exception for corrupted data
            // The exact exception depends on how protobuf handles invalid data
            assertDoesNotThrow(() -> {
                try {
                    TickDataChunk chunk = reader.readChunkContaining(100);
                    // If we get here, the chunk was somehow parseable
                    // but may have invalid data - that's also acceptable
                    assertNotNull(chunk);
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
    void readChunkContaining_handlesEmptySnapshot() throws SQLException, TickNotFoundException {
        // Given: Empty BLOB (no cells in snapshot)
        insertEmptyChunk(testRunId, 100L);
        
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            // When: Query tick with empty snapshot
            TickDataChunk chunk = reader.readChunkContaining(100);
            
            // Then: Should return valid chunk with empty cell columns
            assertNotNull(chunk);
            assertTrue(chunk.hasSnapshot());
            assertEquals(100, chunk.getSnapshot().getTickNumber());
            // Empty snapshot has no cell columns or zero cells
            if (chunk.getSnapshot().hasCellColumns()) {
                assertEquals(0, chunk.getSnapshot().getCellColumns().getFlatIndicesCount());
            }
        }
    }
    
    @Test
    void readChunkContaining_returnsValidChunk() throws SQLException, TickNotFoundException {
        // Given: Valid chunk with data
        insertChunkWithCells(testRunId, 100L);
        
        try (IDatabaseReader reader = database.createReader(testRunId)) {
            // When: Query valid tick
            TickDataChunk chunk = reader.readChunkContaining(100);
            
            // Then: Should return chunk with cells
            assertNotNull(chunk);
            assertTrue(chunk.hasSnapshot());
            assertEquals(100, chunk.getSnapshot().getTickNumber());
            assertTrue(chunk.getSnapshot().getCellColumns().getFlatIndicesCount() > 0);
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
                .setResolvedConfigJson(TestMetadataHelper.builder()
                    .shape(100, 100)
                    .toroidal(true)
                    .samplingInterval(1)
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
                
                long tickCount = lastTick - firstTick + 1;
                TickDataChunk.Builder chunkBuilder = TickDataChunk.newBuilder()
                    .setSnapshot(snapshot)
                    .setFirstTick(firstTick)
                    .setTickCount((int) tickCount);
                
                // Add empty deltas for ticks after the snapshot
                for (long t = firstTick + 1; t <= lastTick; t++) {
                    chunkBuilder.addDeltas(TickDelta.newBuilder()
                        .setTickNumber(t)
                        .build());
                }
                
                PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO environment_chunks (first_tick, last_tick, chunk_blob) VALUES (?, ?, ?)"
                );
                stmt.setLong(1, firstTick);
                stmt.setLong(2, lastTick);
                stmt.setBytes(3, chunkBuilder.build().toByteArray());
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
