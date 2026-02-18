package org.evochora.datapipeline.resources.database.h2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

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
 * RowPerChunkStrategy: Stores environment chunk data as files on the filesystem,
 * with H2 providing only the tick-range index for fast lookups.
 * <p>
 * Chunk data (compressed Protobuf) is written to individual files under a configurable
 * directory, organized by H2 schema name (one subdirectory per simulation run).
 * H2 stores only {@code first_tick} and {@code last_tick} per chunk — two BIGINTs per row,
 * enabling sub-millisecond index lookups even in very large databases.
 * <p>
 * <strong>Read optimization:</strong> When reading chunks, only fields needed for
 * environment rendering are parsed (CellDataColumns, metadata). Heavy fields like
 * OrganismState lists, RNG state, and plugin states are skipped at the wire level
 * using {@link CodedInputStream}, reducing heap allocation and GC pressure.
 * <p>
 * <strong>File layout:</strong>
 * <pre>
 * {chunkDirectory}/{schema}/.chunk_meta                           (metadata)
 * {chunkDirectory}/{schema}/{bucket}/chunk_{firstTick}.pb         (uncompressed)
 * {chunkDirectory}/{schema}/{bucket}/chunk_{firstTick}.pb.zst     (zstd compressed)
 * </pre>
 * <p>
 * <strong>Schema:</strong>
 * <pre>
 * CREATE TABLE environment_chunks (
 *   first_tick BIGINT PRIMARY KEY,
 *   last_tick BIGINT NOT NULL
 * )
 * </pre>
 * <p>
 * <strong>Write safety:</strong> Files are written via temp file + atomic rename to
 * prevent corrupt partial files on crash. Files are written before the H2 MERGE so
 * that a failed MERGE leaves only a harmless orphan file, never an H2 entry pointing
 * to a missing file.
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

    private static final String CHUNK_META_FILENAME = ".chunk_meta";
    private static final String META_KEY_TICKS_PER_SUBDIR = "ticksPerSubdirectory";
    private static final int DEFAULT_MAX_FILES_PER_DIRECTORY = 10_000;

    private final ICompressionCodec codec;
    private final Path chunkDirectory;
    private final int maxFilesPerDirectory;
    private String mergeSql;

    /** Cached ticksPerSubdirectory per schema directory (loaded from .chunk_meta). */
    private final ConcurrentHashMap<Path, Long> metadataCache = new ConcurrentHashMap<>();

    /**
     * Creates RowPerChunkStrategy with file-based chunk storage.
     * <p>
     * Requires {@code chunkDirectory} in config to specify where chunk files are stored.
     * Compression settings control how chunk data is compressed before writing to files.
     * <p>
     * {@code maxFilesPerDirectory} controls automatic subdirectory partitioning.
     * On first write, {@code ticksPerSubdirectory} is computed as
     * {@code maxFilesPerDirectory × chunkTickStep} (where chunkTickStep accounts for
     * the sampling interval) and persisted in a {@code .chunk_meta} file per run.
     * Subsequent writes and reads use this persisted value.
     *
     * @param options Config with required {@code chunkDirectory} and optional {@code compression} block
     * @throws IllegalArgumentException if {@code chunkDirectory} is missing from config
     */
    public RowPerChunkStrategy(Config options) {
        super(options);
        this.codec = CompressionCodecFactory.create(options);

        if (!options.hasPath("chunkDirectory")) {
            throw new IllegalArgumentException(
                    "RowPerChunkStrategy requires 'chunkDirectory' in config");
        }
        this.chunkDirectory = Path.of(options.getString("chunkDirectory"));
        this.maxFilesPerDirectory = options.hasPath("maxFilesPerDirectory")
                ? options.getInt("maxFilesPerDirectory")
                : DEFAULT_MAX_FILES_PER_DIRECTORY;

        log.debug("RowPerChunkStrategy initialized: chunkDirectory={}, maxFilesPerDir={}, compression={}",
                chunkDirectory, maxFilesPerDirectory, codec.getName());
    }

    /**
     * Returns the base directory where chunk files are stored.
     *
     * @return the chunk directory path
     */
    public Path getChunkDirectory() {
        return chunkDirectory;
    }

    @Override
    public void createTables(Connection conn, int dimensions) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // H2 stores only the tick-range index; chunk data lives on the filesystem
            H2SchemaUtil.executeDdlIfNotExists(
                stmt,
                "CREATE TABLE IF NOT EXISTS environment_chunks (" +
                "  first_tick BIGINT PRIMARY KEY," +
                "  last_tick BIGINT NOT NULL" +
                ")",
                "environment_chunks"
            );

            H2SchemaUtil.executeDdlIfNotExists(
                stmt,
                "CREATE INDEX IF NOT EXISTS idx_env_chunks_last_tick ON environment_chunks(last_tick)",
                "idx_env_chunks_last_tick"
            );
        }

        this.mergeSql = "MERGE INTO environment_chunks (first_tick, last_tick) " +
                       "KEY (first_tick) VALUES (?, ?)";

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

        // Ensure the schema-specific directory exists
        Path schemaDir = resolveSchemaDirectory(conn);
        try {
            Files.createDirectories(schemaDir);
        } catch (IOException e) {
            throw new SQLException("Failed to create chunk directory: " + schemaDir, e);
        }

        // Ensure metadata exists (computed from first chunk's tickCount)
        long ticksPerSubdir = ensureChunkMetadata(schemaDir, chunks.get(0));

        try (PreparedStatement stmt = conn.prepareStatement(mergeSql)) {
            for (TickDataChunk chunk : chunks) {
                if (!chunk.hasSnapshot()) {
                    log.warn("Chunk starting at tick {} has no snapshot - skipping",
                             chunk.getSnapshot().getTickNumber());
                    continue;
                }

                long firstTick = chunk.getSnapshot().getTickNumber();
                long lastTick = calculateLastTick(chunk);
                byte[] chunkData = serializeChunk(chunk);

                // Resolve subdirectory, ensure it exists, then write file first
                // (orphan file is harmless; missing file is not)
                Path subdir = resolveSubdirectory(schemaDir, firstTick, ticksPerSubdir);
                try {
                    Files.createDirectories(subdir);
                } catch (IOException e) {
                    throw new SQLException("Failed to create chunk subdirectory: " + subdir, e);
                }
                writeChunkFile(subdir, firstTick, chunkData);

                stmt.setLong(1, firstTick);
                stmt.setLong(2, lastTick);
                stmt.addBatch();
                Thread.yield();
            }

            stmt.executeBatch();
            log.debug("Wrote {} chunks to environment_chunks table and filesystem", chunks.size());
        }
    }

    /**
     * Returns the chunk filename for the given first tick.
     * <p>
     * Format: {@code chunk_{firstTick}.pb} (uncompressed) or
     * {@code chunk_{firstTick}.pb.zst} (zstd compressed).
     *
     * @param firstTick the first tick of the chunk
     * @return the filename including codec-specific extension
     */
    private String chunkFilename(long firstTick) {
        return "chunk_" + firstTick + ".pb" + codec.getFileExtension();
    }

    /**
     * Writes compressed chunk data to a file using temp file + atomic rename.
     *
     * @param directory the target directory (schema subdirectory)
     * @param firstTick the first tick of the chunk (used in filename)
     * @param chunkData the compressed chunk bytes
     * @throws SQLException if file I/O fails
     */
    private void writeChunkFile(Path directory, long firstTick, byte[] chunkData)
            throws SQLException {
        Path targetFile = directory.resolve(chunkFilename(firstTick));
        Path tempFile = directory.resolve(chunkFilename(firstTick) + ".tmp");

        try {
            Files.write(tempFile, chunkData);
            Files.move(tempFile, targetFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Clean up temp file on failure
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw new SQLException("Failed to write chunk file: " + targetFile, e);
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
     * Serializes the chunk to compressed bytes.
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
     * Reads the chunk containing the specified tick from the filesystem, using a
     * streaming partial parser that skips fields not needed for environment rendering
     * (organisms, RNG state, plugin states, genome hashes).
     * <p>
     * The H2 database is queried only for the tick-range index (returns a BIGINT,
     * not a BLOB), then the chunk file is read directly from disk.
     */
    @Override
    public TickDataChunk readChunkContaining(Connection conn, long tickNumber)
            throws SQLException, TickNotFoundException {
        long firstTick = queryFirstTick(conn, tickNumber);
        byte[] chunkData = readChunkFile(conn, firstTick);
        return parseChunkForEnvironment(chunkData);
    }

    /**
     * Queries H2 for the first_tick of the chunk containing the specified tick.
     *
     * @param conn Database connection (schema already set)
     * @param tickNumber Tick number to find
     * @return The first_tick of the containing chunk
     * @throws SQLException if database read fails
     * @throws TickNotFoundException if no chunk contains the requested tick
     */
    private long queryFirstTick(Connection conn, long tickNumber)
            throws SQLException, TickNotFoundException {

        String sql = "SELECT first_tick FROM environment_chunks WHERE first_tick <= ? AND last_tick >= ?";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, tickNumber);
            stmt.setLong(2, tickNumber);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new TickNotFoundException("No chunk found containing tick " + tickNumber);
                }
                return rs.getLong("first_tick");
            }
        }
    }

    /**
     * Reads the compressed chunk file from the filesystem.
     * <p>
     * Uses the persisted {@code .chunk_meta} to determine the correct subdirectory.
     *
     * @param conn Database connection (used to resolve schema directory)
     * @param firstTick First tick of the chunk
     * @return The compressed chunk bytes
     * @throws SQLException if file I/O fails
     * @throws TickNotFoundException if the chunk file does not exist
     */
    private byte[] readChunkFile(Connection conn, long firstTick)
            throws SQLException, TickNotFoundException {
        Path schemaDir = resolveSchemaDirectory(conn);
        long ticksPerSubdir = loadChunkMetadata(schemaDir);
        Path subdir = resolveSubdirectory(schemaDir, firstTick, ticksPerSubdir);
        Path chunkFile = subdir.resolve(chunkFilename(firstTick));

        if (!Files.exists(chunkFile)) {
            throw new TickNotFoundException(
                    "Chunk file not found for tick " + firstTick + ": " + chunkFile);
        }

        try {
            return Files.readAllBytes(chunkFile);
        } catch (IOException e) {
            throw new SQLException("Failed to read chunk file: " + chunkFile, e);
        }
    }

    /**
     * Resolves the schema-specific subdirectory for chunk files.
     *
     * @param conn Database connection with schema already set
     * @return Path to the schema directory (e.g., {chunkDirectory}/SIM_20260216_...)
     * @throws SQLException if schema name cannot be determined
     */
    private Path resolveSchemaDirectory(Connection conn) throws SQLException {
        String schema = conn.getSchema();
        if (schema == null || schema.isEmpty()) {
            throw new SQLException("Connection has no schema set - cannot resolve chunk directory");
        }
        return chunkDirectory.resolve(schema);
    }

    // ========================================================================
    // Subdirectory partitioning and chunk metadata
    // ========================================================================

    /**
     * Resolves the subdirectory path for a given firstTick (no I/O).
     * <p>
     * Subdirectory name is zero-padded bucket index: {@code firstTick / ticksPerSubdirectory}.
     *
     * @param schemaDir the schema-specific base directory
     * @param firstTick the first tick of the chunk
     * @param ticksPerSubdir ticks per subdirectory (from .chunk_meta)
     * @return path to the subdirectory
     */
    private Path resolveSubdirectory(Path schemaDir, long firstTick, long ticksPerSubdir) {
        long bucket = firstTick / ticksPerSubdir;
        return schemaDir.resolve(String.format("%04d", bucket));
    }

    /**
     * Ensures the {@code .chunk_meta} file exists in the schema directory.
     * <p>
     * Thread-safe via {@link ConcurrentHashMap#computeIfAbsent}: only one thread
     * per schema directory computes and writes the metadata. Cross-process safety
     * is achieved via atomic file write (temp file + {@code ATOMIC_MOVE}): if another
     * process wins the race, the loser reads back the winner's value.
     *
     * @param schemaDir the schema-specific directory
     * @param firstChunk the first chunk being written (used to determine tickCount)
     * @return the ticksPerSubdirectory value
     * @throws SQLException if metadata cannot be written or read
     */
    private long ensureChunkMetadata(Path schemaDir, TickDataChunk firstChunk) throws SQLException {
        try {
            return metadataCache.computeIfAbsent(schemaDir,
                    dir -> computeOrLoadMetadata(dir, firstChunk));
        } catch (UncheckedSQLException e) {
            throw e.getCause();
        }
    }

    /**
     * Computes {@code ticksPerSubdirectory} and writes it atomically to {@code .chunk_meta},
     * or loads the existing file if another process created it first.
     *
     * @param schemaDir the schema-specific directory
     * @param firstChunk the first chunk (used to determine tickCount)
     * @return the ticksPerSubdirectory value
     * @throws UncheckedSQLException if I/O fails
     */
    private long computeOrLoadMetadata(Path schemaDir, TickDataChunk firstChunk) {
        Path metaFile = schemaDir.resolve(CHUNK_META_FILENAME);

        // Another process may have created it already
        if (Files.exists(metaFile)) {
            return readMetadataFile(metaFile);
        }

        // Compute from first chunk, accounting for sampling interval
        long chunkTickStep = estimateChunkTickStep(firstChunk);
        long ticksPerSubdir = (long) maxFilesPerDirectory * chunkTickStep;

        // Atomic write: temp file + rename
        Path tempFile = schemaDir.resolve(CHUNK_META_FILENAME + ".tmp");
        Properties props = new Properties();
        props.setProperty(META_KEY_TICKS_PER_SUBDIR, Long.toString(ticksPerSubdir));

        try (OutputStream out = Files.newOutputStream(tempFile)) {
            props.store(out, "Chunk storage metadata - do not edit");
        } catch (IOException e) {
            throw new UncheckedSQLException(
                    new SQLException("Failed to write chunk metadata: " + metaFile, e));
        }

        try {
            Files.move(tempFile, metaFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Another process won the race — use their value
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            if (Files.exists(metaFile)) {
                return readMetadataFile(metaFile);
            }
            throw new UncheckedSQLException(
                    new SQLException("Failed to create chunk metadata: " + metaFile, e));
        }

        log.debug("Created chunk metadata: ticksPerSubdirectory={} (maxFiles={} × chunkTickStep={})",
                ticksPerSubdir, maxFilesPerDirectory, chunkTickStep);
        return ticksPerSubdir;
    }

    /**
     * Estimates the distance in tick numbers between consecutive chunks' first ticks.
     * <p>
     * With {@code samplingInterval > 1}, tick numbers in a chunk are spaced apart
     * (e.g., 0, 1000, 2000, ...). The chunk's {@code tickCount} only counts sampled
     * ticks, not the actual tick number range. This method infers the sampling interval
     * from the chunk's tick range and computes the real spacing.
     *
     * @param chunk the first chunk being written
     * @return estimated tick step between consecutive chunks
     */
    private long estimateChunkTickStep(TickDataChunk chunk) {
        int tickCount = Math.max(chunk.getTickCount(), 1);
        if (tickCount <= 1 || chunk.getDeltasCount() == 0) {
            return tickCount;
        }

        long firstTick = chunk.getSnapshot().getTickNumber();
        long lastTick = chunk.getDeltas(chunk.getDeltasCount() - 1).getTickNumber();
        long samplingInterval = (lastTick - firstTick) / (tickCount - 1);

        return tickCount * Math.max(samplingInterval, 1);
    }

    /**
     * Reads {@code ticksPerSubdirectory} from a {@code .chunk_meta} Properties file.
     *
     * @param metaFile the metadata file to read
     * @return the ticksPerSubdirectory value
     * @throws UncheckedSQLException if the file is missing required keys or I/O fails
     */
    private long readMetadataFile(Path metaFile) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(metaFile)) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedSQLException(
                    new SQLException("Failed to read chunk metadata: " + metaFile, e));
        }

        String value = props.getProperty(META_KEY_TICKS_PER_SUBDIR);
        if (value == null) {
            throw new UncheckedSQLException(new SQLException(
                    "Missing '" + META_KEY_TICKS_PER_SUBDIR + "' in " + metaFile));
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new UncheckedSQLException(new SQLException(
                    "Invalid '" + META_KEY_TICKS_PER_SUBDIR + "' value '" + value + "' in " + metaFile, e));
        }
    }

    /**
     * Loads {@code ticksPerSubdirectory} from the {@code .chunk_meta} file.
     * <p>
     * Thread-safe via {@link ConcurrentHashMap#computeIfAbsent}.
     *
     * @param schemaDir the schema-specific directory
     * @return the ticksPerSubdirectory value
     * @throws SQLException if metadata file is missing or unreadable
     */
    private long loadChunkMetadata(Path schemaDir) throws SQLException {
        try {
            return metadataCache.computeIfAbsent(schemaDir, dir -> {
                Path metaFile = dir.resolve(CHUNK_META_FILENAME);
                if (!Files.exists(metaFile)) {
                    throw new UncheckedSQLException(new SQLException(
                            "Chunk metadata not found: " + metaFile + ". " +
                            "This run may have been created with an older version."));
                }
                return readMetadataFile(metaFile);
            });
        } catch (UncheckedSQLException e) {
            throw e.getCause();
        }
    }

    /**
     * Wraps {@link SQLException} for use inside lambdas that don't allow checked exceptions
     * (e.g., {@link ConcurrentHashMap#computeIfAbsent}).
     */
    private static class UncheckedSQLException extends RuntimeException {

        UncheckedSQLException(SQLException cause) {
            super(cause);
        }

        @Override
        public synchronized SQLException getCause() {
            return (SQLException) super.getCause();
        }
    }

    // ========================================================================
    // Streaming partial parser — skips organisms, RNG, plugins, genome hashes
    // ========================================================================

    /**
     * Parses compressed chunk data using {@link CodedInputStream}, streaming decompression
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
