package org.evochora.datapipeline.resources.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.evochora.datapipeline.api.resources.storage.CheckedConsumer;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.PluginState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.RawChunk;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.IResourceBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.storage.StreamingWriteResult;
import org.evochora.datapipeline.resources.AbstractResource;
import org.evochora.datapipeline.resources.storage.wrappers.MonitoredAnalyticsStorageWriter;
import org.evochora.datapipeline.resources.storage.wrappers.MonitoredBatchStorageReader;
import org.evochora.datapipeline.resources.storage.wrappers.MonitoredBatchStorageWriter;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowCounter;
import org.evochora.datapipeline.utils.monitoring.SlidingWindowPercentiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import com.google.protobuf.WireFormat;
import com.typesafe.config.Config;

/**
 * Abstract base class for batch storage resources with hierarchical folder organization.
 * <p>
 * This class implements all the high-level logic for batch storage:
 * <ul>
 *   <li>Hierarchical folder path calculation based on tick ranges</li>
 *   <li>Atomic batch file writing with compression</li>
 *   <li>Base monitoring and metrics tracking</li>
 * </ul>
 * <p>
 * Subclasses only need to implement low-level I/O primitives for their specific
 * storage backend (filesystem, S3, etc.). Inherits IMonitorable infrastructure
 * from {@link AbstractResource}, with a hook method {@link #addCustomMetrics(Map)}
 * for implementation-specific metrics.
 */
