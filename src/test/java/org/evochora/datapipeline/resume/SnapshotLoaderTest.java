package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.storage.CheckedConsumer;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link SnapshotLoader}.
 * <p>
 * Tests the simplified snapshot-only resume logic. Since resume always happens
 * from a snapshot (chunk start), there is no truncation or accumulated delta handling.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
@ExtendWith(MockitoExtension.class)
class SnapshotLoaderTest {

    private static final String TEST_RUN_ID = "20250127-123456-test-run";

    @Mock
    private IBatchStorageRead storageRead;
    private SnapshotLoader loader;

    /**
     * Warm up Mockito and Protobuf classes to avoid cold-start penalty in individual tests.
     */
    @BeforeAll
    static void warmUp() {
        // Warm up Mockito
        var warmupMock = Mockito.mock(IBatchStorageRead.class);
        Mockito.reset(warmupMock);

        // Warm up Protobuf classes
        SimulationMetadata.newBuilder().build();
        TickDataChunk.newBuilder().build();
        TickData.newBuilder().build();
        CellDataColumns.newBuilder().build();
    }

    @BeforeEach
    void setUp() {
        loader = new SnapshotLoader(storageRead);
    }

    // ==================== Happy Path Tests ====================

    @Test
    void loadLatestCheckpoint_ReturnsSnapshotFromLastChunk() throws Exception {
        // Setup: Metadata exists
        StoragePath metadataPath = StoragePath.of(TEST_RUN_ID + "/raw/metadata.pb");
        SimulationMetadata metadata = createMetadata(TEST_RUN_ID);
        when(storageRead.findMetadataPath(TEST_RUN_ID)).thenReturn(Optional.of(metadataPath));
        when(storageRead.readMessage(eq(metadataPath), any())).thenReturn(metadata);

        // Setup: One batch file
        StoragePath batchPath = StoragePath.of(TEST_RUN_ID + "/raw/000/000/batch_0000000000000001000_0000000000000001099.pb");
        when(storageRead.findLastBatchFile(TEST_RUN_ID + "/raw/")).thenReturn(Optional.of(batchPath));

        TickData snapshot = createSnapshot(1000);
        stubSnapshotRead(batchPath, snapshot);

        // Execute
        ResumeCheckpoint checkpoint = loader.loadLatestCheckpoint(TEST_RUN_ID);

        // Verify snapshot is returned
        assertThat(checkpoint.snapshot().getTickNumber()).isEqualTo(1000);
        assertThat(checkpoint.getResumeFromTick()).isEqualTo(1001);
        assertThat(checkpoint.getCheckpointTick()).isEqualTo(1000);
    }

    @Test
    void loadLatestCheckpoint_MultipleChunks_ReturnsSnapshotFromLastChunk() throws Exception {
        // Setup: Metadata exists
        StoragePath metadataPath = StoragePath.of(TEST_RUN_ID + "/raw/metadata.pb");
        SimulationMetadata metadata = createMetadata(TEST_RUN_ID);
        when(storageRead.findMetadataPath(TEST_RUN_ID)).thenReturn(Optional.of(metadataPath));
        when(storageRead.readMessage(eq(metadataPath), any())).thenReturn(metadata);

        // Setup: Batch with multiple chunks
        StoragePath batchPath = StoragePath.of(TEST_RUN_ID + "/raw/000/000/batch.pb");
        when(storageRead.findLastBatchFile(TEST_RUN_ID + "/raw/")).thenReturn(Optional.of(batchPath));

        // forEachChunk with SNAPSHOT_ONLY invokes consumer for each chunk; last one wins
        TickData lastSnapshot = createSnapshot(1200);
        stubSnapshotRead(batchPath, lastSnapshot);

        // Execute
        ResumeCheckpoint checkpoint = loader.loadLatestCheckpoint(TEST_RUN_ID);

        // Verify snapshot from LAST chunk is returned
        assertThat(checkpoint.snapshot().getTickNumber()).isEqualTo(1200);
        assertThat(checkpoint.getResumeFromTick()).isEqualTo(1201);
    }

