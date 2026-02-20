package org.evochora.datapipeline.resources.database.h2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.evochora.datapipeline.CellStateTestHelper;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.CellState;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.PluginState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
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
@Tag("unit")
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

        // CREATE TABLE: only first_tick and last_tick, no BLOB
        assertThat(executedSql.get(0))
            .contains("CREATE TABLE IF NOT EXISTS environment_chunks")
            .contains("first_tick BIGINT PRIMARY KEY")
            .contains("last_tick BIGINT NOT NULL")
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
            .doesNotContain("chunk_blob");
    }

    // ========================================================================
    // readChunkContaining tests
    // ========================================================================

    @Test
    void testReadChunkContaining_NotFound() throws SQLException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        when(mockResultSet.next()).thenReturn(false);

        assertThatThrownBy(() -> strategy.readChunkContaining(mockConnection, 500L))
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

        assertThatThrownBy(() -> strategy.readChunkContaining(mockConnection, 1000L))
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

        strategy.writeRawChunk(mockConnection, 1000L, 1000L, 1, rawBytes);

        // Verify file exists on disk
        Path chunkFile = tempDir.resolve(TEST_SCHEMA).resolve("0000").resolve("chunk_1000.pb");
        assertThat(chunkFile).exists();
        assertThat(Files.size(chunkFile)).isGreaterThan(0);

        // Verify H2 batch parameters
        verify(mockPreparedStatement).setLong(eq(1), eq(1000L));
        verify(mockPreparedStatement).setLong(eq(2), eq(1000L));
        verify(mockPreparedStatement).addBatch();
    }

    @Test
    void testCommitRawChunks_ExecutesBatchAndClosesStatement() throws SQLException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        strategy.createTables(mockConnection, 2);

        TickDataChunk chunk = createChunkWithSnapshot(500L);
        strategy.writeRawChunk(mockConnection, 500L, 500L, 1, chunk.toByteArray());

        strategy.commitRawChunks(mockConnection);

        verify(mockPreparedStatement).executeBatch();
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

        strategy.writeRawChunk(mockConnection, 0L, 0L, 1, chunk1.toByteArray());
        strategy.writeRawChunk(mockConnection, 100L, 100L, 1, chunk2.toByteArray());

        // Both should be batched (addBatch called twice)
        verify(mockPreparedStatement, times(2)).addBatch();

        // Commit executes both in one batch
        strategy.commitRawChunks(mockConnection);
        verify(mockPreparedStatement).executeBatch();
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

        strategy.writeRawChunk(mockConnection, firstTick, lastTick, tickCount, rawBytes);

        // Read back via readChunkContaining (uses mock H2 query + real filesystem)
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong("first_tick")).thenReturn(firstTick);

        TickDataChunk readback = strategy.readChunkContaining(mockConnection, firstTick);

        // Verify metadata matches
        assertEquals(originalChunk.getSimulationRunId(), readback.getSimulationRunId());
        assertEquals(originalChunk.getFirstTick(), readback.getFirstTick());
        assertEquals(originalChunk.getLastTick(), readback.getLastTick());
        assertEquals(originalChunk.getTickCount(), readback.getTickCount());

        // Verify cell data preserved (organisms stripped by readChunkContaining is expected)
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
    void readChunkContaining_preservesCellDataColumns() throws SQLException, TickNotFoundException, IOException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        TickDataChunk fullChunk = buildChunkWithOrganisms();
        prepareChunkFile(fullChunk.getSnapshot().getTickNumber(), compressWithZstd(fullChunk));

        TickDataChunk result = strategy.readChunkContaining(mockConnection, 0);

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
    void readChunkContaining_stripsOrganismsAndRngAndPlugins() throws SQLException, TickNotFoundException, IOException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        TickDataChunk fullChunk = buildChunkWithOrganisms();

        assertTrue(fullChunk.getSnapshot().getOrganismsCount() > 0,
                "Full chunk must contain organisms");
        assertTrue(fullChunk.getSnapshot().getRngState().size() > 0,
                "Full chunk must contain RNG state");

        prepareChunkFile(fullChunk.getSnapshot().getTickNumber(), compressWithZstd(fullChunk));

        TickDataChunk result = strategy.readChunkContaining(mockConnection, 0);

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
    void readChunkContaining_preservesMetadata() throws SQLException, TickNotFoundException, IOException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        TickDataChunk fullChunk = buildChunkWithOrganisms();
        prepareChunkFile(fullChunk.getSnapshot().getTickNumber(), compressWithZstd(fullChunk));

        TickDataChunk result = strategy.readChunkContaining(mockConnection, 0);

        assertEquals(fullChunk.getSimulationRunId(), result.getSimulationRunId());
        assertEquals(fullChunk.getFirstTick(), result.getFirstTick());
        assertEquals(fullChunk.getLastTick(), result.getLastTick());
        assertEquals(fullChunk.getTickCount(), result.getTickCount());
    }

    @Test
    void readChunkContaining_compatibleWithDeltaCodecDecoder()
            throws SQLException, TickNotFoundException, IOException, ChunkCorruptedException {
        strategy = new RowPerChunkStrategy(configWithChunkDir());
        TickDataChunk fullChunk = buildChunkWithOrganisms();
        prepareChunkFile(fullChunk.getSnapshot().getTickNumber(), compressWithZstd(fullChunk));

        TickDataChunk result = strategy.readChunkContaining(mockConnection, 0);

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
        Environment env = new Environment(new int[]{10, 10}, false);
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder("test-run", 100, 2, 2, 1);

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
