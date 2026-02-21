package org.evochora.datapipeline.resources.database.h2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
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

import org.evochora.datapipeline.api.contracts.OrganismState;
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

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for RowPerOrganismStrategy.
 * <p>
 * Tests the backward-compatible row-per-organism storage strategy that stores
 * one row per organism per tick in the {@code organism_states} table.
 * <p>
 * This strategy is required for reading simulation data created before the
 * BLOB-based {@link SingleBlobOrgStrategy} was introduced.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class RowPerOrganismStrategyTest {

    private RowPerOrganismStrategy strategy;
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
        strategy = new RowPerOrganismStrategy(config);

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
        strategy = new RowPerOrganismStrategy(config);

        // Then: Should use ZstdCodec
        assertThat(strategy.getCodec()).isInstanceOf(ZstdCodec.class);
    }

    @Test
    void testCreateTables_CreatesBothTablesAndIndex() throws SQLException {
        // Given: Strategy with no compression
        strategy = new RowPerOrganismStrategy(ConfigFactory.empty());

        // When: Create tables
        strategy.createTables(mockConnection);

        // Then: Should execute CREATE TABLE for organisms, organism_states, and index
        verify(mockStatement, times(3)).execute(anyString());

        // Verify SQL strings
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockStatement, times(3)).execute(sqlCaptor.capture());

        List<String> executedSql = sqlCaptor.getAllValues();
        assertThat(executedSql).hasSize(3);

        // First call: CREATE TABLE organisms
        assertThat(executedSql.get(0))
                .contains("CREATE TABLE IF NOT EXISTS organisms")
                .contains("organism_id INT PRIMARY KEY");

        // Second call: CREATE TABLE organism_states (row-per-organism)
        assertThat(executedSql.get(1))
                .contains("CREATE TABLE IF NOT EXISTS organism_states")
                .contains("tick_number BIGINT NOT NULL")
                .contains("organism_id INT NOT NULL")
                .contains("energy INT NOT NULL")
                .contains("ip BYTEA NOT NULL")
                .contains("dv BYTEA NOT NULL")
                .contains("data_pointers BYTEA NOT NULL")
                .contains("active_dp_index INT NOT NULL")
                .contains("runtime_state_blob BYTEA NOT NULL")
                .contains("entropy INT DEFAULT 0")
                .contains("molecule_marker INT DEFAULT 0")
                .contains("PRIMARY KEY (tick_number, organism_id)");

        // Third call: CREATE INDEX for per-organism history queries
        assertThat(executedSql.get(2))
                .contains("CREATE INDEX IF NOT EXISTS idx_organism_states_org")
                .contains("organism_states (organism_id)");
    }

    @Test
    void testAddOrganismTick_SingleTickWithMultipleOrganisms() throws SQLException {
        // Given: Strategy with one tick containing 3 organisms
        strategy = new RowPerOrganismStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);

        TickData tick = createTickWithOrganisms(1000L, 3);

        // When: Add tick and commit
        strategy.addOrganismTick(mockConnection, tick);
        strategy.commitOrganismWrites(mockConnection);

        // Then: addBatch for 3 organism metadata + 3 state rows = 6
        verify(mockPreparedStatement, times(6)).addBatch();
        verify(mockPreparedStatement, times(2)).executeBatch();
    }

    @Test
    void testAddOrganismTick_MultipleTicks() throws SQLException {
        // Given: Strategy with multiple ticks (organism IDs overlap: {0,1}, {0,1,2}, {0})
        strategy = new RowPerOrganismStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);

        TickData tick1 = createTickWithOrganisms(1000L, 2);
        TickData tick2 = createTickWithOrganisms(1001L, 3);
        TickData tick3 = createTickWithOrganisms(1002L, 1);

        // When: Add all ticks and commit
        strategy.addOrganismTick(mockConnection, tick1);
        strategy.addOrganismTick(mockConnection, tick2);
        strategy.addOrganismTick(mockConnection, tick3);
        strategy.commitOrganismWrites(mockConnection);

        // Then: addBatch for 3 unique organism metadata (deduped) + 6 state rows = 9
        verify(mockPreparedStatement, times(9)).addBatch();
        verify(mockPreparedStatement, times(2)).executeBatch();
    }

    @Test
    void testCommitOrganismWrites_PropagatesSqlException() throws SQLException {
        // Given: Strategy where executeBatch will fail
        strategy = new RowPerOrganismStrategy(ConfigFactory.empty());
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
        strategy = new RowPerOrganismStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);

        OrganismState org1 = createOrganism(1, 100);
        TickData tick1 = TickData.newBuilder().setTickNumber(1L).addOrganisms(org1).build();
        TickData tick2 = TickData.newBuilder().setTickNumber(2L).addOrganisms(org1).build();

        // When: Add both ticks
        strategy.addOrganismTick(mockConnection, tick1);
        strategy.addOrganismTick(mockConnection, tick2);
        strategy.commitOrganismWrites(mockConnection);

        // Then: addBatch 1 (organism metadata, deduped) + 2 (state rows) = 3
        verify(mockPreparedStatement, times(3)).addBatch();
    }

    @Test
    void testAddOrganismTick_MultipleUniqueOrganisms() throws SQLException {
        // Given: Strategy with ticks containing different organisms
        strategy = new RowPerOrganismStrategy(ConfigFactory.empty());
        strategy.createTables(mockConnection);

        OrganismState org1 = createOrganism(1, 100);
        OrganismState org2 = createOrganism(2, 200);
        OrganismState org3 = createOrganism(3, 300);

        TickData tick1 = TickData.newBuilder().setTickNumber(1L)
                .addOrganisms(org1).addOrganisms(org2).build();
        TickData tick2 = TickData.newBuilder().setTickNumber(2L)
                .addOrganisms(org2).addOrganisms(org3).build(); // org2 appears again

        // When: Add both ticks
        strategy.addOrganismTick(mockConnection, tick1);
        strategy.addOrganismTick(mockConnection, tick2);
        strategy.commitOrganismWrites(mockConnection);

        // Then: 3 unique organism metadata + 4 state rows (2+2) = 7 addBatch calls
        verify(mockPreparedStatement, times(7)).addBatch();
    }

    @Test
    void testReadSingleOrganismState_NotFound() throws SQLException {
        // Given: Strategy with mock that returns empty result
        strategy = new RowPerOrganismStrategy(ConfigFactory.empty());

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
        strategy = new RowPerOrganismStrategy(ConfigFactory.empty());

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

    @Test
    void testGetAvailableTickRange_TableNotFound() throws SQLException {
        // Given: Strategy where table doesn't exist
        strategy = new RowPerOrganismStrategy(ConfigFactory.empty());

        // H2 error code 42102 = Table not found
        SQLException tableNotFound = new SQLException("Table ORGANISM_STATES not found", "42S02", 42102);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockPreparedStatement.executeQuery()).thenThrow(tableNotFound);

        // When: Get tick range
        var result = strategy.getAvailableTickRange(mockConnection);

        // Then: Should return null (graceful handling)
        assertThat(result).isNull();
    }

    @Test
    void testGetAvailableTickRange_WithData() throws SQLException {
        // Given: Strategy with tick data
        strategy = new RowPerOrganismStrategy(ConfigFactory.empty());

        ResultSet mockResultSet = mock(ResultSet.class);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong("min_tick")).thenReturn(100L);
        when(mockResultSet.getLong("max_tick")).thenReturn(5000L);
        when(mockResultSet.wasNull()).thenReturn(false);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);

        // When: Get tick range
        var result = strategy.getAvailableTickRange(mockConnection);

        // Then: Should return correct range
        assertThat(result).isNotNull();
        assertThat(result.minTick()).isEqualTo(100L);
        assertThat(result.maxTick()).isEqualTo(5000L);
    }

    @Test
    void testReadOrganismsAtTick_QueryFormat() throws SQLException {
        // Given: Strategy
        strategy = new RowPerOrganismStrategy(ConfigFactory.empty());

        ResultSet mockResultSet = mock(ResultSet.class);
        when(mockResultSet.next()).thenReturn(false);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);

        // When: Read organisms at tick
        strategy.readOrganismsAtTick(mockConnection, 1000L);

        // Then: Should prepare correct SQL with JOIN
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockConnection).prepareStatement(sqlCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .contains("FROM organism_states s")
                .contains("LEFT JOIN organisms o ON s.organism_id = o.organism_id")
                .contains("WHERE s.tick_number = ?")
                .contains("ORDER BY s.organism_id")
                .contains("s.active_dp_index, s.entropy");
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
