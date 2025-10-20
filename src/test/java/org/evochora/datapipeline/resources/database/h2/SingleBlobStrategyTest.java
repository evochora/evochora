package org.evochora.datapipeline.resources.database.h2;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.CellState;
import org.evochora.datapipeline.api.contracts.CellStateList;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;
import org.evochora.datapipeline.utils.compression.NoneCodec;
import org.evochora.datapipeline.utils.compression.ZstdCodec;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.evochora.junit.extensions.logging.ExpectLog;

/**
 * Unit tests for SingleBlobStrategy.
 * <p>
 * Tests compression configuration, table creation, PreparedStatement caching,
 * and serialization behavior.
 */
@Tag("unit")
class SingleBlobStrategyTest {
    
    private SingleBlobStrategy strategy;
    private Connection mockConnection;
    private Statement mockStatement;
    private PreparedStatement mockPreparedStatement;
    
    @BeforeEach
    void setUp() throws SQLException {
        mockConnection = mock(Connection.class);
        mockStatement = mock(Statement.class);
        mockPreparedStatement = mock(PreparedStatement.class);
        
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
    }
    
    @Test
    void testConstructor_NoCompression() {
        // Given: Config without compression section
        Config config = ConfigFactory.empty();
        
        // When: Create strategy
        strategy = new SingleBlobStrategy(config);
        
        // Then: Should use NoneCodec
        assertThat(strategy.codec).isInstanceOf(NoneCodec.class);
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
        strategy = new SingleBlobStrategy(config);
        
        // Then: Should use ZstdCodec
        assertThat(strategy.codec).isInstanceOf(ZstdCodec.class);
    }
    
    @Test
    void testCreateTables_CreatesTableAndIndex() throws SQLException {
        // Given: Strategy with no compression
        strategy = new SingleBlobStrategy(ConfigFactory.empty());
        
        // When: Create tables
        strategy.createTables(mockConnection, 2);
        
        // Then: Should execute both CREATE TABLE and CREATE INDEX
        verify(mockStatement, times(2)).execute(anyString());
        
        // Verify SQL strings contain expected keywords
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockStatement, times(2)).execute(sqlCaptor.capture());
        
        List<String> executedSql = sqlCaptor.getAllValues();
        assertThat(executedSql).hasSize(2);
        
        // First call: CREATE TABLE
        assertThat(executedSql.get(0))
            .contains("CREATE TABLE IF NOT EXISTS environment_ticks")
            .contains("tick_number BIGINT PRIMARY KEY")
            .contains("cells_blob BYTEA NOT NULL");
        