public abstract class AbstractBatchStorageResource extends AbstractResource
    implements IBatchStorageWrite, IResourceBatchStorageRead, IContextualResource {

    private static final Logger log = LoggerFactory.getLogger(AbstractBatchStorageResource.class);

    // Configuration
    protected final List<Long> folderLevels;
    protected final ICompressionCodec codec;
    protected final int metricsWindowSeconds;

    // Base metrics tracking (all storage implementations)
    protected final java.util.concurrent.atomic.AtomicLong writeOperations = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong readOperations = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong bytesWritten = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong bytesRead = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong writeErrors = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong readErrors = new java.util.concurrent.atomic.AtomicLong(0);

    // Batch size tracking metrics (O(1) operations only, helps diagnose memory issues)
    protected final java.util.concurrent.atomic.AtomicLong lastReadBatchSizeMB = new java.util.concurrent.atomic.AtomicLong(0);
    protected final java.util.concurrent.atomic.AtomicLong maxReadBatchSizeMB = new java.util.concurrent.atomic.AtomicLong(0);

    // Performance metrics (sliding window using unified utils)
    private final SlidingWindowCounter writeOpsCounter;
    private final SlidingWindowCounter writeBytesCounter;
    private final SlidingWindowPercentiles writeLatencyTracker;
    private final SlidingWindowCounter readOpsCounter;
    private final SlidingWindowCounter readBytesCounter;
    private final SlidingWindowPercentiles readLatencyTracker;

    protected AbstractBatchStorageResource(String name, Config options) {
        super(name, options);

        // Initialize compression codec (fail-fast if environment validation fails)
        try {
            this.codec = org.evochora.datapipeline.utils.compression.CompressionCodecFactory.createAndValidate(options);
            if (!"none".equals(codec.getName())) {
                log.debug("Storage '{}' using compression: codec={}, level={}",
                    name, codec.getName(), codec.getLevel());
            }
        } catch (org.evochora.datapipeline.utils.compression.CompressionException e) {
            throw new IllegalStateException("Failed to initialize compression codec for storage '" + name + "'", e);
        }

        // Parse folder structure configuration
        if (options.hasPath("folderStructure.levels")) {
            this.folderLevels = options.getLongList("folderStructure.levels")
                .stream()
                .map(Number::longValue)
                .collect(Collectors.toList());
        } else {
            // Default: [100M, 100K]
            this.folderLevels = Arrays.asList(100_000_000L, 100_000L);
        }

        if (folderLevels.isEmpty()) {
            throw new IllegalArgumentException("folderStructure.levels cannot be empty");
        }
        for (Long level : folderLevels) {
            if (level <= 0) {
                throw new IllegalArgumentException("All folder levels must be positive");
            }
        }

        // Parse metrics window configuration (default: 5 seconds)
        this.metricsWindowSeconds = options.hasPath("metricsWindowSeconds")
            ? options.getInt("metricsWindowSeconds")
            : 5;

        // Initialize performance metrics trackers
        this.writeOpsCounter = new SlidingWindowCounter(metricsWindowSeconds);
        this.writeBytesCounter = new SlidingWindowCounter(metricsWindowSeconds);
        this.writeLatencyTracker = new SlidingWindowPercentiles(metricsWindowSeconds);
        this.readOpsCounter = new SlidingWindowCounter(metricsWindowSeconds);
        this.readBytesCounter = new SlidingWindowCounter(metricsWindowSeconds);
        this.readLatencyTracker = new SlidingWindowPercentiles(metricsWindowSeconds);

        log.debug("Storage '{}' initialized: codec={}, level={}, folders={}, metricsWindow={}s",
            name, codec.getName(), codec.getLevel(), folderLevels, metricsWindowSeconds);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Peeks the first chunk for metadata (simulationRunId, firstTick), writes all chunks
     * to a temp file via {@link #writeChunksToTempFile}, then atomically renames to the
     * final path via {@link #finalizeStreamingWrite}. The {@link TrackingIterable} validates
     * that all chunks share the same simulationRunId.
     *
     * @param chunks iterator of chunks to write (must contain at least one element)
     * @return streaming write result with path, tick range, chunk count, and byte count
     * @throws IOException              if the streaming write or finalization fails
     * @throws IllegalArgumentException if the iterator is null or empty
     * @throws IllegalStateException    if chunks have mismatched simulationRunIds
     */
    @Override
    public StreamingWriteResult writeChunkBatchStreaming(Iterator<TickDataChunk> chunks) throws IOException {
        if (chunks == null || !chunks.hasNext()) {
            throw new IllegalArgumentException("chunks iterator cannot be null or empty");
        }

        // 1. Peek first chunk for metadata (simulationId, firstTick)
        TickDataChunk firstChunk = chunks.next();
        String simulationId = firstChunk.getSimulationRunId();
        long firstTick = firstChunk.getFirstTick();

        // 2. Compute folder path from firstTick
        String folderPath = simulationId + "/raw/" + calculateFolderPath(firstTick);

        // 3. Write to temp file, tracking lastTick as we go
        TrackingIterable trackingIterable = new TrackingIterable(firstChunk, chunks);
        long writeStart = System.nanoTime();
        TempWriteResult tempResult = writeChunksToTempFile(folderPath, trackingIterable, codec);
        long writeLatency = System.nanoTime() - writeStart;

        long lastTick = trackingIterable.getLastTick();
        int chunkCount = trackingIterable.getChunkCount();

        // 4. Compute final path from actual tick range
        String logicalFilename = String.format("batch_%019d_%019d.pb", firstTick, lastTick);
        String physicalPath = folderPath + "/" + logicalFilename + codec.getFileExtension();

        // 5. Atomic rename: temp → final
        finalizeStreamingWrite(tempResult.tempHandle(), physicalPath);

        // 6. Record metrics
        recordWrite(tempResult.bytesWritten(), writeLatency);
        log.debug("Wrote streaming batch {} with {} chunks (ticks {}-{})",
            physicalPath, chunkCount, firstTick, lastTick);

        return new StreamingWriteResult(StoragePath.of(physicalPath), simulationId, firstTick, lastTick,
            chunkCount, trackingIterable.getTotalTickCount(), tempResult.bytesWritten());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Efficient implementation that reads raw protobuf bytes without full parsing.
     * For each chunk in the batch file, reads the raw message bytes and extracts only
     * the three metadata fields (firstTick, lastTick, tickCount) via partial parse.
     * Peak heap: one raw chunk (~25 MB for 4000x3000 environment).
     * <p>
     * <strong>Thread Safety:</strong> Thread-safe. Multiple callers can read concurrently.
     */
    @Override
    public void forEachRawChunk(StoragePath path,
                                CheckedConsumer<RawChunk> consumer) throws Exception {
        if (path == null) {
            throw new IllegalArgumentException("path cannot be null");
        }
        if (consumer == null) {
            throw new IllegalArgumentException("consumer cannot be null");
        }

        long startNanos = System.nanoTime();

        ICompressionCodec detectedCodec = org.evochora.datapipeline.utils.compression.CompressionCodecFactory
            .detectFromExtension(path.asString());

        long bytesRead;
        try (InputStream rawStream = openRawStream(path.asString());
             CountingInputStream countingStream = new CountingInputStream(rawStream);
             InputStream decompressedStream = detectedCodec.wrapInputStream(countingStream)) {

            CodedInputStream cis = CodedInputStream.newInstance(decompressedStream);
            cis.setSizeLimit(Integer.MAX_VALUE);

            while (!cis.isAtEnd()) {
                int messageSize = cis.readRawVarint32();
                byte[] rawBytes = cis.readRawBytes(messageSize);
                RawChunk rawChunk = partialParseRawChunk(rawBytes);
                consumer.accept(rawChunk);
            }

            bytesRead = countingStream.getBytesRead();
        }

        long batchSizeMB = bytesRead / 1_048_576;
        lastReadBatchSizeMB.set(batchSizeMB);
        maxReadBatchSizeMB.updateAndGet(current -> Math.max(current, batchSizeMB));
        recordRead(bytesRead, System.nanoTime() - startNanos);
    }

    /**
     * Extracts firstTick, lastTick, and tickCount from raw protobuf bytes via partial parse.
     * Scans only the top-level fields, skipping snapshot and delta data entirely.
     *
     * @param rawBytes raw protobuf bytes of a single TickDataChunk message
     * @return RawChunk with metadata and the original raw bytes
     * @throws IOException if parsing fails
     */
    private static RawChunk partialParseRawChunk(byte[] rawBytes) throws IOException {
        CodedInputStream cis = CodedInputStream.newInstance(rawBytes);
        long firstTick = 0;
        long lastTick = 0;
        int tickCount = 0;
        int fieldsFound = 0;

        while (fieldsFound < 3) {
            int tag = cis.readTag();
            if (tag == 0) break;

            switch (WireFormat.getTagFieldNumber(tag)) {
                case TickDataChunk.FIRST_TICK_FIELD_NUMBER:
                    firstTick = cis.readInt64();
                    fieldsFound++;
                    break;
                case TickDataChunk.LAST_TICK_FIELD_NUMBER:
                    lastTick = cis.readInt64();
                    fieldsFound++;
                    break;
                case TickDataChunk.TICK_COUNT_FIELD_NUMBER:
                    tickCount = cis.readInt32();
                    fieldsFound++;
                    break;
                default:
                    cis.skipField(tag);
                    break;
            }
        }

        return new RawChunk(firstTick, lastTick, tickCount, rawBytes);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Streaming implementation that parses one chunk at a time from the compressed protobuf
     * stream, applies the filter and transformer, and passes each chunk to the consumer before
     * parsing the next. Only one parsed chunk is held in memory at any time.
     * <p>
     * <strong>Thread Safety:</strong> Thread-safe. Multiple callers can read concurrently.
     */
    @Override
    public void forEachChunk(StoragePath path, ChunkFieldFilter filter,
                             CheckedConsumer<TickDataChunk> consumer) throws Exception {
        if (path == null) {
            throw new IllegalArgumentException("path cannot be null");
        }
        if (filter == null) {
            throw new IllegalArgumentException("filter cannot be null");
        }
        if (consumer == null) {
            throw new IllegalArgumentException("consumer cannot be null");
        }

        long startNanos = System.nanoTime();

        ICompressionCodec detectedCodec = org.evochora.datapipeline.utils.compression.CompressionCodecFactory
            .detectFromExtension(path.asString());

        long bytesRead;
        try (InputStream rawStream = openRawStream(path.asString());
             CountingInputStream countingStream = new CountingInputStream(rawStream);
             InputStream decompressedStream = detectedCodec.wrapInputStream(countingStream)) {

            if (filter == ChunkFieldFilter.ALL) {
                while (true) {
                    TickDataChunk chunk = TickDataChunk.parseDelimitedFrom(decompressedStream);
                    if (chunk == null) break;
                    consumer.accept(chunk);
                }
            } else {
                CodedInputStream cis = CodedInputStream.newInstance(decompressedStream);
                cis.setSizeLimit(Integer.MAX_VALUE);

                while (!cis.isAtEnd()) {
                    int messageSize = cis.readRawVarint32();
                    int limit = cis.pushLimit(messageSize);
                    TickDataChunk chunk = parseChunkWithFilter(cis, filter);
                    consumer.accept(chunk);
                    cis.skipRawBytes(cis.getBytesUntilLimit());
                    cis.popLimit(limit);
                }
            }

            bytesRead = countingStream.getBytesRead();
        }

        long batchSizeMB = bytesRead / 1_048_576;
        lastReadBatchSizeMB.set(batchSizeMB);
        maxReadBatchSizeMB.updateAndGet(current -> Math.max(current, batchSizeMB));
        recordRead(bytesRead, System.nanoTime() - startNanos);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Optimized implementation that skips delta fields at the protobuf wire level.
     * Only metadata fields and the snapshot are parsed; delta bytes are discarded
     * directly from the decompressed stream without deserialization.
     * <p>
     * Memory profile: compressed bytes (~100 MB) + snapshot TickData (~400 MB heap)
     * instead of full chunk parse (~4-8 GB heap).
     */
    @Override
    public TickData readLastSnapshot(StoragePath path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path cannot be null");
        }

        long readStart = System.nanoTime();
        byte[] compressed = getRaw(path.asString());
        long readLatency = System.nanoTime() - readStart;

        ICompressionCodec detectedCodec = org.evochora.datapipeline.utils.compression.CompressionCodecFactory.detectFromExtension(path.asString());

        TickDataChunk lastChunk = null;
        try (InputStream decompressedStream = detectedCodec.wrapInputStream(new ByteArrayInputStream(compressed))) {
            CodedInputStream cis = CodedInputStream.newInstance(decompressedStream);
            cis.setSizeLimit(Integer.MAX_VALUE);

            while (!cis.isAtEnd()) {
                int messageSize = cis.readRawVarint32();
                int limit = cis.pushLimit(messageSize);
                lastChunk = parseChunkSkippingDeltas(cis);
                cis.skipRawBytes(cis.getBytesUntilLimit());
                cis.popLimit(limit);
            }
        } catch (Exception e) {
            throw new IOException("Failed to read last snapshot from: " + path.asString(), e);
        }

        if (lastChunk == null) {
            throw new IOException("Empty batch file: " + path.asString());
        }

        long batchSizeMB = compressed.length / 1_048_576;
        lastReadBatchSizeMB.set(batchSizeMB);
        maxReadBatchSizeMB.updateAndGet(current -> Math.max(current, batchSizeMB));
        recordRead(compressed.length, readLatency);

        log.debug("Read snapshot-only from {}: tick {}, skipped deltas",
            path, lastChunk.getSnapshot().getTickNumber());

        return lastChunk.getSnapshot();
    }

    /**
     * Parses a TickDataChunk from a CodedInputStream, skipping the {@code deltas} field entirely.
     * <p>
     * Only metadata fields (simulation_run_id, first_tick, last_tick, tick_count) and the
     * snapshot are deserialized. Delta bytes are discarded via {@link CodedInputStream#skipField}
     * which reads and discards data in small buffer chunks without allocating large arrays.
     *
     * @param input CodedInputStream positioned at the start of a TickDataChunk message
     * @return TickDataChunk with snapshot only (no deltas)
     * @throws IOException if parsing fails
     */
    private static TickDataChunk parseChunkSkippingDeltas(CodedInputStream input) throws IOException {
        TickDataChunk.Builder builder = TickDataChunk.newBuilder();

        while (true) {
            int tag = input.readTag();
            if (tag == 0) break;

            switch (WireFormat.getTagFieldNumber(tag)) {
                case TickDataChunk.SIMULATION_RUN_ID_FIELD_NUMBER:
                    builder.setSimulationRunId(input.readString());
                    break;
                case TickDataChunk.FIRST_TICK_FIELD_NUMBER:
                    builder.setFirstTick(input.readInt64());
                    break;
                case TickDataChunk.LAST_TICK_FIELD_NUMBER:
                    builder.setLastTick(input.readInt64());
                    break;
                case TickDataChunk.TICK_COUNT_FIELD_NUMBER:
                    builder.setTickCount(input.readInt32());
                    break;
                case TickDataChunk.SNAPSHOT_FIELD_NUMBER:
                    int length = input.readRawVarint32();
                    int oldLimit = input.pushLimit(length);
                    builder.setSnapshot(TickData.parseFrom(input));
                    input.popLimit(oldLimit);
                    break;
                case TickDataChunk.DELTAS_FIELD_NUMBER:
                    // Skip delta data — not needed for resume. Bytes are discarded
                    // from the stream in small chunks, not loaded into memory.
                    input.skipField(tag);
                    break;
                default:
                    input.skipField(tag);
                    break;
            }
        }

        return builder.build();
    }


    /**
     * Parses a TickDataChunk from a CodedInputStream, applying the given field filter to
     * the snapshot and each delta.
     *
     * @param input  CodedInputStream positioned at the start of a TickDataChunk message
     * @param filter Controls which fields to skip in nested TickData/TickDelta messages
     * @return Parsed TickDataChunk with filtered fields omitted
     * @throws IOException if parsing fails
     */
    private static TickDataChunk parseChunkWithFilter(CodedInputStream input, ChunkFieldFilter filter) throws IOException {
        TickDataChunk.Builder builder = TickDataChunk.newBuilder();

        while (true) {
            int tag = input.readTag();
            if (tag == 0) break;

            switch (WireFormat.getTagFieldNumber(tag)) {
                case TickDataChunk.SIMULATION_RUN_ID_FIELD_NUMBER:
                    builder.setSimulationRunId(input.readString());
                    break;
                case TickDataChunk.FIRST_TICK_FIELD_NUMBER:
                    builder.setFirstTick(input.readInt64());
                    break;
                case TickDataChunk.LAST_TICK_FIELD_NUMBER:
                    builder.setLastTick(input.readInt64());
                    break;
                case TickDataChunk.TICK_COUNT_FIELD_NUMBER:
                    builder.setTickCount(input.readInt32());
                    break;
                case TickDataChunk.SNAPSHOT_FIELD_NUMBER: {
                    int length = input.readRawVarint32();
                    int oldLimit = input.pushLimit(length);
                    builder.setSnapshot(parseTickDataWithFilter(input, filter));
                    input.skipRawBytes(input.getBytesUntilLimit());
                    input.popLimit(oldLimit);
                    break;
                }
                case TickDataChunk.DELTAS_FIELD_NUMBER: {
                    int length = input.readRawVarint32();
                    int oldLimit = input.pushLimit(length);
                    builder.addDeltas(parseTickDeltaWithFilter(input, filter));
                    input.skipRawBytes(input.getBytesUntilLimit());
                    input.popLimit(oldLimit);
                    break;
                }
                default:
                    input.skipField(tag);
                    break;
            }
        }

        return builder.build();
    }

    /**
     * Parses a TickData message from a CodedInputStream, skipping fields based on the filter.
     * <p>
     * Field mapping for TickData:
     * <ul>
     *   <li>{@code SKIP_ORGANISMS}: skips field 4 (organisms)</li>
     *   <li>{@code SKIP_CELLS}: skips field 5 (cell_columns)</li>
     * </ul>
     *
     * @param input  CodedInputStream positioned at the start of a TickData message
     * @param filter Controls which fields to skip
     * @return Parsed TickData with filtered fields omitted
     * @throws IOException if parsing fails
     */
    private static TickData parseTickDataWithFilter(CodedInputStream input, ChunkFieldFilter filter) throws IOException {
        TickData.Builder builder = TickData.newBuilder();

        while (true) {
            int tag = input.readTag();
            if (tag == 0) break;

            int fieldNumber = WireFormat.getTagFieldNumber(tag);

            // Skip organisms when filter is SKIP_ORGANISMS
            if (filter == ChunkFieldFilter.SKIP_ORGANISMS && fieldNumber == TickData.ORGANISMS_FIELD_NUMBER) {
                input.skipField(tag);
                continue;
            }
            // Skip cell_columns when filter is SKIP_CELLS
            if (filter == ChunkFieldFilter.SKIP_CELLS && fieldNumber == TickData.CELL_COLUMNS_FIELD_NUMBER) {
                input.skipField(tag);
                continue;
            }

            switch (fieldNumber) {
                case TickData.SIMULATION_RUN_ID_FIELD_NUMBER:
                    builder.setSimulationRunId(input.readString());
                    break;
                case TickData.TICK_NUMBER_FIELD_NUMBER:
                    builder.setTickNumber(input.readInt64());
                    break;
                case TickData.CAPTURE_TIME_MS_FIELD_NUMBER:
                    builder.setCaptureTimeMs(input.readInt64());
                    break;
                case TickData.ORGANISMS_FIELD_NUMBER: {
                    int length = input.readRawVarint32();
                    int oldLimit = input.pushLimit(length);
                    builder.addOrganisms(OrganismState.parseFrom(input));
                    input.popLimit(oldLimit);
                    break;
                }
                case TickData.CELL_COLUMNS_FIELD_NUMBER: {
                    int length = input.readRawVarint32();
                    int oldLimit = input.pushLimit(length);
                    builder.setCellColumns(CellDataColumns.parseFrom(input));
                    input.popLimit(oldLimit);
                    break;
                }
                case TickData.RNG_STATE_FIELD_NUMBER:
                    builder.setRngState(input.readBytes());
                    break;
                case TickData.PLUGIN_STATES_FIELD_NUMBER: {
                    int length = input.readRawVarint32();
                    int oldLimit = input.pushLimit(length);
                    builder.addPluginStates(PluginState.parseFrom(input));
                    input.popLimit(oldLimit);
                    break;
                }
                case TickData.TOTAL_ORGANISMS_CREATED_FIELD_NUMBER:
                    builder.setTotalOrganismsCreated(input.readInt64());
                    break;
                case TickData.TOTAL_UNIQUE_GENOMES_FIELD_NUMBER:
                    builder.setTotalUniqueGenomes(input.readInt64());
                    break;
                case TickData.ALL_GENOME_HASHES_EVER_SEEN_FIELD_NUMBER: {
                    // Handle both packed (LENGTH_DELIMITED) and unpacked (VARINT) encoding
                    if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                        int length = input.readRawVarint32();
                        int oldLimit = input.pushLimit(length);
                        while (input.getBytesUntilLimit() > 0) {
                            builder.addAllGenomeHashesEverSeen(input.readInt64());
                        }
                        input.popLimit(oldLimit);
                    } else {
                        builder.addAllGenomeHashesEverSeen(input.readInt64());
                    }
                    break;
                }
                default:
                    input.skipField(tag);
                    break;
            }
        }

        return builder.build();
    }

    /**
     * Parses a TickDelta message from a CodedInputStream, skipping fields based on the filter.
     * <p>
     * Field mapping for TickDelta (note: field numbers differ from TickData):
     * <ul>
     *   <li>{@code SKIP_ORGANISMS}: skips field 5 (organisms)</li>
     *   <li>{@code SKIP_CELLS}: skips field 4 (changed_cells)</li>
     * </ul>
     *
     * @param input  CodedInputStream positioned at the start of a TickDelta message
     * @param filter Controls which fields to skip
     * @return Parsed TickDelta with filtered fields omitted
     * @throws IOException if parsing fails
     */
    private static TickDelta parseTickDeltaWithFilter(CodedInputStream input, ChunkFieldFilter filter) throws IOException {
        TickDelta.Builder builder = TickDelta.newBuilder();

        while (true) {
            int tag = input.readTag();
            if (tag == 0) break;

            int fieldNumber = WireFormat.getTagFieldNumber(tag);

            // Skip organisms when filter is SKIP_ORGANISMS
            if (filter == ChunkFieldFilter.SKIP_ORGANISMS && fieldNumber == TickDelta.ORGANISMS_FIELD_NUMBER) {
                input.skipField(tag);
                continue;
            }
            // Skip changed_cells when filter is SKIP_CELLS
            if (filter == ChunkFieldFilter.SKIP_CELLS && fieldNumber == TickDelta.CHANGED_CELLS_FIELD_NUMBER) {
                input.skipField(tag);
                continue;
            }

            switch (fieldNumber) {
                case TickDelta.TICK_NUMBER_FIELD_NUMBER:
                    builder.setTickNumber(input.readInt64());
                    break;
                case TickDelta.CAPTURE_TIME_MS_FIELD_NUMBER:
                    builder.setCaptureTimeMs(input.readInt64());
                    break;
                case TickDelta.DELTA_TYPE_FIELD_NUMBER:
                    builder.setDeltaTypeValue(input.readEnum());
                    break;
                case TickDelta.CHANGED_CELLS_FIELD_NUMBER: {
                    int length = input.readRawVarint32();
                    int oldLimit = input.pushLimit(length);
                    builder.setChangedCells(CellDataColumns.parseFrom(input));
                    input.popLimit(oldLimit);
                    break;
                }
                case TickDelta.ORGANISMS_FIELD_NUMBER: {
                    int length = input.readRawVarint32();
                    int oldLimit = input.pushLimit(length);
                    builder.addOrganisms(OrganismState.parseFrom(input));
                    input.popLimit(oldLimit);
                    break;
                }
                case TickDelta.TOTAL_ORGANISMS_CREATED_FIELD_NUMBER:
                    builder.setTotalOrganismsCreated(input.readInt64());
                    break;
                case TickDelta.RNG_STATE_FIELD_NUMBER:
                    builder.setRngState(input.readBytes());
                    break;
                case TickDelta.PLUGIN_STATES_FIELD_NUMBER: {
                    int length = input.readRawVarint32();
                    int oldLimit = input.pushLimit(length);
                    builder.addPluginStates(PluginState.parseFrom(input));
                    input.popLimit(oldLimit);
                    break;
                }
                case TickDelta.TOTAL_UNIQUE_GENOMES_FIELD_NUMBER:
                    builder.setTotalUniqueGenomes(input.readInt64());
                    break;
                default:
                    input.skipField(tag);
                    break;
            }
        }

        return builder.build();
    }

    @Override
    public <T extends MessageLite> StoragePath writeMessage(String key, T message) throws IOException {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key cannot be null or empty");
        }
        if (message == null) {
            throw new IllegalArgumentException("message cannot be null");
        }

        // Serialize single delimited protobuf message
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        message.writeDelimitedTo(bos);
        byte[] data = bos.toByteArray();

        // Convert to physical path (adds compression extension)
        String physicalPath = toPhysicalPath(key);

        // Compress message
        byte[] compressed = compressBatch(data);

        // Write directly to physical path (atomic write with temp file)
        long writeStart = System.nanoTime();
        putRaw(physicalPath, compressed);
        long writeLatency = System.nanoTime() - writeStart;

        // Record metrics
        recordWrite(compressed.length, writeLatency);

        return StoragePath.of(physicalPath);
    }

    @Override
    public <T extends MessageLite> T readMessage(StoragePath path, Parser<T> parser) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path cannot be null");
        }
        if (parser == null) {
            throw new IllegalArgumentException("parser cannot be null");
        }

        // Read compressed bytes
        long readStart = System.nanoTime();
        byte[] compressed = getRaw(path.asString());
        long readLatency = System.nanoTime() - readStart;

        // Detect compression codec from file extension
        ICompressionCodec detectedCodec = org.evochora.datapipeline.utils.compression.CompressionCodecFactory.detectFromExtension(path.asString());

        // Stream directly: decompress → parse in one pass (no intermediate byte array!)
        T message;
        try (InputStream decompressedStream = detectedCodec.wrapInputStream(new ByteArrayInputStream(compressed))) {
            message = parser.parseDelimitedFrom(decompressedStream);

            if (message == null) {
                throw new IOException("File is empty: " + path.asString());
            }

            // Verify exactly one message
            if (decompressedStream.available() > 0) {
                T secondMessage = parser.parseDelimitedFrom(decompressedStream);
                if (secondMessage != null) {
                    throw new IOException("File contains multiple messages: " + path.asString());
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to decompress and parse message: " + path.asString(), e);
        }

        // Record metrics
        recordRead(compressed.length, readLatency);

        return message;
    }

    /**
     * Parses the start tick from a batch filename.
     * <p>
     * Batch filenames follow the pattern: batch_STARTICK_ENDTICK.pb[.compression]
     * Example: "batch_0000000000_0000000999.pb.zst" → startTick = 0
     * <p>
     * This method is protected so subclasses can use it for tick-based filtering
     * in their {@link #listRaw} implementations.
     *
     * @param filename The batch filename (with or without path, with or without compression extension)
     * @return The start tick, or -1 if filename doesn't match the batch pattern
     */
    protected long parseBatchStartTick(String filename) {
        // Extract just the filename if a path is provided
        String name = filename.substring(filename.lastIndexOf('/') + 1);
        
        // Check if it matches the batch pattern
        if (!name.startsWith("batch_") || !name.contains(".pb")) {
            return -1;
        }
        
        try {
            // Extract the part between "batch_" and the first underscore after it
            // Pattern: batch_0000000000_0000000999.pb...
            int startIdx = 6; // Length of "batch_"
            int endIdx = name.indexOf('_', startIdx);
            if (endIdx == -1) {
                return -1;
            }
            
            String tickStr = name.substring(startIdx, endIdx);
            return Long.parseLong(tickStr);
        } catch (NumberFormatException e) {
            log.trace("Failed to parse start tick from filename: {}", filename, e);
            return -1;
        }
    }

    /**
     * Parses the end tick from a batch filename.
     * <p>
     * Batch filenames follow the pattern: batch_STARTICK_ENDTICK.pb[.compression]
     * Example: "batch_0000000000_0000000999.pb.zst" → endTick = 999
     * <p>
     * This method is protected so subclasses can use it for tick-based filtering
     * in their {@link #listRaw} implementations.
     *
     * @param filename The batch filename (with or without path, with or without compression extension)
     * @return The end tick, or -1 if filename doesn't match the batch pattern
     */
    protected long parseBatchEndTick(String filename) {
        // Extract just the filename if a path is provided
        String name = filename.substring(filename.lastIndexOf('/') + 1);
        
        // Check if it matches the batch pattern
        if (!name.startsWith("batch_") || !name.contains(".pb")) {
            return -1;
        }
        
        try {
            // Extract the part between the second underscore and ".pb"
            // Pattern: batch_0000000000_0000000999.pb...
            int startIdx = 6; // Length of "batch_"
            int firstUnderscore = name.indexOf('_', startIdx);
            if (firstUnderscore == -1) {
                return -1;
            }
            
            int secondUnderscore = firstUnderscore + 1;
            int dotPbIdx = name.indexOf(".pb");
            if (dotPbIdx == -1) {
                return -1;
            }
            
            String tickStr = name.substring(secondUnderscore, dotPbIdx);
            return Long.parseLong(tickStr);
        } catch (NumberFormatException e) {
            log.trace("Failed to parse end tick from filename: {}", filename, e);
            return -1;
        }
    }

    /**
     * Calculates folder path for a given tick using configured folder levels.
     * <p>
     * Example with levels=[100000000, 100000]:
     * <ul>
     *   <li>Tick 123,456,789 → "001/234/"</li>
     *   <li>Tick 5,000,000,000 → "050/000/"</li>
     * </ul>
     */
    private String calculateFolderPath(long tick) {
        StringBuilder path = new StringBuilder();
        long remaining = tick;

        for (int i = 0; i < folderLevels.size(); i++) {
            long divisor = folderLevels.get(i);
            long bucket = remaining / divisor;

            // Format with 3 digits (supports up to 999 per level)
            path.append(String.format("%03d", bucket));

            if (i < folderLevels.size() - 1) {
                path.append("/");
            }

            remaining %= divisor;
        }

        return path.toString();
    }

    /**
     * Compresses data using the configured compression codec.
     * <p>
     * This method is implemented generically in the abstract class to avoid
     * code duplication across storage backends. NoneCodec is treated like any
     * other codec - it simply returns the stream unchanged.
     *
     * @param data uncompressed data
     * @return compressed data (or original data if NoneCodec is used)
     * @throws IOException if compression fails
     */
    private byte[] compressBatch(byte[] data) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (OutputStream compressedStream = codec.wrapOutputStream(bos)) {
                compressedStream.write(data);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IOException("Failed to compress batch", e);
        }
    }


    @Override
    public BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults,
                                               long startTick, long endTick,
                                               IBatchStorageRead.SortOrder sortOrder) throws IOException {
        if (sortOrder == null) {
            throw new IllegalArgumentException("sortOrder cannot be null");
        }
        if (startTick < 0) {
            throw new IllegalArgumentException("startTick must be >= 0");
        }
        if (endTick < startTick) {
            throw new IllegalArgumentException("endTick must be >= startTick");
        }
        Long startTickNullable = (startTick == 0) ? null : startTick;
        Long endTickNullable = (endTick == Long.MAX_VALUE) ? null : endTick;
        return listBatchFilesInternal(prefix, continuationToken, maxResults, startTickNullable, endTickNullable, sortOrder);
    }

    /**
     * Internal implementation for listing batch files with optional tick filtering and sort order.
     * <p>
     * This method delegates to {@link #listRaw} with nullable tick parameters and performs
     * the common logic of filtering to batch files, deduplication, and wrapping as StoragePath.
     * <p>
     * <strong>Deduplication:</strong> If multiple batch files have the same firstTick (which can
     * happen after a crash during write), only the file with the smallest lastTick is kept.
     * This ensures we use the complete file written before the crash, not a potentially partial
     * file that was being written when the crash occurred.
     * <p>
     * <strong>Sort Order:</strong> Results can be sorted in ascending (oldest first) or descending
     * (newest first) order. Use {@link IBatchStorageRead.SortOrder#DESCENDING} with maxResults=1
     * to efficiently get only the last batch file for resume operations.
     *
     * @param prefix Filter prefix (e.g., "sim123/" for specific simulation)
     * @param continuationToken Token from previous call, or null for first page
     * @param maxResults Maximum files to return per page
     * @param startTick Minimum start tick (nullable - null means no lower bound)
     * @param endTick Maximum start tick (nullable - null means no upper bound)
     * @param sortOrder Sort order for results (ASCENDING or DESCENDING)
     * @return Paginated result with matching batch physical paths
     * @throws IOException If storage access fails
     */
    private BatchFileListResult listBatchFilesInternal(String prefix, String continuationToken, int maxResults,
                                                        Long startTick, Long endTick,
                                                        IBatchStorageRead.SortOrder sortOrder) throws IOException {
        if (prefix == null) {
            throw new IllegalArgumentException("prefix cannot be null");
        }
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be > 0");
        }

        // Delegate to subclass to get all files with prefix
        // listRaw returns PHYSICAL paths (with compression extensions)
        List<String> allPhysicalFiles = listRaw(prefix, false, continuationToken, (maxResults + 1) * 2, startTick, endTick);

        // Filter to batch files and sort lexicographically (ascending tick order)
        List<String> batchFilePaths = allPhysicalFiles.stream()
            .filter(path -> {
                String filename = path.substring(path.lastIndexOf('/') + 1);
                return filename.startsWith("batch_") && filename.contains(".pb");
            })
            .sorted()  // Lexicographic order = tick order (ascending)
            .collect(Collectors.toCollection(ArrayList::new));

        // Deduplicate: group by firstTick, keep file with smallest lastTick
        Map<Long, String> deduplicatedByFirstTick = new java.util.LinkedHashMap<>();
        for (String path : batchFilePaths) {
            long firstTick = parseBatchStartTick(path);
            long lastTick = parseBatchEndTick(path);

            if (firstTick < 0 || lastTick < 0) {
                // Failed to parse, include it anyway
                deduplicatedByFirstTick.putIfAbsent(System.nanoTime(), path);  // Use unique key
                continue;
            }

            String existing = deduplicatedByFirstTick.get(firstTick);
            if (existing == null) {
                deduplicatedByFirstTick.put(firstTick, path);
            } else {
                // Duplicate found - keep the one with smaller lastTick
                long existingLastTick = parseBatchEndTick(existing);
                if (lastTick < existingLastTick) {
                    // New file has smaller lastTick, replace
                    log.warn("Duplicate batch files for firstTick {}: keeping {} (lastTick={}) over {} (lastTick={})",
                            firstTick, path, lastTick, existing, existingLastTick);
                    deduplicatedByFirstTick.put(firstTick, path);
                } else {
                    // Existing file has smaller or equal lastTick, keep it
                    log.warn("Duplicate batch files for firstTick {}: keeping {} (lastTick={}) over {} (lastTick={})",
                            firstTick, existing, existingLastTick, path, lastTick);
                }
            }
        }

        // deduplicatedByFirstTick is already in ascending order from LinkedHashMap
        List<String> sortedPaths = new ArrayList<>(deduplicatedByFirstTick.values());

        if (sortOrder == IBatchStorageRead.SortOrder.DESCENDING) {
            Collections.reverse(sortedPaths);
        }

        // Convert to StoragePath list with limit
        List<StoragePath> batchFiles = sortedPaths.stream()
            .limit(maxResults + 1)  // +1 to detect truncation
            .map(StoragePath::of)
            .toList();

        // Check if truncated
        boolean truncated = batchFiles.size() > maxResults;
        List<StoragePath> resultFiles = truncated ? batchFiles.subList(0, maxResults) : batchFiles;
        String nextToken = truncated ? resultFiles.get(resultFiles.size() - 1).asString() : null;

        return new BatchFileListResult(resultFiles, nextToken, truncated);
    }

    @Override
    public java.util.Optional<StoragePath> findMetadataPath(String runId) throws IOException {
        if (runId == null) {
            throw new IllegalArgumentException("runId cannot be null");
        }

        // Search for metadata file using listRaw with prefix pattern
        // This finds both uncompressed (metadata.pb) and compressed variants (metadata.pb.zst, etc.)
        // The prefix "runId/raw/metadata.pb" will match files starting with this pattern
        String metadataPrefix = runId + "/raw/metadata.pb";
        List<String> files = listRaw(metadataPrefix, false, null, 1, null, null);

        // Return first matching file, if any
        if (files.isEmpty()) {
            return java.util.Optional.empty();
        }

        // listRaw returns physical paths with compression extensions
        return java.util.Optional.of(StoragePath.of(files.get(0)));
    }

    // ===== IResource implementation =====

    /**
     * Returns the current operational state for the specified usage type.
     * <p>
     * All batch storage implementations return ACTIVE for "storage-read" and "storage-write" usage types,
     * as batch storage is stateless and always available.
     * <p>
     * This method is final to ensure consistent behavior across all storage implementations.
     *
     * @param usageType The usage type (must be "storage-read" or "storage-write")
     * @return UsageState.ACTIVE for valid usage types
     * @throws IllegalArgumentException if usageType is null or not recognized
     */
    @Override
    public final UsageState getUsageState(String usageType) {
        if (usageType == null) {
            throw new IllegalArgumentException("Storage requires non-null usageType");
        }

        return switch (usageType) {
            case "storage-read", "storage-write" -> UsageState.ACTIVE;
            default -> throw new IllegalArgumentException("Unknown usageType: " + usageType);
        };
    }

    /**
     * Adds storage-specific metrics to the provided map.
     * <p>
     * This override adds counters and performance metrics tracked by all storage resources.
     * Subclasses should call {@code super.addCustomMetrics(metrics)} to include these.
     * <p>
     * Added metrics:
     * <ul>
     *   <li>write_operations - cumulative write count</li>
     *   <li>read_operations - cumulative read count</li>
     *   <li>bytes_written - cumulative bytes written</li>
     *   <li>bytes_read - cumulative bytes read</li>
     *   <li>write_errors - cumulative write errors</li>
     *   <li>read_errors - cumulative read errors</li>
     *   <li>writes_per_sec - sliding window write rate (O(1))</li>
     *   <li>reads_per_sec - sliding window read rate (O(1))</li>
     *   <li>write_bytes_per_sec - sliding window write throughput (O(1))</li>
     *   <li>read_bytes_per_sec - sliding window read throughput (O(1))</li>
     *   <li>write_latency_ms - sliding window average write latency (O(1))</li>
     *   <li>read_latency_ms - sliding window average read latency (O(1))</li>
     *   <li>last_read_batch_size_mb - most recent batch read size in MB (O(1))</li>
     *   <li>max_read_batch_size_mb - maximum observed batch read size in MB (O(1))</li>
     * </ul>
     *
     * @param metrics Mutable map to add metrics to (already contains base error_count from AbstractResource)
     */
    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);  // Include parent metrics

        // Cumulative metrics
        metrics.put("write_operations", writeOperations.get());
        metrics.put("read_operations", readOperations.get());
        metrics.put("bytes_written", bytesWritten.get());
        metrics.put("bytes_read", bytesRead.get());
        metrics.put("write_errors", writeErrors.get());
        metrics.put("read_errors", readErrors.get());

        // Performance metrics (sliding window using unified utils - O(1))
        metrics.put("writes_per_sec", writeOpsCounter.getRate());
        metrics.put("reads_per_sec", readOpsCounter.getRate());
        metrics.put("write_bytes_per_sec", writeBytesCounter.getRate());
        metrics.put("read_bytes_per_sec", readBytesCounter.getRate());
        metrics.put("write_latency_ms", writeLatencyTracker.getAverage() / 1_000_000.0);
        metrics.put("read_latency_ms", readLatencyTracker.getAverage() / 1_000_000.0);

        // Batch size tracking metrics (O(1) operations, helps diagnose memory issues)
        metrics.put("last_read_batch_size_mb", lastReadBatchSizeMB.get());
        metrics.put("max_read_batch_size_mb", maxReadBatchSizeMB.get());
    }

    /**
     * Clears operational errors and resets error counters.
     * <p>
     * Extends AbstractResource's clearErrors() to also reset storage-specific error counters.
     */
    @Override
    public void clearErrors() {
        super.clearErrors();  // Clear errors collection in AbstractResource
        writeErrors.set(0);
        readErrors.set(0);
    }

    // ===== IContextualResource implementation =====

    /**
     * Returns a contextual wrapper for this storage resource based on usage type.
     * <p>
     * All batch storage implementations support the same usage types and use the same
     * monitoring wrappers, as the wrappers operate on the standard {@link IBatchStorageRead}
     * and {@link IBatchStorageWrite} interfaces.
     * <p>
     * Supported usage types:
     * <ul>
     *   <li>storage-write - Returns a {@link MonitoredBatchStorageWriter} that tracks write metrics</li>
     *   <li>storage-read - Returns a {@link MonitoredBatchStorageReader} that tracks read metrics</li>
     *   <li>storage-readwrite - Returns a {@link MonitoredBatchStorageReadWriter} for services needing both
     *       read and write access (e.g., SimulationEngine in resume mode)</li>
     *   <li>analytics-write - Returns a {@link MonitoredAnalyticsStorageWriter} for analytics data</li>
     * </ul>
     *
     * @param context The resource context containing usage type and service information
     * @return The wrapped resource with monitoring capabilities
     * @throws IllegalArgumentException if usageType is null or not supported
     */
    @Override
    public final IWrappedResource getWrappedResource(ResourceContext context) {
        if (context.usageType() == null) {
            throw new IllegalArgumentException(String.format(
                "Storage resource '%s' requires a usageType in the binding URI. " +
                "Expected format: 'usageType:%s' where usageType is one of: " +
                "storage-write, storage-read",
                getResourceName(), getResourceName()
            ));
        }

        return switch (context.usageType()) {
            case "storage-write" -> new MonitoredBatchStorageWriter(this, context);
            case "storage-read" -> new MonitoredBatchStorageReader(this, context);
            case "analytics-write" -> new MonitoredAnalyticsStorageWriter(this, context);
            default -> throw new IllegalArgumentException(String.format(
                "Unsupported usage type '%s' for storage resource '%s'. " +
                "Supported types: storage-write, storage-read, analytics-write",
                context.usageType(), getResourceName()
            ));
        };
    }

    // ===== Abstract I/O primitives (subclasses implement) =====

    // ===== Resolution & High-Level Operations (non-abstract) =====

    /**
     * Converts logical key to physical path for writing.
     * Adds compression extension based on configured codec.
     */
    private String toPhysicalPath(String logicalKey) {
        return logicalKey + codec.getFileExtension();
    }

    // ===== Abstract I/O primitives (subclasses implement) =====

    /**
     * Writes raw bytes to physical path.
     * <p>
     * Implementation must:
     * <ul>
     *   <li>Create parent directories if needed</li>
     *   <li>Ensure atomic writes (temp-then-move pattern or backend-native atomics)</li>
     *   <li>Only perform I/O - metrics are tracked by caller (put() method)</li>
     * </ul>
     * <p>
     * Example implementations:
     * <ul>
     *   <li>FileSystem: temp file + Files.move(ATOMIC_MOVE)</li>
     *   <li>S3: Direct putObject (inherently atomic)</li>
     * </ul>
     *
     * @param physicalPath physical path including compression extension
     * @param data raw bytes (already compressed by caller)
     * @throws IOException if write fails
     */
    protected abstract void putRaw(String physicalPath, byte[] data) throws IOException;

    /**
     * Writes chunks to a temporary file in the given folder.
     * <p>
     * Implementations must:
     * <ul>
     *   <li>Create the folder if it does not exist</li>
     *   <li>Write each chunk using {@code chunk.writeDelimitedTo(compressedStream)}</li>
     *   <li>Clean up the temp file on failure</li>
     * </ul>
     *
     * @param folderPath relative folder path within the storage root
     * @param chunks     iterable of chunks to write
     * @param codec      compression codec to wrap the output stream
     * @return result containing the temp file handle and bytes written
     * @throws IOException if the write fails
     */
    protected abstract TempWriteResult writeChunksToTempFile(
        String folderPath, Iterable<TickDataChunk> chunks, ICompressionCodec codec) throws IOException;

    /**
     * Atomically moves a temp file to its final path.
     * <p>
     * Implementations must ensure the move is atomic (e.g., {@code Files.move(ATOMIC_MOVE)})
     * so that concurrent readers never see partial data.
     *
     * @param tempHandle        backend-specific handle to the temp file
     * @param finalPhysicalPath relative physical path within the storage root
     * @throws IOException if the move fails
     */
    protected abstract void finalizeStreamingWrite(String tempHandle, String finalPhysicalPath) throws IOException;

    /**
     * Opens a streaming read handle for the raw (still compressed) bytes at the given path.
     * <p>
     * This is the read-side counterpart to the streaming write methods
     * ({@link #writeChunksToTempFile}, {@link #finalizeStreamingWrite}).
     * <p>
     * Implementation must:
     * <ul>
     *   <li>Throw IOException if file doesn't exist</li>
     *   <li>Return a buffered InputStream (callers should not need to wrap)</li>
     *   <li>Only perform I/O - metrics are tracked by caller</li>
     * </ul>
     * <p>
     * S3 mapping: {@code GetObjectRequest} returning the response input stream.
     *
     * @param physicalPath physical path including compression extension
     * @return InputStream of raw compressed bytes (caller closes)
     * @throws IOException if file not found or read fails
     */
    protected abstract InputStream openRawStream(String physicalPath) throws IOException;

    /**
     * Reads all raw bytes from the given physical path into memory.
     * <p>
     * Delegates to {@link #openRawStream(String)} and reads the full content.
     * Used by methods that need the complete byte array (e.g., {@link #readMessage},
     * {@link #readLastSnapshot}). For streaming chunk processing, prefer
     * {@link #openRawStream(String)} directly.
     *
     * @param physicalPath physical path including compression extension
     * @return raw bytes (still compressed, caller handles decompression)
     * @throws IOException if file not found or read fails
     */
    protected byte[] getRaw(String physicalPath) throws IOException {
        try (InputStream stream = openRawStream(physicalPath)) {
            return stream.readAllBytes();
        }
    }

    /**
     * Lists physical paths or directory prefixes matching a prefix, with optional tick filtering.
     * <p>
     * This method works for three use cases:
     * <ul>
     *   <li>Finding file variants: listRaw("runId/metadata.pb", false, null, 1, null, null)</li>
     *   <li>Listing batches: listRaw("runId/", false, token, 1000, null, null)</li>
     *   <li>Listing batches from tick: listRaw("runId/", false, token, 1000, 5000L, null)</li>
     *   <li>Listing batches in range: listRaw("runId/", false, token, 1000, 1000L, 5000L)</li>
     *   <li>Listing run IDs: listRaw("", true, null, 1000, null, null)</li>
     * </ul>
     * <p>
     * Performance: O(1) for directories or single file, O(n) for recursive file listing.
     * <p>
     * Implementation must:
     * <ul>
     *   <li>If listDirectories=false: Recursively scan for files (Files.walk)</li>
     *   <li>If listDirectories=true: List immediate subdirectory prefixes (Files.list)</li>
     *   <li>Return physical paths with compression extensions (files) or directory prefixes ending with "/"</li>
     *   <li>Filter out .tmp files to avoid race conditions</li>
     *   <li>Apply tick filtering if startTick/endTick are non-null (only relevant for batch files)</li>
     *   <li>Support pagination via continuationToken</li>
     *   <li>Enforce maxResults limit (prevent runaway queries)</li>
     *   <li>Return results in lexicographic order</li>
     * </ul>
     * <p>
     * S3 mapping: ListObjectsV2 with prefix, delimiter="/", maxKeys, continuationToken
     * <ul>
     *   <li>listDirectories=true → delimiter="/" (returns CommonPrefixes)</li>
     *   <li>listDirectories=false → delimiter=null (returns Contents)</li>
     * </ul>
     *
     * @param prefix prefix to match (e.g., "runId/metadata.pb", "runId/", or "")
     * @param listDirectories if true, return directory prefixes; if false, return files
     * @param continuationToken pagination token from previous call, or null
     * @param maxResults hard limit on results
     * @param startTick minimum batch start tick (nullable, ignored if listDirectories=true or null)
     * @param endTick maximum batch start tick (nullable, ignored if listDirectories=true or null)
     * @return physical paths (files) or directory prefixes, max maxResults items
     * @throws IOException if storage access fails
     */
    protected abstract List<String> listRaw(String prefix, boolean listDirectories, String continuationToken, 
                                             int maxResults, Long startTick, Long endTick) throws IOException;

    @Override
    public List<String> listRunIds(Instant afterTimestamp) throws IOException {
        // List all run directories (first level directories in storage root)
        // Pass null for startTick/endTick as they are not relevant for directory listing
        List<String> runDirs = listRaw("", true, null, 10000, null, null);
        
        // Parse timestamps and filter
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSS");
        return runDirs.stream()
            .map(dir -> dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir)  // Strip trailing "/"
            .filter(runId -> {
                if (runId.length() < 17) return false;
                try {
                    String timestampStr = runId.substring(0, 17);
                    LocalDateTime ldt = LocalDateTime.parse(timestampStr, formatter);
                    Instant runIdInstant = ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
                    return runIdInstant.isAfter(afterTimestamp);
                } catch (DateTimeParseException e) {
                    log.trace("Ignoring non-runId directory: {}", runId);
                    return false;
                }
            })
            .sorted()
            .toList();
    }

    // ===== Performance tracking helpers =====

    /**
     * Records a write operation for performance tracking.
     * This is an O(1) operation using unified monitoring utils.
     * Updates both legacy counters (writeOperations) and new sliding window metrics.
     *
     * @param bytes Number of bytes written
     * @param latencyNanos Write latency in nanoseconds
     */
    private void recordWrite(long bytes, long latencyNanos) {
        // Legacy counters (for backward compatibility)
        writeOperations.incrementAndGet();
        bytesWritten.addAndGet(bytes);
        
        // New sliding window metrics
        writeOpsCounter.recordCount();
        writeBytesCounter.recordSum(bytes);
        writeLatencyTracker.record(latencyNanos);
    }

    /**
     * Records a read operation for performance tracking.
     * This is an O(1) operation using unified monitoring utils.
     * Updates both legacy counters (readOperations) and new sliding window metrics.
     *
     * @param bytes Number of bytes read
     * @param latencyNanos Read latency in nanoseconds
     */
    private void recordRead(long bytes, long latencyNanos) {
        // Legacy counters (for backward compatibility)
        readOperations.incrementAndGet();
        bytesRead.addAndGet(bytes);
        
        // New sliding window metrics
        readOpsCounter.recordCount();
        readBytesCounter.recordSum(bytes);
        readLatencyTracker.record(latencyNanos);
    }

    // ===== Helper classes =====

    /**
     * An input stream wrapper that counts compressed bytes read.
     * <p>
     * Used for metrics tracking during streaming reads without requiring
     * the compressed data to be buffered in memory.
     */
    private static class CountingInputStream extends FilterInputStream {
        private long bytesRead;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) bytesRead++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) bytesRead += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) bytesRead += skipped;
            return skipped;
        }

        long getBytesRead() {
            return bytesRead;
        }
    }

    /**
     * An output stream wrapper that counts bytes written.
     * <p>
     * Used for metrics tracking during streaming writes without requiring
     * the compressed data to be buffered in memory. Subclasses should use
     * this in their {@link #writeAtomicStreaming} implementations.
     * <p>
     * <strong>Usage Example:</strong>
     * <pre>
     * try (OutputStream fileOut = createFileStream(path);
     *      CountingOutputStream counting = new CountingOutputStream(fileOut);
     *      OutputStream compressed = codec.wrapOutputStream(counting)) {
     *     for (TickData tick : batch) {
     *         tick.writeDelimitedTo(compressed);
     *     }
     * }
     * return counting.getBytesWritten();
     * </pre>
     */
    protected static class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long bytesWritten = 0;

        /**
         * Creates a counting wrapper around the given output stream.
         *
         * @param delegate the underlying output stream to write to
         */
        public CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            bytesWritten++;
        }

        @Override
        public void write(byte[] b) throws IOException {
            delegate.write(b);
            bytesWritten += b.length;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            bytesWritten += len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        /**
         * Returns the total number of bytes written through this stream.
         *
         * @return bytes written count
         */
        public long getBytesWritten() {
            return bytesWritten;
        }
    }

    /**
     * An iterable that wraps a peeked first chunk and remaining iterator,
     * tracking the last tick and chunk count during iteration.
     * <p>
     * Used by {@link #writeChunkBatchStreaming} to derive metadata (lastTick, chunkCount)
     * during the streaming write without requiring a second pass.
     */
    private static class TrackingIterable implements Iterable<TickDataChunk> {
        private final TickDataChunk firstChunk;
        private final Iterator<TickDataChunk> remaining;
        private final String expectedSimulationRunId;
        private long lastTick;
        private int chunkCount;
        private int totalTickCount;

        TrackingIterable(TickDataChunk firstChunk, Iterator<TickDataChunk> remaining) {
            this.firstChunk = firstChunk;
            this.remaining = remaining;
            this.expectedSimulationRunId = firstChunk.getSimulationRunId();
            this.lastTick = firstChunk.getLastTick();
            this.chunkCount = 0;
            this.totalTickCount = 0;
        }

        @Override
        public Iterator<TickDataChunk> iterator() {
            return new Iterator<>() {
                private boolean firstReturned = false;

                @Override
                public boolean hasNext() {
                    return !firstReturned || remaining.hasNext();
                }

                @Override
                public TickDataChunk next() {
                    TickDataChunk chunk;
                    if (!firstReturned) {
                        chunk = firstChunk;
                        firstReturned = true;
                    } else {
                        chunk = remaining.next();
                        if (!expectedSimulationRunId.equals(chunk.getSimulationRunId())) {
                            throw new IllegalStateException(String.format(
                                "simulationRunId mismatch in batch: expected '%s' (from first chunk) but chunk at tick %d has '%s'",
                                expectedSimulationRunId, chunk.getFirstTick(), chunk.getSimulationRunId()));
                        }
                    }
                    if (chunkCount > 0 && chunk.getFirstTick() < lastTick) {
                        throw new IllegalStateException(String.format(
                            "chunks not in ascending tick order: previous lastTick=%d but chunk has firstTick=%d",
                            lastTick, chunk.getFirstTick()));
                    }
                    lastTick = chunk.getLastTick();
                    chunkCount++;
                    totalTickCount += chunk.getTickCount();
                    return chunk;
                }
            };
        }

        /**
         * Returns the last tick seen during iteration.
         *
         * @return the last tick from the most recently iterated chunk
         */
        long getLastTick() {
            return lastTick;
        }

        /**
         * Returns the number of chunks iterated so far.
         *
         * @return chunk count
         */
        int getChunkCount() {
            return chunkCount;
        }

        /**
         * Returns the sum of tick counts across all iterated chunks.
         *
         * @return total tick count
         */
        int getTotalTickCount() {
            return totalTickCount;
        }
    }

    /**
     * Result of writing chunks to a temporary file.
     *
     * @param tempHandle   backend-specific handle to the temp file (e.g., absolute file path)
     * @param bytesWritten number of compressed bytes written
     */
    protected record TempWriteResult(String tempHandle, long bytesWritten) {}

}
