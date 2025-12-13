package org.evochora.datapipeline.resources.database.h2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.OrganismStateList;
import org.evochora.datapipeline.api.contracts.RegisterValue;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.utils.compression.NoneCodec;
import org.evochora.datapipeline.utils.compression.ZstdCodec;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import com.google.protobuf.InvalidProtocolBufferException;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for SingleBlobOrgStrategy.
 * <p>
 * Tests compression configuration, table creation, PreparedStatement caching,
 * and serialization behavior for organism data.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class SingleBlobOrgStrategyTest {
    
    private SingleBlobOrgStrategy strategy;
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
        strategy = new SingleBlobOrgStrategy(config);
        
        // Then: Should use NoneCodec
        assertThat(strategy.getCodec()).isInstanceOf(NoneCodec.class);
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
        strategy = new SingleBlobOrgStrategy(config);
        
        // Then: Should use ZstdCodec
        assertThat(strategy.getCodec()).isInstanceOf(ZstdCodec.class);
    }
    
    @Test
    void testCreateTables_CreatesBothTables() throws SQLException {
        // Given: Strategy with no compression
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        
        // When: Create tables
        strategy.createTables(mockConnection);
        
        // Then: Should execute CREATE TABLE for both organisms and organism_ticks
        verify(mockStatement, times(2)).execute(anyString());
        
        // Verify SQL strings
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockStatement, times(2)).execute(sqlCaptor.capture());
        
        List<String> executedSql = sqlCaptor.getAllValues();
        assertThat(executedSql).hasSize(2);
        
        // First call: CREATE TABLE organisms
        assertThat(executedSql.get(0))
            .contains("CREATE TABLE IF NOT EXISTS organisms")
            .contains("organism_id INT PRIMARY KEY");
        
        // Second call: CREATE TABLE organism_ticks
        assertThat(executedSql.get(1))
            .contains("CREATE TABLE IF NOT EXISTS organism_ticks")
            .contains("tick_number BIGINT PRIMARY KEY")
            .contains("organisms_blob BYTEA NOT NULL");
    }
    
    @Test
    void testCreateTables_CachesSqlStrings() throws SQLException {
        // Given: Strategy
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        
        // When: Create tables
        strategy.createTables(mockConnection);
        
        // Then: SQL strings should be cached
        assertThat(strategy.getOrganismsMergeSql())
            .contains("MERGE INTO organisms")
            .contains("KEY (organism_id)");
        
        assertThat(strategy.getStatesMergeSql())
            .isEqualTo("MERGE INTO organism_ticks (tick_number, organisms_blob) " +
                      "KEY (tick_number) VALUES (?, ?)");
    }
    
    @Test
    void testWriteStates_EmptyList() throws SQLException {
        // Given: Strategy with empty tick list
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);
        
        // When: Write empty list
        strategy.writeStates(mockConnection, mockPreparedStatement, List.of());
        
        // Then: Should not execute any database operations
        verify(mockPreparedStatement, never()).setLong(anyInt(), anyLong());
        verify(mockPreparedStatement, never()).executeBatch();
    }
    
    @Test
    void testWriteStates_SingleTick() throws SQLException {
        // Given: Strategy with one tick
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);
        
        TickData tick = createTickWithOrganisms(1000L, 3);
        
        // When: Write single tick
        strategy.writeStates(mockConnection, mockPreparedStatement, List.of(tick));
        
        // Then: Should use PreparedStatement and execute batch
        verify(mockPreparedStatement).setLong(eq(1), eq(1000L));
        verify(mockPreparedStatement).setBytes(eq(2), any(byte[].class));
        verify(mockPreparedStatement).addBatch();
        verify(mockPreparedStatement).executeBatch();
    }
    
    @Test
    void testWriteStates_MultipleTicks() throws SQLException {
        // Given: Strategy with multiple ticks
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);
        
        TickData tick1 = createTickWithOrganisms(1000L, 2);
        TickData tick2 = createTickWithOrganisms(1001L, 3);
        TickData tick3 = createTickWithOrganisms(1002L, 1);
        
        // When: Write multiple ticks
        strategy.writeStates(mockConnection, mockPreparedStatement, List.of(tick1, tick2, tick3));
        
        // Then: Should add all ticks to batch and execute once
        verify(mockPreparedStatement, times(3)).setLong(eq(1), anyLong());
        verify(mockPreparedStatement, times(3)).setBytes(eq(2), any(byte[].class));
        verify(mockPreparedStatement, times(3)).addBatch();
        verify(mockPreparedStatement, times(1)).executeBatch();
    }
    
    @Test
    void testSerializeOrganisms_WithoutCompression() throws SQLException {
        // Given: Strategy with no compression
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);
        
        TickData tick = createTickWithOrganisms(1000L, 2);
        
        // When: Write tick
        strategy.writeStates(mockConnection, mockPreparedStatement, List.of(tick));
        
        // Then: Should serialize organisms to protobuf
        ArgumentCaptor<byte[]> blobCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockPreparedStatement).setBytes(eq(2), blobCaptor.capture());
        
        byte[] serializedBlob = blobCaptor.getValue();
        assertThat(serializedBlob).isNotEmpty();
        
        // Verify it's valid protobuf by deserializing
        try {
            OrganismStateList deserialized = OrganismStateList.parseFrom(serializedBlob);
            assertThat(deserialized.getOrganismsList()).hasSize(2);
            assertThat(deserialized.getOrganisms(0).getOrganismId()).isEqualTo(0);
            assertThat(deserialized.getOrganisms(1).getOrganismId()).isEqualTo(1);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to deserialize protobuf", e);
        }
    }
    
    @Test
    void testSerializeOrganisms_WithCompression() throws SQLException {
        // Given: Strategy with zstd compression
        Config config = ConfigFactory.parseString("""
            compression {
              enabled = true
              codec = "zstd"
              level = 1
            }
            """);
        strategy = new SingleBlobOrgStrategy(config);
        strategy.createTables(mockConnection);
        
        TickData tick = createTickWithOrganisms(1000L, 5);
        
        // When: Write tick
        strategy.writeStates(mockConnection, mockPreparedStatement, List.of(tick));
        
        // Then: Should serialize and compress organisms
        ArgumentCaptor<byte[]> blobCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockPreparedStatement).setBytes(eq(2), blobCaptor.capture());
        
        byte[] serializedBlob = blobCaptor.getValue();
        assertThat(serializedBlob).isNotEmpty();
        
        // With compression, the blob should be reasonable size
        assertThat(serializedBlob.length).isLessThan(5000); // Reasonable upper bound
    }
    
    @Test
    void testWriteStates_TickWithoutOrganisms() throws SQLException {
        // Given: Strategy with tick that has no organisms
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);
        
        TickData emptyTick = TickData.newBuilder()
            .setTickNumber(1000L)
            .build(); // No organisms
        
        // When: Write empty tick
        strategy.writeStates(mockConnection, mockPreparedStatement, List.of(emptyTick));
        
        // Then: Should skip empty tick (no database operations)
        verify(mockPreparedStatement, never()).setBytes(anyInt(), any(byte[].class));
        verify(mockPreparedStatement, never()).executeBatch();
    }
    
    @Test
    void testWriteStates_SQLException() throws SQLException {
        // Given: Strategy that will throw SQLException
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);
        
        when(mockPreparedStatement.executeBatch()).thenThrow(new SQLException("Database error"));
        
        TickData tick = createTickWithOrganisms(1000L, 1);
        
        // When/Then: Should propagate SQLException
        assertThatThrownBy(() -> strategy.writeStates(mockConnection, mockPreparedStatement, List.of(tick)))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("Database error");
    }
    
    @Test
    void testWriteOrganisms_ExtractsUniqueOrganisms() throws SQLException {
        // Given: Strategy with multiple ticks containing same organism
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);
        
        // Same organism appears in both ticks
        OrganismState org1 = createOrganism(1, 100);
        TickData tick1 = TickData.newBuilder().setTickNumber(1L).addOrganisms(org1).build();
        TickData tick2 = TickData.newBuilder().setTickNumber(2L).addOrganisms(org1).build();
        
        // When: Write organisms
        strategy.writeOrganisms(mockConnection, mockPreparedStatement, List.of(tick1, tick2));
        
        // Then: Should only add organism once (deduplication)
        verify(mockPreparedStatement, times(1)).addBatch();
        verify(mockPreparedStatement, times(1)).executeBatch();
    }
    
    @Test
    void testReadSingleOrganismState_NotFound() throws SQLException {
        // Given: Strategy with mock that returns empty result
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        
        ResultSet mockResultSet = mock(ResultSet.class);
        when(mockResultSet.next()).thenReturn(false);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        
        // When: Read non-existent organism
        OrganismState result = strategy.readSingleOrganismState(mockConnection, 1000L, 999);
        
        // Then: Should return null
        assertThat(result).isNull();
    }
    
    @Test
    void testGetAvailableTickRange_EmptyTable() throws SQLException {
        // Given: Strategy with mock that returns null for min/max
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        
        ResultSet mockResultSet = mock(ResultSet.class);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong("min_tick")).thenReturn(0L);
        when(mockResultSet.wasNull()).thenReturn(true); // Table is empty
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        
        // When: Get tick range from empty table
        var result = strategy.getAvailableTickRange(mockConnection);
        
        // Then: Should return null
        assertThat(result).isNull();
    }
    
    // ==================== Helper Methods ====================
    
    private OrganismState createOrganism(int id, int energy) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setBirthTick(0)
            .setProgramId("prog-" + id)
            .setInitialPosition(Vector.newBuilder().addComponents(0).addComponents(0).build())
            .setEnergy(energy)
            .setIp(Vector.newBuilder().addComponents(1).build())
            .setDv(Vector.newBuilder().addComponents(0).addComponents(1).build())
            .addDataPointers(Vector.newBuilder().addComponents(5).build())
            .setActiveDpIndex(0)
            .addDataRegisters(RegisterValue.newBuilder().setScalar(7).build())
            .build();
    }
    
    private TickData createTickWithOrganisms(long tickNumber, int organismCount) {
        TickData.Builder builder = TickData.newBuilder().setTickNumber(tickNumber);
        for (int i = 0; i < organismCount; i++) {
            builder.addOrganisms(createOrganism(i, 100 + i * 10));
        }
        return builder.build();
    }
}
