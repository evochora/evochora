package org.evochora.datapipeline.resources.database.h2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import com.github.luben.zstd.ZstdOutputStream;
import com.google.protobuf.ByteString;
import org.evochora.datapipeline.api.resources.database.PendingChunkRead;
import org.evochora.datapipeline.CellStateTestHelper;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.CellState;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.PluginState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.api.resources.database.dto.ChunkIndexSummary;
import org.evochora.datapipeline.api.resources.database.dto.SampledTickRange;
import org.evochora.datapipeline.api.resources.database.dto.TickRangeExtension;
import org.evochora.datapipeline.utils.delta.DeltaCodec;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Unit tests for RowPerChunkStrategy.
 * <p>
 * Tests file-based chunk storage: H2 holds only the tick-range index,
 * chunk data is written to and read from the filesystem.
 */
@Tag("integration")
class RowPerChunkStrategyTest {

    private static final String TEST_SCHEMA = "TEST_SCHEMA";

    @TempDir
    Path tempDir;

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
        when(mockConnection.getSchema()).thenReturn(TEST_SCHEMA);
    }

    private Config configWithChunkDir() {
        return ConfigFactory.parseString(
                "chunkDirectory = \"" + tempDir.toString().replace("\\", "\\\\") + "\"");
    }

    private Config configWithChunkDirAndZstd() {
        return ConfigFactory.parseString(
                "chunkDirectory = \"" + tempDir.toString().replace("\\", "\\\\") + "\"\n" +
                "compression { enabled = true, codec = \"zstd\", level = 3 }");
    }

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Test
    void testConstructor_RequiresChunkDirectory() {
        assertThatThrownBy(() -> new RowPerChunkStrategy(ConfigFactory.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkDirectory");
    }

    @Test
    void testConstructor_WithChunkDirectory() {
        strategy = new RowPerChunkStrategy(configWithChunkDir());

        assertThat(strategy).isNotNull();
        assertThat(strategy.getChunkDirectory()).isEqualTo(tempDir);
    }

    @Test
    void testConstructor_WithZstdCompression() {
        strategy = new RowPerChunkStrategy(configWithChunkDirAndZstd());

        assertThat(strategy).isNotNull();
    }

    // ========================================================================
    // createTables tests
    // ========================================================================

    @Test
    void testCreateTables_CreatesTableAndIndex() throws SQLException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());

        strategy.createTables(mockConnection, 2);

        verify(mockStatement, times(2)).execute(anyString());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockStatement, times(2)).execute(sqlCaptor.capture());

        List<String> executedSql = sqlCaptor.getAllValues();
        assertThat(executedSql).hasSize(2);

        // CREATE TABLE: the chunk's bounds and how many ticks it holds, no BLOB
        assertThat(executedSql.get(0))
            .contains("CREATE TABLE IF NOT EXISTS environment_chunks")
            .contains("first_tick BIGINT PRIMARY KEY")
            .contains("last_tick BIGINT NOT NULL")
            .contains("tick_count INT NOT NULL")
            .doesNotContain("chunk_blob")
            .doesNotContain("BYTEA");

        // CREATE INDEX
        assertThat(executedSql.get(1))
            .contains("CREATE INDEX IF NOT EXISTS idx_env_chunks_last_tick");
    }

    @Test
    void testCreateTables_CachesMergeSql() throws SQLException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());

        strategy.createTables(mockConnection, 3);

        assertThat(strategy.getMergeSql())
            .contains("MERGE INTO environment_chunks")
            .contains("first_tick")
            .contains("last_tick")
            .contains("tick_count")
            .doesNotContain("chunk_blob");
    }

    // ========================================================================
    // Tick ranges derived from the chunk index
    // ========================================================================

    @Test
    void writeRawChunk_storesHowManyTicksTheChunkHolds() throws Exception {
        strategy = new RowPerChunkStrategy(configWithChunkDir());

        try (Connection conn = inMemoryDatabase()) {
            strategy.createTables(conn, 2);
            strategy.writeRawChunk(conn, 100L, 140L, 5, 10, createChunkWithSnapshot(100L).toByteArray());
            strategy.commitRawChunks(conn);

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT first_tick, last_tick, tick_count FROM environment_chunks")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("first_tick")).isEqualTo(100L);
                assertThat(rs.getLong("last_tick")).isEqualTo(140L);
                assertThat(rs.getInt("tick_count")).isEqualTo(5);
            }
        }
    }

    @Test
    void readTickRanges_takesTheStepOfASingleChunkFromTheChunkItself() throws Exception {
        try (Connection conn = indexWith(chunk(0L, 90L, 10, 10))) {
            assertThat(rangesOf(conn))
                    .containsExactly(new SampledTickRange(0L, 90L, 10L));
        }
    }

    @Test
    void readTickRanges_joinsChunksThatContinueEachOther() throws Exception {
        try (Connection conn = indexWith(chunk(0L, 90L, 10, 10), chunk(100L, 190L, 10, 10),
                                         chunk(200L, 290L, 10, 10))) {
            assertThat(rangesOf(conn))
                    .containsExactly(new SampledTickRange(0L, 290L, 10L));
        }
    }

    @Test
    void readTickRanges_splitsWhereTheRecordingHasAGap() throws Exception {
        try (Connection conn = indexWith(chunk(0L, 90L, 10, 10), chunk(500L, 590L, 10, 10))) {
            assertThat(rangesOf(conn))
                    .containsExactly(
                            new SampledTickRange(0L, 90L, 10L),
                            new SampledTickRange(500L, 590L, 10L));
        }
    }

    @Test
    void readTickRanges_startsWhereTheRunStarts() throws Exception {
        // A run forked from another begins at the fork's first tick, not at 0
        try (Connection conn = indexWith(chunk(4000L, 4090L, 10, 10), chunk(4100L, 4190L, 10, 10))) {
            assertThat(rangesOf(conn))
                    .containsExactly(new SampledTickRange(4000L, 4190L, 10L));
        }
    }

    @Test
    void readTickRanges_separatesStretchesRecordedAtDifferentSteps() throws Exception {
        // The second stretch follows the first without a gap but was recorded ten times as
        // densely, so stepping along it needs its own step
        try (Connection conn = indexWith(chunk(0L, 90L, 10, 10), chunk(100L, 109L, 10, 1))) {
            assertThat(rangesOf(conn))
                    .containsExactly(
                            new SampledTickRange(0L, 90L, 10L),
                            new SampledTickRange(100L, 109L, 1L));
        }
    }

    @Test
    void readTickRanges_isEmptyWhileNothingIsIndexed() throws Exception {
        try (Connection conn = indexWith()) {
            assertThat(rangesOf(conn)).isEmpty();
        }
    }

    @Test
    void readTickRanges_rejectsOverlappingChunks() throws Exception {
        try (Connection conn = indexWith(chunk(0L, 90L, 10, 10), chunk(50L, 140L, 10, 10))) {
            assertThatThrownBy(() -> strategy.readTickRanges(conn, "run-a"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("run-a")
                    .hasMessageContaining("50")
                    .hasMessageContaining("140")
                    .hasMessageContaining("0")
                    .hasMessageContaining("90");
        }
    }

    @Test
    void readTickRanges_joinsChunksThatHoldASingleTickEach() throws Exception {
        // Every recorded tick its own chunk: each states the step, so the four make one range
        try (Connection conn = indexWith(chunk(0L, 0L, 1, 1), chunk(1L, 1L, 1, 1),
                                         chunk(2L, 2L, 1, 1), chunk(3L, 3L, 1, 1))) {
            assertThat(rangesOf(conn))
                    .containsExactly(new SampledTickRange(0L, 3L, 1L));
        }
    }

    @Test
    void readTickRanges_readsTheStepOfALoneSingleTickChunkFromTheChunk() throws Exception {
        // Nothing neighbours this chunk, and it still knows what it was recorded at
        try (Connection conn = indexWith(chunk(70L, 70L, 1, 5))) {
            assertThat(rangesOf(conn))
                    .containsExactly(new SampledTickRange(70L, 70L, 5L));
        }
    }

    @Test
    void readTickRanges_rejectsASpanThatDoesNotFitTheStepAndTheTicks() throws Exception {
        // Four ticks at a step of 4 would end at 12, not at 10
        try (Connection conn = indexWith(chunk(0L, 10L, 4, 4))) {
            assertThatThrownBy(() -> strategy.readTickRanges(conn, "run-a"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("run-a")
                    .hasMessageContaining("0..10")
                    .hasMessageContaining("would end at 12");
        }
    }

    @Test
    void readTickRanges_rejectsAChunkWithoutAStep() throws Exception {
        try (Connection conn = indexWith(chunk(40L, 40L, 1, 0))) {
            assertThatThrownBy(() -> strategy.readTickRanges(conn, "run-a"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("run-a")
                    .hasMessageContaining("40..40")
                    .hasMessageContaining("no step at all");
        }
    }

    @Test
    void writeRawChunk_rejectsAChunkThatStatesNoSamplingInterval() throws Exception {
        strategy = new RowPerChunkStrategy(configWithChunkDir());

        try (Connection conn = inMemoryDatabase()) {
            strategy.createTables(conn, 2);

            assertThatThrownBy(() -> strategy.writeRawChunk(conn, 0L, 0L, 1, 0,
                    createChunkWithSnapshot(0L).toByteArray()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("0..0")
                    .hasMessageContaining("older build");
        }
    }

    @Test
    void readChunkIndexSummary_countsTheChunksAndHowFarTheyReach() throws Exception {
        try (Connection conn = indexWith(chunk(0L, 90L, 10, 10), chunk(500L, 590L, 10, 10))) {
            assertThat(strategy.readChunkIndexSummary(conn))
                    .isEqualTo(new ChunkIndexSummary(2L, 590L, 20L));
        }
    }

    @Test
    void readChunkIndexSummary_countsNothingWhileNothingIsIndexed() throws Exception {
        try (Connection conn = indexWith()) {
            assertThat(strategy.readChunkIndexSummary(conn))
                    .isEqualTo(new ChunkIndexSummary(0L, 0L, 0L));
        }
    }

    // ========================================================================
    // Catching up with an index that has grown
    // ========================================================================

    @Test
    void extendTickRanges_lengthensTheRangeTheNewChunkContinues() throws Exception {
        try (Connection conn = indexWith(chunk(0L, 90L, 10, 10), chunk(100L, 190L, 10, 10))) {
            TickRangeExtension known = strategy.readTickRanges(conn, "run-a");
            insertChunks(conn, chunk(200L, 290L, 10, 10));

            TickRangeExtension extended =
                    strategy.extendTickRanges(conn, "run-a", known.ranges(), known.lastFirstTick());

            assertThat(extended.ranges()).containsExactly(new SampledTickRange(0L, 290L, 10L));
            assertThat(extended.addedChunks()).isEqualTo(1L);
            assertThat(extended.addedSamples()).isEqualTo(10L);
            assertThat(extended.lastFirstTick()).isEqualTo(200L);
        }
    }

    @Test
    void extendTickRanges_opensANewRangeWhereTheNewChunkDoesNotContinue() throws Exception {
        try (Connection conn = indexWith(chunk(0L, 90L, 10, 10))) {
            TickRangeExtension known = strategy.readTickRanges(conn, "run-a");
            insertChunks(conn, chunk(100L, 109L, 10, 1));

            TickRangeExtension extended =
                    strategy.extendTickRanges(conn, "run-a", known.ranges(), known.lastFirstTick());

            assertThat(extended.ranges()).containsExactly(
                    new SampledTickRange(0L, 90L, 10L),
                    new SampledTickRange(100L, 109L, 1L));
        }
    }

    @Test
    void extendTickRanges_lengthensARangeOfSingleTickChunks() throws Exception {
        // The known ranges end on a chunk holding one tick, and the appended one holds one too:
        // both state the same step, so both belong to the same range
        try (Connection conn = indexWith(chunk(0L, 0L, 1, 4), chunk(4L, 4L, 1, 4))) {
            TickRangeExtension known = strategy.readTickRanges(conn, "run-a");
            assertThat(known.ranges()).containsExactly(new SampledTickRange(0L, 4L, 4L));
            insertChunks(conn, chunk(8L, 8L, 1, 4));

            TickRangeExtension extended =
                    strategy.extendTickRanges(conn, "run-a", known.ranges(), known.lastFirstTick());

            assertThat(extended.ranges()).containsExactly(new SampledTickRange(0L, 8L, 4L));
        }
    }

    @Test
    void extendTickRanges_takesInNothingWhileTheIndexStandsStill() throws Exception {
        try (Connection conn = indexWith(chunk(0L, 90L, 10, 10))) {
            TickRangeExtension known = strategy.readTickRanges(conn, "run-a");

            TickRangeExtension extended =
                    strategy.extendTickRanges(conn, "run-a", known.ranges(), known.lastFirstTick());

            assertThat(extended.ranges()).isEqualTo(known.ranges());
            assertThat(extended.addedChunks()).isZero();
            assertThat(extended.addedSamples()).isZero();
            assertThat(extended.lastFirstTick()).isEqualTo(known.lastFirstTick());
        }
    }

    // ========================================================================
    // chunk read tests
    // ========================================================================

    @Test
    void testReadChunkContaining_NotFound() throws SQLException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        when(mockResultSet.next()).thenReturn(false);

        assertThatThrownBy(() -> strategy.prepareChunkRead(mockConnection, 500L).read())
            .isInstanceOf(TickNotFoundException.class)
            .hasMessageContaining("No chunk found containing tick 500");
    }

    @Test
    void testReadChunkContaining_FileNotFound() throws SQLException, IOException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());

        // Create schema directory with metadata but no chunk file
        Path schemaDir = tempDir.resolve(TEST_SCHEMA);
        Files.createDirectories(schemaDir);
        var props = new java.util.Properties();
        props.setProperty("ticksPerSubdirectory", "10000");
        try (var out = Files.newOutputStream(schemaDir.resolve(".chunk_meta"))) {
            props.store(out, null);
        }

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong("first_tick")).thenReturn(1000L);

        assertThatThrownBy(() -> strategy.prepareChunkRead(mockConnection, 1000L).read())
            .isInstanceOf(TickNotFoundException.class)
            .hasMessageContaining("Chunk file not found");
    }

    // ========================================================================
    // writeRawChunk / commitRawChunks round-trip
    // ========================================================================

    @Test
    void testWriteRawChunk_WritesFileAndAddsToJdbcBatch() throws SQLException, IOException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        strategy.createTables(mockConnection, 2);

        TickDataChunk chunk = createChunkWithSnapshot(1000L);
        byte[] rawBytes = chunk.toByteArray();

        strategy.writeRawChunk(mockConnection, 1000L, 1000L, 1, 1, rawBytes);

        // Verify file exists on disk
        Path chunkFile = tempDir.resolve(TEST_SCHEMA).resolve("0000").resolve("chunk_1000.pb");
        assertThat(chunkFile).exists();
        assertThat(Files.size(chunkFile)).isGreaterThan(0);

        // Verify H2 batch parameters
        verify(mockPreparedStatement).setLong(eq(1), eq(1000L));
        verify(mockPreparedStatement).setLong(eq(2), eq(1000L));
        verify(mockPreparedStatement).setInt(eq(3), eq(1));
        verify(mockPreparedStatement).addBatch();
    }

    @Test
    void testCommitRawChunks_ExecutesBatchAndKeepsStatementOpen() throws SQLException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        strategy.createTables(mockConnection, 2);

        TickDataChunk chunk = createChunkWithSnapshot(500L);
        strategy.writeRawChunk(mockConnection, 500L, 500L, 1, 1, chunk.toByteArray());

        strategy.commitRawChunks(mockConnection);

        verify(mockPreparedStatement).executeBatch();
        verify(mockPreparedStatement, times(0)).close();

        // Statement is closed by resetStreamingState
        strategy.resetStreamingState(mockConnection);
        verify(mockPreparedStatement).close();
    }

    @Test
    void testCommitRawChunks_NoWritesPreceding_NoOp() throws SQLException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        strategy.createTables(mockConnection, 2);

        // Commit without any preceding writes
        strategy.commitRawChunks(mockConnection);

        // No interactions — map entry was never created
        verify(mockPreparedStatement, times(0)).executeBatch();
    }

    @Test
    void testWriteRawChunk_MultipleChunks_BatchedTogether() throws SQLException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        strategy.createTables(mockConnection, 2);

        TickDataChunk chunk1 = createChunkWithSnapshot(0L);
        TickDataChunk chunk2 = createChunkWithSnapshot(100L);

        strategy.writeRawChunk(mockConnection, 0L, 0L, 1, 1, chunk1.toByteArray());
        strategy.writeRawChunk(mockConnection, 100L, 100L, 1, 1, chunk2.toByteArray());

        // Both should be batched (addBatch called twice)
        verify(mockPreparedStatement, times(2)).addBatch();

        // Commit executes both in one batch
        strategy.commitRawChunks(mockConnection);
        verify(mockPreparedStatement).executeBatch();
    }

    @Test
    void prepareChunkRead_takesEverythingFromTheConnectionBeforeReading() throws Exception {
        // The connection is needed to find the chunk, not to read it. Carrying the read out once
        // the connection is gone proves the read needs none - which is what keeps a slow disk from
        // starving the pool.
        strategy = new RowPerChunkStrategy(configWithChunkDirAndZstd());
        strategy.createTables(mockConnection, 2);

        TickDataChunk chunk = buildChunkWithOrganisms();
        strategy.writeRawChunk(mockConnection, chunk.getFirstTick(), chunk.getLastTick(),
                chunk.getTickCount(), chunk.getSamplingInterval(), chunk.toByteArray());

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong("first_tick")).thenReturn(chunk.getFirstTick());

        PendingChunkRead pending = strategy.prepareChunkRead(mockConnection, chunk.getFirstTick());

        // Every further use of the connection now fails, as a closed one would. Without this a
        // call would get Mockito's default answer and fail somewhere further along, if at all
        when(mockConnection.prepareStatement(anyString()))
                .thenThrow(new SQLException("connection is gone"));
        when(mockConnection.getSchema()).thenThrow(new SQLException("connection is gone"));
        clearInvocations(mockConnection);

        TickDataChunk readback = pending.read();

        // Not "these two methods were not called" but "the connection was not touched" - which is
        // what the caller relies on when it hands the connection back before reading
        verifyNoInteractions(mockConnection);
        assertEquals(chunk.getFirstTick(), readback.getFirstTick());
        assertEquals(chunk.getTickCount(), readback.getTickCount());
    }

    @Test
    void testWriteRawChunk_RoundTrip_DataMatchesAfterReadback() throws Exception {
        strategy = new RowPerChunkStrategy(configWithChunkDirAndZstd());
        strategy.createTables(mockConnection, 2);

        TickDataChunk originalChunk = buildChunkWithOrganisms();
        byte[] rawBytes = originalChunk.toByteArray();

        long firstTick = originalChunk.getFirstTick();
        long lastTick = originalChunk.getLastTick();
        int tickCount = originalChunk.getTickCount();

        strategy.writeRawChunk(mockConnection, firstTick, lastTick, tickCount,
                originalChunk.getSamplingInterval(), rawBytes);

        // Read back through a prepared read (uses mock H2 query + real filesystem)
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong("first_tick")).thenReturn(firstTick);

        TickDataChunk readback = strategy.prepareChunkRead(mockConnection, firstTick).read();

        // Verify metadata matches
        assertEquals(originalChunk.getSimulationRunId(), readback.getSimulationRunId());
        assertEquals(originalChunk.getFirstTick(), readback.getFirstTick());
        assertEquals(originalChunk.getLastTick(), readback.getLastTick());
        assertEquals(originalChunk.getTickCount(), readback.getTickCount());

        // Verify cell data preserved (organisms are stripped on this path, as expected)
        assertCellColumnsEqual(
            originalChunk.getSnapshot().getCellColumns(),
            readback.getSnapshot().getCellColumns());

        assertEquals(originalChunk.getDeltasCount(), readback.getDeltasCount());
        for (int i = 0; i < originalChunk.getDeltasCount(); i++) {
            assertCellColumnsEqual(
                originalChunk.getDeltas(i).getChangedCells(),
                readback.getDeltas(i).getChangedCells());
        }
    }

    // ========================================================================
    // Partial parse: organisms stripped, CellDataColumns preserved
    // ========================================================================

    @Test
    void chunkRead_preservesCellDataColumns() throws SQLException, TickNotFoundException, IOException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        TickDataChunk fullChunk = buildChunkWithOrganisms();
        prepareChunkFile(fullChunk.getSnapshot().getTickNumber(), compressWithZstd(fullChunk));

        TickDataChunk result = strategy.prepareChunkRead(mockConnection, 0).read();

        assertCellColumnsEqual(
                fullChunk.getSnapshot().getCellColumns(),
                result.getSnapshot().getCellColumns());

        assertEquals(fullChunk.getDeltasCount(), result.getDeltasCount());
        for (int i = 0; i < fullChunk.getDeltasCount(); i++) {
            assertCellColumnsEqual(
                    fullChunk.getDeltas(i).getChangedCells(),
                    result.getDeltas(i).getChangedCells());
        }
    }

    @Test
    void chunkRead_stripsOrganismsAndRngAndPlugins() throws SQLException, TickNotFoundException, IOException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        TickDataChunk fullChunk = buildChunkWithOrganisms();

        assertTrue(fullChunk.getSnapshot().getOrganismsCount() > 0,
                "Full chunk must contain organisms");
        assertTrue(fullChunk.getSnapshot().getRngState().size() > 0,
                "Full chunk must contain RNG state");

        prepareChunkFile(fullChunk.getSnapshot().getTickNumber(), compressWithZstd(fullChunk));

        TickDataChunk result = strategy.prepareChunkRead(mockConnection, 0).read();

        assertEquals(0, result.getSnapshot().getOrganismsCount());
        assertEquals(0, result.getSnapshot().getRngState().size());
        assertEquals(0, result.getSnapshot().getPluginStatesCount());
        for (int i = 0; i < result.getDeltasCount(); i++) {
            assertEquals(0, result.getDeltas(i).getOrganismsCount());
            assertEquals(0, result.getDeltas(i).getRngState().size());
            assertEquals(0, result.getDeltas(i).getPluginStatesCount());
        }
    }

    @Test
    void chunkRead_preservesMetadata() throws SQLException, TickNotFoundException, IOException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        TickDataChunk fullChunk = buildChunkWithOrganisms();
        prepareChunkFile(fullChunk.getSnapshot().getTickNumber(), compressWithZstd(fullChunk));

        TickDataChunk result = strategy.prepareChunkRead(mockConnection, 0).read();

        assertEquals(fullChunk.getSimulationRunId(), result.getSimulationRunId());
        assertEquals(fullChunk.getFirstTick(), result.getFirstTick());
        assertEquals(fullChunk.getLastTick(), result.getLastTick());
        assertEquals(fullChunk.getTickCount(), result.getTickCount());
    }

    @Test
    void chunkRead_compatibleWithDeltaCodecDecoder()
            throws SQLException, TickNotFoundException, IOException, ChunkCorruptedException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        TickDataChunk fullChunk = buildChunkWithOrganisms();
        prepareChunkFile(fullChunk.getSnapshot().getTickNumber(), compressWithZstd(fullChunk));

        TickDataChunk result = strategy.prepareChunkRead(mockConnection, 0).read();

        DeltaCodec.Decoder decoder = new DeltaCodec.Decoder(100);

        for (int i = 0; i < fullChunk.getTickCount(); i++) {
            long tickNumber = (i == 0)
                    ? fullChunk.getSnapshot().getTickNumber()
                    : fullChunk.getDeltas(i - 1).getTickNumber();

            TickData fromFull = decoder.decompressTick(fullChunk, tickNumber);
            TickData fromResult = decoder.decompressTick(result, tickNumber);

            assertCellColumnsEqual(fromFull.getCellColumns(), fromResult.getCellColumns());
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * One row of the chunk index: the chunk's bounds and how many ticks it holds.
     */
    private record IndexedChunk(long firstTick, long lastTick, int tickCount, int step) {}

    private static IndexedChunk chunk(long firstTick, long lastTick, int tickCount, int step) {
        return new IndexedChunk(firstTick, lastTick, tickCount, step);
    }

    /**
     * Opens a database of its own, so that rows written by one test cannot reach another.
     */
    private Connection inMemoryDatabase() throws SQLException {
        return java.sql.DriverManager.getConnection(
                "jdbc:h2:mem:rowperchunk-" + java.util.UUID.randomUUID(), "sa", "");
    }

    /**
     * Creates the chunk index and fills it with the given chunks, leaving the chunk files out:
     * everything the tick ranges are derived from stands in the index.
     */
    private Connection indexWith(IndexedChunk... chunks) throws SQLException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        Connection conn = inMemoryDatabase();
        strategy.createTables(conn, 2);
        insertChunks(conn, chunks);
        return conn;
    }

    /**
     * Adds further chunks to an index that already exists, the way an indexer appends to it.
     */
    private void insertChunks(Connection conn, IndexedChunk... chunks) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(strategy.getMergeSql())) {
            for (IndexedChunk c : chunks) {
                stmt.setLong(1, c.firstTick());
                stmt.setLong(2, c.lastTick());
                stmt.setInt(3, c.tickCount());
                stmt.setInt(4, c.step());
                stmt.executeUpdate();
            }
        }
    }

    /**
     * The ranges of the whole index, for the tests that look at nothing else.
     */
    private List<SampledTickRange> rangesOf(Connection conn) throws SQLException {
        return strategy.readTickRanges(conn, "run-a").ranges();
    }

    /**
     * Writes a compressed chunk file to the schema directory with subdirectory
     * structure and .chunk_meta, then mocks the H2 query to return the corresponding first_tick.
     */
    private void prepareChunkFile(long firstTick, byte[] compressedData) throws IOException, SQLException {
        Path schemaDir = tempDir.resolve(TEST_SCHEMA);
        Files.createDirectories(schemaDir);

        // Write .chunk_meta (ticksPerSubdirectory = 10000 × 1 for default maxFilesPerDirectory)
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
        Files.write(subdir.resolve("chunk_" + firstTick + ".pb"), compressedData);

        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong("first_tick")).thenReturn(firstTick);
    }

    private TickDataChunk buildChunkWithOrganisms() {
        Environment env = new Environment(new int[]{32, 32}, false);
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder("test-run", 1, 2, 2, 1);

        env.setMolecule(Molecule.fromInt(100), new int[]{0, 0});
        env.setMolecule(Molecule.fromInt(200), new int[]{5, 5});
        captureTickWithOrganisms(encoder, env, 0, 2);

        env.setMolecule(Molecule.fromInt(110), new int[]{1, 0});
        captureTickWithOrganisms(encoder, env, 1, 3);

        env.setMolecule(Molecule.fromInt(120), new int[]{2, 0});
        captureTickWithOrganisms(encoder, env, 2, 3);

        env.setMolecule(Molecule.fromInt(130), new int[]{3, 0});
        Optional<TickDataChunk> chunk = captureTickWithOrganisms(encoder, env, 3, 4);

        assertTrue(chunk.isPresent(), "Chunk must be complete after 4 ticks");
        return chunk.get();
    }

    private Optional<TickDataChunk> captureTickWithOrganisms(
            DeltaCodec.Encoder encoder, Environment env, long tick, int organismCount) {
        List<OrganismState> organisms = new java.util.ArrayList<>();
        for (int i = 1; i <= organismCount; i++) {
            organisms.add(OrganismState.newBuilder()
                    .setOrganismId(i).setEnergy(100 * i).build());
        }
        return encoder.captureTick(tick, env, organisms, organismCount,
                tick * 10L, new LongOpenHashSet(new long[]{1000L + tick}),
                ByteString.copyFromUtf8("rng-" + tick),
                List.of(PluginState.newBuilder().setPluginClass("TestPlugin")
                        .setStateBlob(ByteString.copyFromUtf8("s-" + tick)).build()));
    }

    private byte[] compressWithZstd(TickDataChunk chunk) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (OutputStream zstd = new ZstdOutputStream(baos)) {
            chunk.writeTo(zstd);
        }
        return baos.toByteArray();
    }

    private void assertCellColumnsEqual(CellDataColumns expected, CellDataColumns actual) {
        assertEquals(expected.getFlatIndicesCount(), actual.getFlatIndicesCount(), "Cell count mismatch");
        for (int i = 0; i < expected.getFlatIndicesCount(); i++) {
            assertEquals(expected.getFlatIndices(i), actual.getFlatIndices(i));
            assertEquals(expected.getMoleculeData(i), actual.getMoleculeData(i));
            assertEquals(expected.getOwnerIds(i), actual.getOwnerIds(i));
        }
    }

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
}
