package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.storage.CheckedConsumer;
import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
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
