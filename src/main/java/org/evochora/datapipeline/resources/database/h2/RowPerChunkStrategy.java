package org.evochora.datapipeline.resources.database.h2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.resources.database.TickNotFoundException;
import org.evochora.datapipeline.utils.H2SchemaUtil;
import org.evochora.datapipeline.utils.compression.CompressionCodecFactory;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import com.typesafe.config.Config;

/**
 * RowPerChunkStrategy: Stores entire TickDataChunks as BLOBs (one row per chunk).
 * <p>
 * This strategy stores delta-compressed chunks directly in the database without
 * decompression, maximizing storage savings. Decompression is deferred to the
 * EnvironmentController, which can cache decompressed chunks for efficient
 * sequential access.
 * <p>
 * <strong>Read optimization:</strong> When reading chunks, only fields needed for
 * environment rendering are parsed (CellDataColumns, metadata). Heavy fields like
 * OrganismState lists, RNG state, and plugin states are skipped at the wire level
 * using {@link CodedInputStream}, reducing heap allocation and GC pressure.
 * <p>
 * <strong>Storage:</strong> One row per chunk
 * <ul>
 *   <li>50 ticks per chunk = 50× fewer rows than per-tick storage</li>
 *   <li>15M ticks = ~300K rows (vs 15M rows with per-tick)</li>
 *   <li>Chunk BLOB: ~3-8MB compressed (snapshot + 49 deltas)</li>
 * </ul>
 * <p>
 * <strong>Schema:</strong>
 * <pre>
 * CREATE TABLE environment_chunks (
 *   first_tick BIGINT PRIMARY KEY,
 *   last_tick BIGINT NOT NULL,
 *   chunk_blob BYTEA NOT NULL
 * )
 * </pre>
 * <p>
 * <strong>Query Performance:</strong>
 * <ul>
 *   <li>Write: Fast (1 MERGE per chunk)</li>
 *   <li>Read: Must load entire chunk (~10-20ms), then decompress specific tick</li>
 *   <li>With LRU cache in controller: subsequent ticks in same chunk are instant</li>
 * </ul>
 * <p>
 * <strong>Best For:</strong> Production deployments with delta compression enabled.
 *
 * @see IH2EnvStorageStrategy
 * @see AbstractH2EnvStorageStrategy
 * @see org.evochora.datapipeline.utils.delta.DeltaCodec
 */
public class RowPerChunkStrategy extends AbstractH2EnvStorageStrategy {

    // Proto field numbers for partial parsing (from tickdata_contracts.proto)
    private static final int CHUNK_SIMULATION_RUN_ID = 1;
    private static final int CHUNK_FIRST_TICK = 2;
    private static final int CHUNK_LAST_TICK = 3;
    private static final int CHUNK_TICK_COUNT = 4;
    private static final int CHUNK_SNAPSHOT = 5;
    private static final int CHUNK_DELTAS = 6;

    private static final int TICKDATA_SIMULATION_RUN_ID = 1;
    private static final int TICKDATA_TICK_NUMBER = 2;
    private static final int TICKDATA_CAPTURE_TIME_MS = 3;
    private static final int TICKDATA_ORGANISMS = 4;
    private static final int TICKDATA_CELL_COLUMNS = 5;
    private static final int TICKDATA_RNG_STATE = 6;
    private static final int TICKDATA_PLUGIN_STATES = 7;
    private static final int TICKDATA_TOTAL_ORGANISMS_CREATED = 8;
    private static final int TICKDATA_TOTAL_UNIQUE_GENOMES = 9;
    private static final int TICKDATA_GENOME_HASHES = 10;

    private static final int DELTA_TICK_NUMBER = 1;
    private static final int DELTA_CAPTURE_TIME_MS = 2;
    private static final int DELTA_DELTA_TYPE = 3;
    private static final int DELTA_CHANGED_CELLS = 4;
    private static final int DELTA_ORGANISMS = 5;
    private static final int DELTA_TOTAL_ORGANISMS_CREATED = 6;
    private static final int DELTA_RNG_STATE = 7;
    private static final int DELTA_PLUGIN_STATES = 8;
    private static final int DELTA_TOTAL_UNIQUE_GENOMES = 9;

    private final ICompressionCodec codec;
    private String mergeSql;

    /**
     * Creates RowPerChunkStrategy with optional compression for the BLOB storage.
     * <p>
     * Note: This compression is for the outer BLOB storage layer. The TickDataChunk
     * itself may already contain compressed delta data internally.
     *
     * @param options Config with optional compression block
     */
    public RowPerChunkStrategy(Config options) {
        super(options);
        this.codec = CompressionCodecFactory.create(options);
        log.debug("RowPerChunkStrategy initialized with compression: {}", codec.getName());
    }

