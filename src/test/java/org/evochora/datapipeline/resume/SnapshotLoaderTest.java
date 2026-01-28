package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link SnapshotLoader}.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
@ExtendWith(MockitoExtension.class)
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*SnapshotLoader.*")
class SnapshotLoaderTest {

    private static final String TEST_RUN_ID = "20250127-123456-test-run";

    @Mock
    private IBatchStorageRead storageRead;
    @Mock
    private IBatchStorageWrite storageWrite;
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
        TickDelta.newBuilder().build();
        CellDataColumns.newBuilder().build();
    }

    @BeforeEach
    void setUp() {
        loader = new SnapshotLoader(storageRead, storageWrite);
    }

    // ==================== Happy Path Tests ====================
    // Note: The basic "WithAccumulatedDelta" happy path is covered by ResumeEndToEndTest
    // which runs a real simulation. Here we only test edge cases.

    @Test
    void loadLatestCheckpoint_WithoutAccumulatedDelta_FallsBackToSnapshot() throws IOException {
        // Setup: Metadata exists
        StoragePath metadataPath = StoragePath.of(TEST_RUN_ID + "/raw/metadata.pb");
        SimulationMetadata metadata = createMetadata(TEST_RUN_ID);
        when(storageRead.findMetadataPath(TEST_RUN_ID)).thenReturn(Optional.of(metadataPath));
        when(storageRead.readMessage(eq(metadataPath), any())).thenReturn(metadata);

        // Setup: One batch file with only incremental deltas (no accumulated)
        StoragePath batchPath = StoragePath.of(TEST_RUN_ID + "/raw/000/000/batch_0000000000000001000_0000000000000001000.pb");
        when(storageRead.findLastBatchFile(TEST_RUN_ID + "/raw/")).thenReturn(Optional.of(batchPath));

        TickDataChunk chunk = createChunkWithoutAccumulatedDelta(1000);
        when(storageRead.readChunkBatch(batchPath)).thenReturn(List.of(chunk));

        // Execute
        ResumeCheckpoint checkpoint = loader.loadLatestCheckpoint(TEST_RUN_ID);

        // Verify fallback to snapshot
        assertThat(checkpoint.hasAccumulatedDelta()).isFalse();
        assertThat(checkpoint.snapshot().getTickNumber()).isEqualTo(1000);
        assertThat(checkpoint.getResumeFromTick()).isEqualTo(1001);
    }

    @Test
    void loadLatestCheckpoint_WithTicksAfterResumePoint_TruncatesChunk() throws IOException {
        // Setup: Metadata exists
        StoragePath metadataPath = StoragePath.of(TEST_RUN_ID + "/raw/metadata.pb");
        SimulationMetadata metadata = createMetadata(TEST_RUN_ID);
        when(storageRead.findMetadataPath(TEST_RUN_ID)).thenReturn(Optional.of(metadataPath));
        when(storageRead.readMessage(eq(metadataPath), any())).thenReturn(metadata);

        // Setup: Batch file with accumulated delta at 1040 but ticks go up to 1060
        StoragePath originalPath = StoragePath.of(TEST_RUN_ID + "/raw/000/000/batch_0000000000000001000_0000000000000001060.pb");
        when(storageRead.findLastBatchFile(TEST_RUN_ID + "/raw/")).thenReturn(Optional.of(originalPath));

        TickDataChunk chunk = createChunkWithAccumulatedDeltaAndExtra(1000, 1040, 1060);
        when(storageRead.readChunkBatch(originalPath)).thenReturn(List.of(chunk));

        // Setup: Mock the write of truncated batch
        StoragePath truncatedPath = StoragePath.of(TEST_RUN_ID + "/raw/000/000/batch_0000000000000001000_0000000000000001040.pb");
        when(storageWrite.writeChunkBatch(any(), eq(1000L), eq(1040L))).thenReturn(truncatedPath);

        // Execute
        ResumeCheckpoint checkpoint = loader.loadLatestCheckpoint(TEST_RUN_ID);

        // Verify truncation happened
        ArgumentCaptor<List<TickDataChunk>> chunkCaptor = ArgumentCaptor.forClass(List.class);
        verify(storageWrite).writeChunkBatch(chunkCaptor.capture(), eq(1000L), eq(1040L));
        verify(storageWrite).moveToSuperseded(originalPath);

        // Verify truncated chunk has correct tick range
        List<TickDataChunk> writtenChunks = chunkCaptor.getValue();
        assertThat(writtenChunks).hasSize(1);
        assertThat(writtenChunks.get(0).getLastTick()).isEqualTo(1040);

        // Verify checkpoint data
        assertThat(checkpoint.getResumeFromTick()).isEqualTo(1041);
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

        // Batch file exists but is empty
        StoragePath batchPath = StoragePath.of(TEST_RUN_ID + "/raw/batch.pb");
        when(storageRead.findLastBatchFile(TEST_RUN_ID + "/raw/")).thenReturn(Optional.of(batchPath));
        when(storageRead.readChunkBatch(batchPath)).thenReturn(List.of());

        assertThatThrownBy(() -> loader.loadLatestCheckpoint(TEST_RUN_ID))
            .isInstanceOf(ResumeException.class)
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

    private SimulationMetadata createMetadata(String runId) {
        return SimulationMetadata.newBuilder()
            .setSimulationRunId(runId)
            .setStartTimeMs(System.currentTimeMillis())
            .setInitialSeed(42)
            .build();
    }

    private TickDataChunk createChunkWithoutAccumulatedDelta(long tick) {
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(tick)
            .setSimulationRunId(TEST_RUN_ID)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setCellColumns(CellDataColumns.newBuilder().build())
            .build();

        return TickDataChunk.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setFirstTick(tick)
            .setLastTick(tick)
            .setTickCount(1)
            .setSnapshot(snapshot)
            .build();
    }

    private TickDataChunk createChunkWithAccumulatedDeltaAndExtra(
            long firstTick, long accDeltaTick, long lastTick) {

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(firstTick)
            .setSimulationRunId(TEST_RUN_ID)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setCellColumns(CellDataColumns.newBuilder().build())
            .build();

        TickDataChunk.Builder builder = TickDataChunk.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setFirstTick(firstTick)
            .setLastTick(lastTick)
            .setTickCount((int)(lastTick - firstTick + 1))
            .setSnapshot(snapshot);

        // Add accumulated delta at accDeltaTick
        builder.addDeltas(TickDelta.newBuilder()
            .setTickNumber(accDeltaTick)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setDeltaType(DeltaType.ACCUMULATED)
            .setChangedCells(CellDataColumns.newBuilder().build())
            .build());

        // Add incremental deltas after the accumulated delta (these should be truncated)
        for (long t = accDeltaTick + 10; t <= lastTick; t += 10) {
            builder.addDeltas(TickDelta.newBuilder()
                .setTickNumber(t)
                .setCaptureTimeMs(System.currentTimeMillis())
                .setDeltaType(DeltaType.INCREMENTAL)
                .setChangedCells(CellDataColumns.newBuilder().build())
                .build());
        }

        return builder.build();
    }
}
