package org.evochora.datapipeline.resources.database.h2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.evochora.datapipeline.CellStateTestHelper;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.CellState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.utils.compression.NoneCodec;
import org.evochora.datapipeline.utils.compression.ZstdCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for RowPerChunkStrategy.
 * <p>
 * Tests compression configuration, table creation, chunk serialization,
 * and read/write behavior for delta-compressed environment data.
 */
@Tag("unit")
class RowPerChunkStrategyTest {
    
    private RowPerChunkStrategy strategy;
    private Connection mockConnection;
    private Statement mockStatement;
    private PreparedStatement mockPreparedStatement;
    private ResultSet mockResultSet;
    
    @BeforeEach
    void setUp() throws SQLException {
        mockConnection = mock(Connection.class);
        mockStatement = mock(Statement.class);
        mockPreparedStatement = mock(PreparedStatement.class);
        mockResultSet = mock(ResultSet.class);
        
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);
    }
    
    @Test
    void testConstructor_NoCompression() {
        // Given: Config without compression section
        Config config = ConfigFactory.empty();
        
        // When: Create strategy
        strategy = new RowPerChunkStrategy(config);
        
        // Then: Should use NoneCodec
        assertThat(strategy).isNotNull();
    }
    
    @Test
    void testConstructor_WithZstdCompression() {
        // Given: Config with zstd compression
        Config config = ConfigFactory.parseString("""
            compression {
              enabled = true
              codec = "zstd"
              level = 3
            }
            """);
        
        // When: Create strategy
        strategy = new RowPerChunkStrategy(config);
        
        // Then: Should create successfully
        assertThat(strategy).isNotNull();
    }
    
    @Test
    void testCreateTables_CreatesTableAndIndex() throws SQLException {
        // Given: Strategy with no compression
        strategy = new RowPerChunkStrategy(ConfigFactory.empty());
        
        // When: Create tables
        strategy.createTables(mockConnection, 2);
        
        // Then: Should execute CREATE TABLE and CREATE INDEX
        verify(mockStatement, times(2)).execute(anyString());
        
        // Verify SQL strings
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockStatement, times(2)).execute(sqlCaptor.capture());
        
        List<String> executedSql = sqlCaptor.getAllValues();
        assertThat(executedSql).hasSize(2);
        
        // First call: CREATE TABLE
        assertThat(executedSql.get(0))
            .contains("CREATE TABLE IF NOT EXISTS environment_chunks")
            .contains("first_tick BIGINT PRIMARY KEY")
            .contains("last_tick BIGINT NOT NULL")
            .contains("chunk_blob BYTEA NOT NULL");
        
        // Second call: CREATE INDEX
        assertThat(executedSql.get(1))
            .contains("CREATE INDEX IF NOT EXISTS idx_env_chunks_last_tick");
    }
    
    @Test
    void testCreateTables_CachesSqlString() throws SQLException {
        // Given: Strategy
        strategy = new RowPerChunkStrategy(ConfigFactory.empty());
        
        // When: Create tables
        strategy.createTables(mockConnection, 3);
        
        // Then: mergeSql should be cached
        assertThat(strategy.getMergeSql())
            .contains("MERGE INTO environment_chunks")
            .contains("first_tick")
            .contains("last_tick")
            .contains("chunk_blob");
    }
    
    @Test
    void testWriteChunks_EmptyList() throws SQLException {
        // Given: Strategy with empty chunk list
        strategy = new RowPerChunkStrategy(ConfigFactory.empty());
        
        // When: Write empty list
        strategy.writeChunks(mockConnection, List.of());
        
        // Then: Should not execute any database operations
        verify(mockConnection, times(0)).prepareStatement(anyString());
    }
    
    @Test
    void testWriteChunks_SingleChunk() throws SQLException {
        // Given: Strategy with one chunk
        strategy = new RowPerChunkStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection, 2);
        
        TickDataChunk chunk = createChunkWithSnapshot(1000L);
        
        // When: Write single chunk
        strategy.writeChunks(mockConnection, List.of(chunk));
        
        // Then: Should use PreparedStatement and execute batch
        verify(mockPreparedStatement).setLong(eq(1), eq(1000L)); // first_tick
        verify(mockPreparedStatement).setLong(eq(2), eq(1000L)); // last_tick (no deltas)
        verify(mockPreparedStatement).addBatch();
        verify(mockPreparedStatement).executeBatch();
    }
    
    @Test
    void testWriteChunks_ChunkWithDeltas() throws SQLException {
        // Given: Strategy with chunk containing deltas
        strategy = new RowPerChunkStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection, 2);
        
        TickDataChunk chunk = createChunkWithDeltas(1000L, 1004L);
        
        // When: Write chunk
        strategy.writeChunks(mockConnection, List.of(chunk));
        
        // Then: Should calculate last_tick correctly
        verify(mockPreparedStatement).setLong(eq(1), eq(1000L)); // first_tick
        verify(mockPreparedStatement).setLong(eq(2), eq(1004L)); // last_tick (from last delta)
    }
    
    @Test
    void testReadChunkContaining_NotFound() throws SQLException {
        // Given: Strategy with no matching chunk
        strategy = new RowPerChunkStrategy(ConfigFactory.empty());
        when(mockResultSet.next()).thenReturn(false);
        
        // When/Then: Should throw TickNotFoundException
        assertThatThrownBy(() -> strategy.readChunkContaining(mockConnection, 500L))
            .isInstanceOf(TickNotFoundException.class)
            .hasMessageContaining("No chunk found containing tick 500");
    }
    
    @Test
    void testReadChunkContaining_EmptyBlob() throws SQLException {
        // Given: Strategy with empty blob
        strategy = new RowPerChunkStrategy(ConfigFactory.empty());
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getBytes("chunk_blob")).thenReturn(new byte[0]);
        
        // When/Then: Should throw TickNotFoundException
        assertThatThrownBy(() -> strategy.readChunkContaining(mockConnection, 500L))
            .isInstanceOf(TickNotFoundException.class)
            .hasMessageContaining("empty BLOB");
    }
    
    @Test
    void testGetMergeSql_ReturnsCorrectSql() throws SQLException {
        // Given: Strategy with tables created
        strategy = new RowPerChunkStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection, 2);
        
        // When: Get SQL
        String sql = strategy.getMergeSql();
        
        // Then: Should return correct MERGE statement
        assertThat(sql)
            .contains("MERGE INTO environment_chunks")
            .contains("first_tick")
            .contains("last_tick")
            .contains("chunk_blob")
            .contains("KEY (first_tick)");
    }
    
    // Helper methods
    
    private TickData createSnapshotWithCells(long tickNumber, int cellCount) {
        TickData.Builder builder = TickData.newBuilder().setTickNumber(tickNumber);
        java.util.List<CellState> cells = new java.util.ArrayList<>();
        for (int i = 0; i < cellCount; i++) {
            cells.add(CellStateTestHelper.createCellState(i, 0, 1, i * 10, 0));
        }
        builder.setCellColumns(CellStateTestHelper.createColumnsFromCells(cells));
        return builder.build();
    }
    
    private TickDataChunk createChunkWithSnapshot(long tickNumber) {
        return TickDataChunk.newBuilder()
            .setSnapshot(createSnapshotWithCells(tickNumber, 3))
            .build();
    }
    
    private TickDataChunk createChunkWithDeltas(long firstTick, long lastTick) {
        TickDataChunk.Builder builder = TickDataChunk.newBuilder()
            .setSnapshot(createSnapshotWithCells(firstTick, 3));
        
        // Add deltas for each tick between first and last
        for (long t = firstTick + 1; t <= lastTick; t++) {
            builder.addDeltas(TickDelta.newBuilder()
                .setTickNumber(t)
                .build());
        }
        
        return builder.build();
    }
}