    @Override
    public void createTables(Connection conn, int dimensions) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Create environment_chunks table
            // first_tick is PRIMARY KEY (automatic B-tree index)
            // last_tick indexed for range queries
            H2SchemaUtil.executeDdlIfNotExists(
                stmt,
                "CREATE TABLE IF NOT EXISTS environment_chunks (" +
                "  first_tick BIGINT PRIMARY KEY," +
                "  last_tick BIGINT NOT NULL," +
                "  chunk_blob BYTEA NOT NULL" +
                ")",
                "environment_chunks"
            );

            // Index on last_tick for efficient range queries
            H2SchemaUtil.executeDdlIfNotExists(
                stmt,
                "CREATE INDEX IF NOT EXISTS idx_env_chunks_last_tick ON environment_chunks(last_tick)",
                "idx_env_chunks_last_tick"
            );
        }

        // Cache SQL string for MERGE operations
        this.mergeSql = "MERGE INTO environment_chunks (first_tick, last_tick, chunk_blob) " +
                       "KEY (first_tick) VALUES (?, ?, ?)";

        log.debug("Environment chunk tables created for {} dimensions", dimensions);
    }

    @Override
    public String getMergeSql() {
        return mergeSql;
    }

    @Override
    public void writeChunks(Connection conn, List<TickDataChunk> chunks) throws SQLException {
        if (chunks.isEmpty()) {
            return;
        }

        try (PreparedStatement stmt = conn.prepareStatement(mergeSql)) {
            for (TickDataChunk chunk : chunks) {
                // Validate chunk has required fields
                if (!chunk.hasSnapshot()) {
                    log.warn("Chunk starting at tick {} has no snapshot - skipping",
                             chunk.getSnapshot().getTickNumber());
                    continue;
                }

                long firstTick = chunk.getSnapshot().getTickNumber();
                long lastTick = calculateLastTick(chunk);
                byte[] chunkBlob = serializeChunk(chunk);

                stmt.setLong(1, firstTick);
                stmt.setLong(2, lastTick);
                stmt.setBytes(3, chunkBlob);
                stmt.addBatch();
                Thread.yield();
            }

            stmt.executeBatch();
            log.debug("Wrote {} chunks to environment_chunks table", chunks.size());
        }
    }

    /**
     * Calculates the last tick number in the chunk.
     * <p>
     * The last tick is either:
     * <ul>
     *   <li>The tick number of the last delta (if deltas exist)</li>
     *   <li>The snapshot tick number (if no deltas)</li>
     * </ul>
     */
    private long calculateLastTick(TickDataChunk chunk) {
        int deltaCount = chunk.getDeltasCount();
        if (deltaCount > 0) {
            return chunk.getDeltas(deltaCount - 1).getTickNumber();
        }
        return chunk.getSnapshot().getTickNumber();
    }

    /**
     * Serializes the chunk to a compressed BLOB.
     * <p>
     * Uses the configured compression codec to wrap the Protobuf serialization.
     */
    private byte[] serializeChunk(TickDataChunk chunk) throws SQLException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (OutputStream compressed = codec.wrapOutputStream(baos)) {
                chunk.writeTo(compressed);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new SQLException("Failed to serialize chunk starting at tick: " +
                                   chunk.getSnapshot().getTickNumber(), e);
        }
    }

    /**
     * Reads the chunk containing the specified tick, using a streaming partial parser
     * that skips fields not needed for environment rendering (organisms, RNG state,
     * plugin states, genome hashes).
     * <p>
     * This reduces heap allocation by avoiding creation of OrganismState objects,
     * which can amount to ~50MB per chunk. The returned TickDataChunk has empty
     * organism lists but complete CellDataColumns, compatible with
     * {@link org.evochora.datapipeline.utils.delta.DeltaCodec.Decoder#decompressTick}.
     */
    @Override
    public TickDataChunk readChunkContaining(Connection conn, long tickNumber)
            throws SQLException, TickNotFoundException {
        byte[] blobData = queryChunkBlob(conn, tickNumber);
        return parseChunkForEnvironment(blobData);
    }

    /**
     * Queries the compressed chunk BLOB from the database.
     *
     * @param conn Database connection (schema already set)
     * @param tickNumber Tick number to find
     * @return The compressed BLOB bytes
     * @throws SQLException if database read fails
     * @throws TickNotFoundException if no chunk contains the requested tick
     */
    private byte[] queryChunkBlob(Connection conn, long tickNumber)
            throws SQLException, TickNotFoundException {

        String sql = "SELECT chunk_blob FROM environment_chunks WHERE first_tick <= ? AND last_tick >= ?";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, tickNumber);
            stmt.setLong(2, tickNumber);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new TickNotFoundException("No chunk found containing tick " + tickNumber);
                }

                byte[] blobData = rs.getBytes("chunk_blob");
                if (blobData == null || blobData.length == 0) {
                    throw new TickNotFoundException("Chunk for tick " + tickNumber + " has empty BLOB");
                }

                return blobData;
            }
        }
    }

    // ========================================================================
    // Streaming partial parser — skips organisms, RNG, plugins, genome hashes
    // ========================================================================

    /**
     * Parses a compressed BLOB using {@link CodedInputStream}, streaming decompression
     * directly without intermediate byte[] allocation.
     */
    private TickDataChunk parseChunkForEnvironment(byte[] compressedBlob) throws SQLException {
        try {
            ICompressionCodec detectedCodec = CompressionCodecFactory.detectFromMagicBytes(compressedBlob);

            try (InputStream decompressed = detectedCodec.wrapInputStream(new ByteArrayInputStream(compressedBlob))) {
                CodedInputStream cis = CodedInputStream.newInstance(decompressed);
                cis.setSizeLimit(Integer.MAX_VALUE);
                return parseChunk(cis);
            }
        } catch (IOException e) {
            throw new SQLException("Failed to parse chunk for environment rendering", e);
        }
    }

    private TickDataChunk parseChunk(CodedInputStream cis) throws IOException {
        TickDataChunk.Builder builder = TickDataChunk.newBuilder();

        while (true) {
            int tag = cis.readTag();
            if (tag == 0) break;

            int fieldNumber = WireFormat.getTagFieldNumber(tag);

            switch (fieldNumber) {
                case CHUNK_SIMULATION_RUN_ID -> builder.setSimulationRunId(cis.readString());
                case CHUNK_FIRST_TICK -> builder.setFirstTick(cis.readInt64());
                case CHUNK_LAST_TICK -> builder.setLastTick(cis.readInt64());
                case CHUNK_TICK_COUNT -> builder.setTickCount(cis.readInt32());
                case CHUNK_SNAPSHOT -> {
                    int length = cis.readRawVarint32();
                    int oldLimit = cis.pushLimit(length);
                    builder.setSnapshot(parseTickData(cis));
                    cis.popLimit(oldLimit);
                }
                case CHUNK_DELTAS -> {
                    int length = cis.readRawVarint32();
                    int oldLimit = cis.pushLimit(length);
                    builder.addDeltas(parseTickDelta(cis));
                    cis.popLimit(oldLimit);
                }
                default -> cis.skipField(tag);
            }
        }

        return builder.build();
    }

    private TickData parseTickData(CodedInputStream cis) throws IOException {
        TickData.Builder builder = TickData.newBuilder();

        while (true) {
            int tag = cis.readTag();
            if (tag == 0) break;

            int fieldNumber = WireFormat.getTagFieldNumber(tag);

            switch (fieldNumber) {
                case TICKDATA_SIMULATION_RUN_ID -> builder.setSimulationRunId(cis.readString());
                case TICKDATA_TICK_NUMBER -> builder.setTickNumber(cis.readInt64());
                case TICKDATA_CAPTURE_TIME_MS -> builder.setCaptureTimeMs(cis.readInt64());
                case TICKDATA_CELL_COLUMNS -> builder.setCellColumns(CellDataColumns.parseFrom(cis.readBytes()));
                case TICKDATA_TOTAL_ORGANISMS_CREATED -> builder.setTotalOrganismsCreated(cis.readInt64());
                case TICKDATA_TOTAL_UNIQUE_GENOMES -> builder.setTotalUniqueGenomes(cis.readInt64());
                case TICKDATA_ORGANISMS, TICKDATA_RNG_STATE,
                     TICKDATA_PLUGIN_STATES, TICKDATA_GENOME_HASHES -> cis.skipField(tag);
                default -> cis.skipField(tag);
            }
        }

        return builder.build();
    }

    private TickDelta parseTickDelta(CodedInputStream cis) throws IOException {
        TickDelta.Builder builder = TickDelta.newBuilder();

        while (true) {
            int tag = cis.readTag();
            if (tag == 0) break;

            int fieldNumber = WireFormat.getTagFieldNumber(tag);

            switch (fieldNumber) {
                case DELTA_TICK_NUMBER -> builder.setTickNumber(cis.readInt64());
                case DELTA_CAPTURE_TIME_MS -> builder.setCaptureTimeMs(cis.readInt64());
                case DELTA_DELTA_TYPE -> builder.setDeltaType(DeltaType.forNumber(cis.readEnum()));
                case DELTA_CHANGED_CELLS -> builder.setChangedCells(CellDataColumns.parseFrom(cis.readBytes()));
                case DELTA_TOTAL_ORGANISMS_CREATED -> builder.setTotalOrganismsCreated(cis.readInt64());
                case DELTA_TOTAL_UNIQUE_GENOMES -> builder.setTotalUniqueGenomes(cis.readInt64());
                case DELTA_ORGANISMS, DELTA_RNG_STATE, DELTA_PLUGIN_STATES -> cis.skipField(tag);
                default -> cis.skipField(tag);
            }
        }

        return builder.build();
    }
}