    // ==================== Checkpoint For A Chosen Tick ====================

    @Test
    void loadCheckpointContaining_TickInFirstChunk_ReturnsItsSnapshotAndStopsThere() throws Exception {
        stubMetadata();

        StoragePath batchPath = StoragePath.of(TEST_RUN_ID + "/raw/000/000/batch_0000000000000001000_0000000000000001199.pb");
        when(storageRead.findBatchFileContaining(TEST_RUN_ID + "/raw/", 1050L)).thenReturn(Optional.of(batchPath));
        AtomicInteger chunksRead = stubChunkRead(batchPath,
            chunk(1000, 1099), chunk(1100, 1199));

        ResumeCheckpoint checkpoint = loader.loadCheckpointContaining(TEST_RUN_ID, 1050);

        assertThat(checkpoint.getCheckpointTick()).isEqualTo(1000);
        assertThat(chunksRead).hasValue(1);
    }

    @Test
    void loadCheckpointContaining_TickInLaterChunk_ReturnsThatChunksSnapshot() throws Exception {
        stubMetadata();

        StoragePath batchPath = StoragePath.of(TEST_RUN_ID + "/raw/000/000/batch_0000000000000001000_0000000000000001299.pb");
        when(storageRead.findBatchFileContaining(TEST_RUN_ID + "/raw/", 1250L)).thenReturn(Optional.of(batchPath));
        stubChunkRead(batchPath, chunk(1000, 1099), chunk(1100, 1199), chunk(1200, 1299));

        ResumeCheckpoint checkpoint = loader.loadCheckpointContaining(TEST_RUN_ID, 1250);

        assertThat(checkpoint.getCheckpointTick()).isEqualTo(1200);
        assertThat(checkpoint.getResumeFromTick()).isEqualTo(1201);
    }

    @Test
    void loadCheckpointContaining_TickOnChunkFirstTick_ReturnsThatChunksSnapshot() throws Exception {
        stubMetadata();

        StoragePath batchPath = StoragePath.of(TEST_RUN_ID + "/raw/000/000/batch_0000000000000001000_0000000000000001199.pb");
        when(storageRead.findBatchFileContaining(TEST_RUN_ID + "/raw/", 1100L)).thenReturn(Optional.of(batchPath));
        stubChunkRead(batchPath, chunk(1000, 1099), chunk(1100, 1199));

        ResumeCheckpoint checkpoint = loader.loadCheckpointContaining(TEST_RUN_ID, 1100);

        assertThat(checkpoint.getCheckpointTick()).isEqualTo(1100);
    }

    @Test
    void loadCheckpointContaining_TickOnChunkLastTick_ReturnsThatChunksSnapshot() throws Exception {
        stubMetadata();

        StoragePath batchPath = StoragePath.of(TEST_RUN_ID + "/raw/000/000/batch_0000000000000001000_0000000000000001199.pb");
        when(storageRead.findBatchFileContaining(TEST_RUN_ID + "/raw/", 1099L)).thenReturn(Optional.of(batchPath));
        stubChunkRead(batchPath, chunk(1000, 1099), chunk(1100, 1199));

        ResumeCheckpoint checkpoint = loader.loadCheckpointContaining(TEST_RUN_ID, 1099);

        assertThat(checkpoint.getCheckpointTick()).isEqualTo(1000);
    }

    @Test
    void loadCheckpointContaining_TickBeyondRecordedData_NamesLastCoveredTick() throws Exception {
        stubMetadata();

        when(storageRead.findBatchFileContaining(TEST_RUN_ID + "/raw/", 5000L)).thenReturn(Optional.empty());

        StoragePath lastBatchPath = StoragePath.of(TEST_RUN_ID + "/raw/000/000/batch_0000000000000001000_0000000000000001199.pb");
        when(storageRead.findLastBatchFile(TEST_RUN_ID + "/raw/")).thenReturn(Optional.of(lastBatchPath));
        when(storageRead.listBatchFiles(TEST_RUN_ID + "/raw/", null, 1))
            .thenReturn(new BatchFileListResult(List.of(lastBatchPath), null, false));
        stubChunkRead(lastBatchPath, chunk(1000, 1099), chunk(1100, 1199));

        assertThatThrownBy(() -> loader.loadCheckpointContaining(TEST_RUN_ID, 5000))
            .isInstanceOf(ResumeException.class)
            .hasMessageContaining("tick 5000")
            .hasMessageContaining(TEST_RUN_ID)
            .hasMessageContaining("covers ticks 1000 to 1199");
    }

