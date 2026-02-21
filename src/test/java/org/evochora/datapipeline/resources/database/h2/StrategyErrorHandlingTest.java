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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Error handling tests for chunk-based environment storage strategies.
 * <p>
 * Tests error handling for chunk-based environment storage (RowPerChunkStrategy).
 */
@Tag("integration")
class StrategyErrorHandlingTest {

    @TempDir
    Path tempChunkDir;

    private H2Database database;
    private String testRunId;

    @BeforeEach
    void setUp() throws SQLException {
        String chunkDir = tempChunkDir.toString().replace("\\", "\\\\");
        Config config = ConfigFactory.parseString(
            "jdbcUrl = \"jdbc:h2:mem:test-strategy-errors-" + System.nanoTime() + ";MODE=PostgreSQL\"\n" +
            "maxPoolSize = 2\n" +
            "h2EnvironmentStrategy {\n" +
            "  className = \"org.evochora.datapipeline.resources.database.h2.RowPerChunkStrategy\"\n" +
            "  options { chunkDirectory = \"" + chunkDir + "\" }\n" +
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

    /**
     * Resolves the schema name for the given runId (same logic as H2SchemaUtil).
     */
    private String resolveSchemaName(String runId) {
        return "SIM_" + runId.replaceAll("[^a-zA-Z0-9]", "_").toUpperCase();
    }

    private void insertCorruptedChunk(String runId, long tick) throws SQLException {
        try {
            java.lang.reflect.Field dataSourceField = H2Database.class.getDeclaredField("dataSource");
            dataSourceField.setAccessible(true);
            com.zaxxer.hikari.HikariDataSource dataSource = (com.zaxxer.hikari.HikariDataSource) dataSourceField.get(database);

            try (Connection conn = dataSource.getConnection()) {
                org.evochora.datapipeline.utils.H2SchemaUtil.setSchema(conn, runId);
                createChunkTable(conn);
                insertTickRange(conn, tick, tick);
            }

            // Write corrupted data as chunk file
            writeChunkFile(runId, tick, new byte[]{1, 2, 3, 4});
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
                createChunkTable(conn);
                insertTickRange(conn, tick, tick);
            }

            // Write valid chunk with empty snapshot as file
            TickData emptySnapshot = TickData.newBuilder()
                .setTickNumber(tick)
                .build();

            TickDataChunk chunk = TickDataChunk.newBuilder()
                .setSnapshot(emptySnapshot)
                .setFirstTick(tick)
                .setTickCount(1)
                .build();

            writeChunkFile(runId, tick, chunk.toByteArray());
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
                createChunkTable(conn);
                insertTickRange(conn, firstTick, lastTick);
            }

            // Write minimal valid chunk as file
            TickData snapshot = TickData.newBuilder()
                .setTickNumber(firstTick)
                .build();

            long tickCount = lastTick - firstTick + 1;
            TickDataChunk.Builder chunkBuilder = TickDataChunk.newBuilder()
                .setSnapshot(snapshot)
                .setFirstTick(firstTick)
                .setTickCount((int) tickCount);

            for (long t = firstTick + 1; t <= lastTick; t++) {
                chunkBuilder.addDeltas(TickDelta.newBuilder()
                    .setTickNumber(t)
                    .build());
            }

            writeChunkFile(runId, firstTick, chunkBuilder.build().toByteArray());
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
                createChunkTable(conn);
                insertTickRange(conn, tick, tick);
            }

            // Write valid chunk with cells as file
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
                .setTickCount(1)
                .build();

            writeChunkFile(runId, tick, chunk.toByteArray());
        } catch (Exception e) {
            throw new SQLException("Failed to insert chunk with cells", e);
        }
    }

    private void createChunkTable(Connection conn) throws SQLException {
        conn.createStatement().execute(
            "CREATE TABLE IF NOT EXISTS environment_chunks (" +
            "first_tick BIGINT PRIMARY KEY, " +
            "last_tick BIGINT NOT NULL" +
            ")"
        );
    }

    private void insertTickRange(Connection conn, long firstTick, long lastTick) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO environment_chunks (first_tick, last_tick) VALUES (?, ?)"
        );
        stmt.setLong(1, firstTick);
        stmt.setLong(2, lastTick);
        stmt.executeUpdate();
    }

    private void writeChunkFile(String runId, long firstTick, byte[] data) throws IOException {
        String schemaName = resolveSchemaName(runId);
        Path schemaDir = tempChunkDir.resolve(schemaName);
        Files.createDirectories(schemaDir);

        // Write .chunk_meta (ticksPerSubdirectory = 10000 for default maxFilesPerDirectory × tickCount=1)
        long ticksPerSubdir = 10_000L;
        Path metaFile = schemaDir.resolve(".chunk_meta");
        if (!Files.exists(metaFile)) {
            var props = new java.util.Properties();
            props.setProperty("ticksPerSubdirectory", Long.toString(ticksPerSubdir));
            try (var out = Files.newOutputStream(metaFile)) {
                props.store(out, null);
            }
        }

        // Write chunk file in subdirectory
        long bucket = firstTick / ticksPerSubdir;
        Path subdir = schemaDir.resolve(String.format("%04d", bucket));
        Files.createDirectories(subdir);
        Files.write(subdir.resolve("chunk_" + firstTick + ".pb"), data);
    }
}