        // Second call: CREATE INDEX
        assertThat(executedSql.get(1))
            .contains("CREATE INDEX IF NOT EXISTS idx_env_tick")
            .contains("ON environment_ticks (tick_number)");
    }
    
    @Test
    void testCreateTables_CachesSqlString() throws SQLException {
        // Given: Strategy
        strategy = new SingleBlobStrategy(ConfigFactory.empty());
        
        // When: Create tables
        strategy.createTables(mockConnection, 3);
        
        // Then: mergeSql should be cached
        assertThat(strategy.mergeSql)
            .isEqualTo("MERGE INTO environment_ticks (tick_number, cells_blob) " +
                      "KEY (tick_number) VALUES (?, ?)");
    }
    
    @Test
    void testWriteTicks_EmptyList() throws SQLException {
        // Given: Strategy with empty tick list
        strategy = new SingleBlobStrategy(ConfigFactory.empty());
        strategy.mergeSql = "MERGE INTO environment_ticks (tick_number, cells_blob) " +
                           "KEY (tick_number) VALUES (?, ?)";
        
        // When: Write empty list
        strategy.writeTicks(mockConnection, mockPreparedStatement, List.of(), new EnvironmentProperties(new int[]{10, 10}, false));
        
        // Then: Should not execute any database operations
        verify(mockPreparedStatement, never()).setLong(anyInt(), anyLong());
        verify(mockPreparedStatement, never()).executeBatch();
    }
    
    @Test
    void testWriteTicks_SingleTick() throws SQLException {
        // Given: Strategy with one tick
        strategy = new SingleBlobStrategy(ConfigFactory.empty());
        strategy.mergeSql = "MERGE INTO environment_ticks (tick_number, cells_blob) " +
                           "KEY (tick_number) VALUES (?, ?)";
        
        TickData tick = createTickWithCells(1000L, 3);
        
        // When: Write single tick
        strategy.writeTicks(mockConnection, mockPreparedStatement, List.of(tick), new EnvironmentProperties(new int[]{10, 10}, false));
        
        // Then: Should use PreparedStatement and execute batch
        verify(mockPreparedStatement).setLong(eq(1), eq(1000L));
        verify(mockPreparedStatement).setBytes(eq(2), any(byte[].class));
        verify(mockPreparedStatement).addBatch();
        verify(mockPreparedStatement).executeBatch();
    }
    
    @Test
    void testWriteTicks_MultipleTicks() throws SQLException {
        // Given: Strategy with multiple ticks
        strategy = new SingleBlobStrategy(ConfigFactory.empty());
        strategy.mergeSql = "MERGE INTO environment_ticks (tick_number, cells_blob) " +
                           "KEY (tick_number) VALUES (?, ?)";
        
        TickData tick1 = createTickWithCells(1000L, 2);
        TickData tick2 = createTickWithCells(1001L, 3);
        TickData tick3 = createTickWithCells(1002L, 1);
        
        // When: Write multiple ticks
        strategy.writeTicks(mockConnection, mockPreparedStatement, List.of(tick1, tick2, tick3), 
                           new EnvironmentProperties(new int[]{10, 10}, false));
        
        // Then: Should add all ticks to batch and execute once
        verify(mockPreparedStatement, times(3)).setLong(eq(1), anyLong());
        verify(mockPreparedStatement, times(3)).setBytes(eq(2), any(byte[].class));
        verify(mockPreparedStatement, times(3)).addBatch();
        verify(mockPreparedStatement, times(1)).executeBatch();
    }
    
    @Test
    void testGetMergeSql_ReturnsCorrectSql() throws SQLException {
        // Given: Strategy with tables created
        strategy = new SingleBlobStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection, 2);
        
        // When: Get SQL
        String sql = strategy.getMergeSql();
        
        // Then: Should return correct MERGE statement
        assertThat(sql).isEqualTo("MERGE INTO environment_ticks (tick_number, cells_blob) " +
                                 "KEY (tick_number) VALUES (?, ?)");
    }
    
    @Test
    void testSerializeTickCells_WithoutCompression() throws SQLException {
        // Given: Strategy with no compression
        strategy = new SingleBlobStrategy(ConfigFactory.empty());
        strategy.mergeSql = "MERGE INTO environment_ticks (tick_number, cells_blob) " +
                           "KEY (tick_number) VALUES (?, ?)";
        
        TickData tick = createTickWithCells(1000L, 2);
        
        // When: Write tick
        strategy.writeTicks(mockConnection, mockPreparedStatement, List.of(tick), new EnvironmentProperties(new int[]{10, 10}, false));
        
        // Then: Should serialize cells to protobuf
        ArgumentCaptor<byte[]> blobCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockPreparedStatement).setBytes(eq(2), blobCaptor.capture());
        
        byte[] serializedBlob = blobCaptor.getValue();
        assertThat(serializedBlob).isNotEmpty();
        
        // Verify it's valid protobuf by deserializing
        try {
            CellStateList deserialized = CellStateList.parseFrom(serializedBlob);
            assertThat(deserialized.getCellsList()).hasSize(2);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to deserialize protobuf", e);
        }
    }
    
    @Test
    void testSerializeTickCells_WithCompression() throws SQLException {
        // Given: Strategy with zstd compression
        Config config = ConfigFactory.parseString("""
            compression {
              enabled = true
              codec = "zstd"
              level = 1
            }
            """);
        strategy = new SingleBlobStrategy(config);
        strategy.mergeSql = "MERGE INTO environment_ticks (tick_number, cells_blob) " +
                           "KEY (tick_number) VALUES (?, ?)";
        
        TickData tick = createTickWithCells(1000L, 2);
        
        // When: Write tick
        strategy.writeTicks(mockConnection, mockPreparedStatement, List.of(tick), new EnvironmentProperties(new int[]{10, 10}, false));
        
        // Then: Should serialize and compress cells
        ArgumentCaptor<byte[]> blobCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockPreparedStatement).setBytes(eq(2), blobCaptor.capture());
        
        byte[] serializedBlob = blobCaptor.getValue();
        assertThat(serializedBlob).isNotEmpty();
        
        // With compression, the blob should be smaller than uncompressed protobuf
        // (exact size depends on compression ratio, but should be reasonable)
        assertThat(serializedBlob.length).isLessThan(1000); // Reasonable upper bound
    }
    
    @Test
    @ExpectLog(level = org.evochora.junit.extensions.logging.LogLevel.WARN,
               loggerPattern = "org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy",
               messagePattern = ".*empty cell list - skipping database write.*")
    @ExpectLog(level = org.evochora.junit.extensions.logging.LogLevel.WARN,
               loggerPattern = "org.evochora.datapipeline.resources.database.h2.SingleBlobStrategy",
               messagePattern = ".*no database writes performed.*")
    void testWriteTicks_EmptyCells() throws SQLException {
        // Given: Strategy with empty tick (resilience test - shouldn't happen in practice)
        strategy = new SingleBlobStrategy(ConfigFactory.empty());
        strategy.mergeSql = "MERGE INTO environment_ticks (tick_number, cells_blob) " +
                           "KEY (tick_number) VALUES (?, ?)";
        
        TickData emptyTick = TickData.newBuilder()
            .setTickNumber(1000L)
            .build(); // No cells
        
        // When: Write empty tick
        strategy.writeTicks(mockConnection, mockPreparedStatement, List.of(emptyTick), new EnvironmentProperties(new int[]{10, 10}, false));
        
        // Then: Should skip empty tick (no database operations)
        verify(mockPreparedStatement, never()).setBytes(anyInt(), any(byte[].class));
        verify(mockPreparedStatement, never()).executeBatch();
    }
    
    @Test
    void testWriteTicks_SQLException() throws SQLException {
        // Given: Strategy that will throw SQLException
        strategy = new SingleBlobStrategy(ConfigFactory.empty());
        strategy.mergeSql = "MERGE INTO environment_ticks (tick_number, cells_blob) " +
                           "KEY (tick_number) VALUES (?, ?)";
        
        when(mockPreparedStatement.executeBatch()).thenThrow(new SQLException("Database error"));
        
        TickData tick = createTickWithCells(1000L, 1);
        
        // When/Then: Should propagate SQLException
        assertThatThrownBy(() -> strategy.writeTicks(mockConnection, mockPreparedStatement, List.of(tick), 
                                                    new EnvironmentProperties(new int[]{10, 10}, false)))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("Database error");
    }
    
    // Helper methods
    
    private CellState createCell(int flatIndex, int type, int value) {
        return CellState.newBuilder()
            .setFlatIndex(flatIndex)
            .setMoleculeType(type)
            .setMoleculeValue(value)
            .setOwnerId(0)
            .build();
    }
    
    private TickData createTickWithCells(long tickNumber, int cellCount) {
        TickData.Builder builder = TickData.newBuilder().setTickNumber(tickNumber);
        for (int i = 0; i < cellCount; i++) {
            builder.addCells(createCell(i, 1, i * 10));
        }
        return builder.build();
    }
}
