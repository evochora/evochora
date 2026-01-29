package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
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
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*SnapshotLoader.*")
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
    void loadLatestCheckpoint_ReturnsSnapshotFromLastChunk() throws IOException {
        // Setup: Metadata exists
        StoragePath metadataPath = StoragePath.of(TEST_RUN_ID + "/raw/metadata.pb");
        SimulationMetadata metadata = createMetadata(TEST_RUN_ID);
        when(storageRead.findMetadataPath(TEST_RUN_ID)).thenReturn(Optional.of(metadataPath));
        when(storageRead.readMessage(eq(metadataPath), any())).thenReturn(metadata);

        // Setup: One batch file
        StoragePath batchPath = StoragePath.of(TEST_RUN_ID + "/raw/000/000/batch_0000000000000001000_0000000000000001099.pb");
        when(storageRead.findLastBatchFile(TEST_RUN_ID + "/raw/")).thenReturn(Optional.of(batchPath));

        TickDataChunk chunk = createChunk(1000, 1099);
        when(storageRead.readChunkBatch(batchPath)).thenReturn(List.of(chunk));

        // Execute
        ResumeCheckpoint checkpoint = loader.loadLatestCheckpoint(TEST_RUN_ID);

        // Verify snapshot is returned
        assertThat(checkpoint.snapshot().getTickNumber()).isEqualTo(1000);
        assertThat(checkpoint.getResumeFromTick()).isEqualTo(1001);
        assertThat(checkpoint.getCheckpointTick()).isEqualTo(1000);
    }

    @Test
    void loadLatestCheckpoint_MultipleChunks_ReturnsSnapshotFromLastChunk() throws IOException {
        // Setup: Metadata exists
        StoragePath metadataPath = StoragePath.of(TEST_RUN_ID + "/raw/metadata.pb");
        SimulationMetadata metadata = createMetadata(TEST_RUN_ID);
        when(storageRead.findMetadataPath(TEST_RUN_ID)).thenReturn(Optional.of(metadataPath));
        when(storageRead.readMessage(eq(metadataPath), any())).thenReturn(metadata);

        // Setup: Batch with multiple chunks
        StoragePath batchPath = StoragePath.of(TEST_RUN_ID + "/raw/000/000/batch.pb");
        when(storageRead.findLastBatchFile(TEST_RUN_ID + "/raw/")).thenReturn(Optional.of(batchPath));

        TickDataChunk chunk1 = createChunk(1000, 1099);
        TickDataChunk chunk2 = createChunk(1100, 1199);
        TickDataChunk chunk3 = createChunk(1200, 1299);
        when(storageRead.readChunkBatch(batchPath)).thenReturn(List.of(chunk1, chunk2, chunk3));

        // Execute
        ResumeCheckpoint checkpoint = loader.loadLatestCheckpoint(TEST_RUN_ID);

        // Verify snapshot from LAST chunk is returned
        assertThat(checkpoint.snapshot().getTickNumber()).isEqualTo(1200);
        assertThat(checkpoint.getResumeFromTick()).isEqualTo(1201);
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
            .setResolvedConfigJson(TestMetadataHelper.builder()
                .samplingInterval(1)
                .accumulatedDeltaInterval(5)
                .snapshotInterval(20)
                .chunkInterval(1)
                .build())
            .build();
    }

    private TickDataChunk createChunk(long firstTick, long lastTick) {
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(firstTick)
            .setSimulationRunId(TEST_RUN_ID)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setCellColumns(CellDataColumns.newBuilder().build())
            .build();

        return TickDataChunk.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setFirstTick(firstTick)
            .setLastTick(lastTick)
            .setTickCount((int)(lastTick - firstTick + 1))
            .setSnapshot(snapshot)
            .build();
    }
}
