package org.evochora.datapipeline.resources.storage.wrappers;

import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
import org.evochora.datapipeline.api.resources.storage.IResourceBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class MonitoredStorageReaderTest {

    private IResourceBatchStorageRead mockDelegate;
    private MonitoredBatchStorageReader monitoredReader;

    @BeforeEach
    void setUp() {
        mockDelegate = mock(IResourceBatchStorageRead.class);
        ResourceContext context = new ResourceContext("test-service", "test-port", "storage-read", "test-resource", Collections.emptyMap());
        monitoredReader = new MonitoredBatchStorageReader(mockDelegate, context);
    }

    @Test
    void testListBatchFilesPassthrough() throws IOException {
        List<StoragePath> files = Arrays.asList(
            StoragePath.of("run/batch_001.pb.zst"),
            StoragePath.of("run/batch_002.pb.zst")
        );
        BatchFileListResult result = new BatchFileListResult(files, null, false);

        when(mockDelegate.listBatchFiles("run/", null, 10)).thenReturn(result);

        BatchFileListResult actualResult = monitoredReader.listBatchFiles("run/", null, 10);

        verify(mockDelegate).listBatchFiles("run/", null, 10);
        assertEquals(result, actualResult);
    }

    @Test
    void testReadMetricsTracked() throws IOException {
        TickDataChunk chunk1 = TickDataChunk.newBuilder()
            .setSimulationRunId("test-sim")
            .setFirstTick(100)
            .setLastTick(100)
            .setTickCount(1)
            .setSnapshot(TickData.newBuilder().setTickNumber(100).build())
            .build();
        TickDataChunk chunk2 = TickDataChunk.newBuilder()
            .setSimulationRunId("test-sim")
            .setFirstTick(101)
            .setLastTick(101)
            .setTickCount(1)
            .setSnapshot(TickData.newBuilder().setTickNumber(101).build())
            .build();
        List<TickDataChunk> chunks = Arrays.asList(chunk1, chunk2);

        StoragePath testPath = StoragePath.of("batch.pb.zst");
        when(mockDelegate.readChunkBatch(any(StoragePath.class))).thenReturn(chunks);

        monitoredReader.readChunkBatch(testPath);

        verify(mockDelegate).readChunkBatch(testPath);

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("batches_read").longValue());
        assertEquals(chunks.stream().mapToLong(TickDataChunk::getSerializedSize).sum(),
            metrics.get("bytes_read").longValue());
    }

    // Test disabled - queryBatches() removed in Step 1 (S3-incompatible non-paginated API)
    // Will be replaced with paginated API in Step 2
    // @Test
    // void testQueryErrorTracked() throws IOException {
    //     when(mockDelegate.queryBatches(anyLong(), anyLong()))
    //         .thenThrow(new IOException("Query failed"));
    //
    //     assertThrows(IOException.class, () -> monitoredReader.queryBatches(0, 100));
    //
    //     Map<String, Number> metrics = monitoredReader.getMetrics();
    //     assertEquals(1L, metrics.get("query_errors").longValue());
    // }

    @Test
    void testFilteredReadDelegatesAndTracksMetrics() throws IOException {
        TickDataChunk chunk = TickDataChunk.newBuilder()
            .setSimulationRunId("test-sim")
            .setFirstTick(100)
            .setLastTick(100)
            .setTickCount(1)
            .setSnapshot(TickData.newBuilder().setTickNumber(100).build())
            .build();
        List<TickDataChunk> chunks = List.of(chunk);

        StoragePath testPath = StoragePath.of("batch.pb.zst");
        when(mockDelegate.readChunkBatch(any(StoragePath.class), any(ChunkFieldFilter.class))).thenReturn(chunks);

        List<TickDataChunk> result = monitoredReader.readChunkBatch(testPath, ChunkFieldFilter.SKIP_ORGANISMS);

        verify(mockDelegate).readChunkBatch(testPath, ChunkFieldFilter.SKIP_ORGANISMS);
        assertEquals(1, result.size());

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("batches_read").longValue());
    }

    @Test
    void testFilteredReadWithTransformerDelegatesAndTracksMetrics() throws IOException {
        TickDataChunk chunk = TickDataChunk.newBuilder()
            .setSimulationRunId("test-sim")
            .setFirstTick(200)
            .setLastTick(200)
            .setTickCount(1)
            .setSnapshot(TickData.newBuilder().setTickNumber(200).build())
            .build();
        List<TickDataChunk> chunks = List.of(chunk);

        StoragePath testPath = StoragePath.of("batch.pb.zst");
        when(mockDelegate.readChunkBatch(any(StoragePath.class), any(ChunkFieldFilter.class), any())).thenReturn(chunks);

        List<TickDataChunk> result = monitoredReader.readChunkBatch(testPath, ChunkFieldFilter.SKIP_CELLS, c -> c);

        verify(mockDelegate).readChunkBatch(eq(testPath), eq(ChunkFieldFilter.SKIP_CELLS), any());
        assertEquals(1, result.size());

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("batches_read").longValue());
    }

    @Test
    void testReadErrorTracked() throws IOException {
        StoragePath testPath = StoragePath.of("batch.pb.zst");
        when(mockDelegate.readChunkBatch(any(StoragePath.class)))
            .thenThrow(new IOException("Read failed"));

        assertThrows(IOException.class, () -> monitoredReader.readChunkBatch(testPath));

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("read_errors").longValue());
    }
}