    @Test
    void loadCheckpointContaining_TickBeforeRecordedData_NamesWhereTheDataBegins() throws Exception {
        stubMetadata();

        when(storageRead.findBatchFileContaining(TEST_RUN_ID + "/raw/", 0L)).thenReturn(Optional.empty());

        StoragePath onlyBatchPath = StoragePath.of(TEST_RUN_ID + "/raw/000/001/batch_0000000000000150000_0000000000000150199.pb");
        when(storageRead.findLastBatchFile(TEST_RUN_ID + "/raw/")).thenReturn(Optional.of(onlyBatchPath));
        when(storageRead.listBatchFiles(TEST_RUN_ID + "/raw/", null, 1))
            .thenReturn(new BatchFileListResult(List.of(onlyBatchPath), null, false));
        stubChunkRead(onlyBatchPath, chunk(150000, 150099), chunk(150100, 150199));

        assertThatThrownBy(() -> loader.loadCheckpointContaining(TEST_RUN_ID, 0))
            .isInstanceOf(ResumeException.class)
            .hasMessageContaining("tick 0")
            .hasMessageContaining("covers ticks 150000 to 150199");
    }

    @Test
    void loadCheckpointContaining_NoBatchFiles_ThrowsResumeException() throws Exception {
        stubMetadata();

        when(storageRead.findBatchFileContaining(TEST_RUN_ID + "/raw/", 100L)).thenReturn(Optional.empty());
        when(storageRead.findLastBatchFile(TEST_RUN_ID + "/raw/")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loader.loadCheckpointContaining(TEST_RUN_ID, 100))
            .isInstanceOf(ResumeException.class)
            .hasMessageContaining("No tick data found");
    }

    // ==================== Error Cases ====================

