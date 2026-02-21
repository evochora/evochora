package org.evochora.datapipeline.resources.database.h2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
    void testAddOrganismTick_SingleTick() throws SQLException {
        // Given: Strategy with one tick containing 3 organisms
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);

        TickData tick = createTickWithOrganisms(1000L, 3);

        // When: Add tick and commit
        strategy.addOrganismTick(mockConnection, tick);
        strategy.commitOrganismWrites(mockConnection);

        // Then: addBatch called for 3 organism metadata + 1 state blob = 4
        verify(mockPreparedStatement, times(4)).addBatch();
        // executeBatch called for organisms batch + states batch
        verify(mockPreparedStatement, times(2)).executeBatch();
    }

    @Test
    void testAddOrganismTick_MultipleTicks() throws SQLException {
        // Given: Strategy with multiple ticks (organism IDs overlap: {0,1}, {0,1,2}, {0})
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);

        TickData tick1 = createTickWithOrganisms(1000L, 2);
        TickData tick2 = createTickWithOrganisms(1001L, 3);
        TickData tick3 = createTickWithOrganisms(1002L, 1);

        // When: Add all ticks and commit
        strategy.addOrganismTick(mockConnection, tick1);
        strategy.addOrganismTick(mockConnection, tick2);
        strategy.addOrganismTick(mockConnection, tick3);
        strategy.commitOrganismWrites(mockConnection);

        // Then: addBatch for 3 unique organism metadata (deduped) + 3 state blobs = 6
        verify(mockPreparedStatement, times(6)).addBatch();
        verify(mockPreparedStatement, times(2)).executeBatch();
    }

    @Test
    void testAddOrganismTick_SerializesWithoutCompression() throws SQLException {
        // Given: Strategy with no compression
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);

        TickData tick = createTickWithOrganisms(1000L, 2);

        // When: Add tick
        strategy.addOrganismTick(mockConnection, tick);

        // Then: Should serialize organisms to protobuf (no compression = raw protobuf)
        ArgumentCaptor<byte[]> blobCaptor = ArgumentCaptor.forClass(byte[].class);
        // setBytes called for organism metadata initial_position + state blob
        verify(mockPreparedStatement, times(3)).setBytes(anyInt(), blobCaptor.capture());

        // The last setBytes call is the state blob (organisms_blob)
        List<byte[]> captured = blobCaptor.getAllValues();
        byte[] stateBlob = captured.get(captured.size() - 1);
        assertThat(stateBlob).isNotEmpty();

        // Verify it's valid protobuf by deserializing
        try {
            OrganismStateList deserialized = OrganismStateList.parseFrom(stateBlob);
            assertThat(deserialized.getOrganismsList()).hasSize(2);
            assertThat(deserialized.getOrganisms(0).getOrganismId()).isEqualTo(0);
            assertThat(deserialized.getOrganisms(1).getOrganismId()).isEqualTo(1);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to deserialize protobuf", e);
        }
    }

    @Test
    void testAddOrganismTick_SerializesWithCompression() throws SQLException {
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

        // When: Add tick
        strategy.addOrganismTick(mockConnection, tick);

        // Then: Should serialize and compress organisms
        ArgumentCaptor<byte[]> blobCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockPreparedStatement, times(6)).setBytes(anyInt(), blobCaptor.capture());

        // Last setBytes call is the compressed state blob
        List<byte[]> captured = blobCaptor.getAllValues();
        byte[] stateBlob = captured.get(captured.size() - 1);
        assertThat(stateBlob).isNotEmpty();
        assertThat(stateBlob.length).isLessThan(5000);
    }

    @Test
    void testAddOrganismTick_SkipsTickWithoutOrganisms() throws SQLException {
        // Given: Strategy with tick that has no organisms
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);

        TickData emptyTick = TickData.newBuilder()
            .setTickNumber(1000L)
            .build(); // No organisms

        // When: Add empty tick and commit
        strategy.addOrganismTick(mockConnection, emptyTick);
        strategy.commitOrganismWrites(mockConnection);

        // Then: No organism metadata and no state blob (only executeBatch for states)
        verify(mockPreparedStatement, never()).addBatch();
        verify(mockPreparedStatement).executeBatch();
    }

    @Test
    void testCommitOrganismWrites_PropagatesSqlException() throws SQLException {
        // Given: Strategy where executeBatch will fail
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);

        when(mockPreparedStatement.executeBatch()).thenThrow(new SQLException("Database error"));

        TickData tick = createTickWithOrganisms(1000L, 1);
        strategy.addOrganismTick(mockConnection, tick);

        // When/Then: commitOrganismWrites should propagate SQLException
        assertThatThrownBy(() -> strategy.commitOrganismWrites(mockConnection))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("Database error");
    }

    @Test
    void testAddOrganismTick_DeduplicatesOrganismMetadata() throws SQLException {
        // Given: Strategy with same organism appearing in multiple ticks
        strategy = new SingleBlobOrgStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);

        OrganismState org1 = createOrganism(1, 100);
        TickData tick1 = TickData.newBuilder().setTickNumber(1L).addOrganisms(org1).build();
        TickData tick2 = TickData.newBuilder().setTickNumber(2L).addOrganisms(org1).build();

        // When: Add both ticks
        strategy.addOrganismTick(mockConnection, tick1);
        strategy.addOrganismTick(mockConnection, tick2);
        strategy.commitOrganismWrites(mockConnection);

        // Then: addBatch called 1 (organism metadata, deduped) + 2 (state blobs) = 3
        verify(mockPreparedStatement, times(3)).addBatch();
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