    @Test
    void loadLatestCheckpoint_MetadataNotFound_ThrowsResumeException() throws IOException {
        when(storageRead.findMetadataPath(TEST_RUN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loader.loadLatestCheckpoint(TEST_RUN_ID))
            .isInstanceOf(ResumeException.class)
            .hasMessageContaining("Metadata not found");
    }

    @Test
    void loadLatestCheckpoint_NoBatchFiles_ThrowsResumeException() throws IOException {
        // Setup: Metadata exists
        StoragePath metadataPath = StoragePath.of(TEST_RUN_ID + "/raw/metadata.pb");
        SimulationMetadata metadata = createMetadata(TEST_RUN_ID);
        when(storageRead.findMetadataPath(TEST_RUN_ID)).thenReturn(Optional.of(metadataPath));
        when(storageRead.readMessage(eq(metadataPath), any())).thenReturn(metadata);

        // No batch files
        when(storageRead.findLastBatchFile(TEST_RUN_ID + "/raw/")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loader.loadLatestCheckpoint(TEST_RUN_ID))
            .isInstanceOf(ResumeException.class)
            .hasMessageContaining("No tick data found");
    }

    @Test
    void loadLatestCheckpoint_EmptyBatch_ThrowsResumeException() throws IOException {
        // Setup: Metadata exists
        StoragePath metadataPath = StoragePath.of(TEST_RUN_ID + "/raw/metadata.pb");
        SimulationMetadata metadata = createMetadata(TEST_RUN_ID);
        when(storageRead.findMetadataPath(TEST_RUN_ID)).thenReturn(Optional.of(metadataPath));
        when(storageRead.readMessage(eq(metadataPath), any())).thenReturn(metadata);

        // Batch file exists but is empty (consumer never invoked → last[0] stays null)
        StoragePath batchPath = StoragePath.of(TEST_RUN_ID + "/raw/batch.pb");
        when(storageRead.findLastBatchFile(TEST_RUN_ID + "/raw/")).thenReturn(Optional.of(batchPath));

        assertThatThrownBy(() -> loader.loadLatestCheckpoint(TEST_RUN_ID))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Empty batch file");
    }

    @Test
    void loadLatestCheckpoint_RunIdMismatch_ThrowsResumeException() throws IOException {
        // Setup: Metadata exists but has different run ID
        StoragePath metadataPath = StoragePath.of(TEST_RUN_ID + "/raw/metadata.pb");
        SimulationMetadata wrongMetadata = createMetadata("different-run-id");
        when(storageRead.findMetadataPath(TEST_RUN_ID)).thenReturn(Optional.of(metadataPath));
        when(storageRead.readMessage(eq(metadataPath), any())).thenReturn(wrongMetadata);

        assertThatThrownBy(() -> loader.loadLatestCheckpoint(TEST_RUN_ID))
            .isInstanceOf(ResumeException.class)
            .hasMessageContaining("Run ID mismatch");
    }

    // ==================== Helper Methods ====================

    private void stubMetadata() throws IOException {
        StoragePath metadataPath = StoragePath.of(TEST_RUN_ID + "/raw/metadata.pb");
        when(storageRead.findMetadataPath(TEST_RUN_ID)).thenReturn(Optional.of(metadataPath));
        when(storageRead.readMessage(eq(metadataPath), any())).thenReturn(createMetadata(TEST_RUN_ID));
    }

    private TickDataChunk chunk(long firstTick, long lastTick) {
        return TickDataChunk.newBuilder()
            .setFirstTick(firstTick)
            .setLastTick(lastTick)
            .setTickCount((int) (lastTick - firstTick + 1))
            .setSnapshot(createSnapshot(firstTick))
            .build();
    }

    /**
     * Hands the given chunks to the snapshot-only read of the batch file, in order.
     *
     * @return counter of the chunks the caller actually consumed
     */
    private AtomicInteger stubChunkRead(StoragePath path, TickDataChunk... chunks) throws Exception {
        AtomicInteger consumed = new AtomicInteger();
        doAnswer(invocation -> {
            CheckedConsumer<TickDataChunk> consumer = invocation.getArgument(2);
            for (TickDataChunk chunk : chunks) {
                consumed.incrementAndGet();
                consumer.accept(chunk);
            }
            return null;
        }).when(storageRead).forEachChunk(eq(path), eq(ChunkFieldFilter.SNAPSHOT_ONLY), any());
        return consumed;
    }

    private void stubSnapshotRead(StoragePath path, TickData snapshot) throws Exception {
        TickDataChunk chunk = TickDataChunk.newBuilder()
            .setSnapshot(snapshot)
            .build();
        doAnswer(invocation -> {
            CheckedConsumer<TickDataChunk> consumer = invocation.getArgument(2);
            consumer.accept(chunk);
            return null;
        }).when(storageRead).forEachChunk(eq(path), eq(ChunkFieldFilter.SNAPSHOT_ONLY), any());
    }

    private SimulationMetadata createMetadata(String runId) {
        return SimulationMetadata.newBuilder()
            .setSimulationRunId(runId)
            .setStartTimeMs(System.currentTimeMillis())
            .setInitialSeed(42)
            .setResolvedConfigJson(TestMetadataHelper.builder()
                .samplingInterval(1)
                .accumulatedDeltaInterval(5)
                .snapshotInterval(20)
                .chunkInterval(1)
                .build())
            .build();
    }

    private TickData createSnapshot(long tickNumber) {
        return TickData.newBuilder()
            .setTickNumber(tickNumber)
            .setSimulationRunId(TEST_RUN_ID)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setCellColumns(CellDataColumns.newBuilder().build())
            .build();
    }

}
