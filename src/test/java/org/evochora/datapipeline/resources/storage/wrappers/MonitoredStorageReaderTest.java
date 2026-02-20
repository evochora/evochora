package org.evochora.datapipeline.resources.storage.wrappers;

import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.CheckedConsumer;
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

        when(mockDelegate.listBatchFiles("run/", null, 10, 0L, Long.MAX_VALUE,
                IResourceBatchStorageRead.SortOrder.ASCENDING)).thenReturn(result);

        BatchFileListResult actualResult = monitoredReader.listBatchFiles("run/", null, 10);

        verify(mockDelegate).listBatchFiles("run/", null, 10, 0L, Long.MAX_VALUE,
                IResourceBatchStorageRead.SortOrder.ASCENDING);
        assertEquals(result, actualResult);
    }

    @Test
    void testReadMetricsTracked() throws Exception {
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
        doAnswer(invocation -> {
            CheckedConsumer<TickDataChunk> c = invocation.getArgument(2);
            for (TickDataChunk ch : chunks) {
                c.accept(ch);
            }
            return null;
        }).when(mockDelegate).forEachChunk(any(), any(), any());

        List<TickDataChunk> result = new java.util.ArrayList<>();
        monitoredReader.forEachChunk(testPath, result::add);

        assertEquals(2, result.size());
        verify(mockDelegate).forEachChunk(eq(testPath), eq(ChunkFieldFilter.ALL), any());

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("batches_read").longValue());
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
    void testForEachChunkDelegatesWithFilterAndTracksMetrics() throws Exception {
        TickDataChunk chunk = TickDataChunk.newBuilder()
            .setSimulationRunId("test-sim")
            .setFirstTick(100)
            .setLastTick(100)
            .setTickCount(1)
            .setSnapshot(TickData.newBuilder().setTickNumber(100).build())
            .build();

        StoragePath testPath = StoragePath.of("batch.pb.zst");
        doAnswer(invocation -> {
            CheckedConsumer<TickDataChunk> c = invocation.getArgument(2);
            c.accept(chunk);
            return null;
        }).when(mockDelegate).forEachChunk(any(), any(), any());

        List<TickDataChunk> received = new java.util.ArrayList<>();
        monitoredReader.forEachChunk(testPath, ChunkFieldFilter.SKIP_CELLS, received::add);

        verify(mockDelegate).forEachChunk(eq(testPath), eq(ChunkFieldFilter.SKIP_CELLS), any());
        assertEquals(1, received.size());
        assertEquals(100, received.get(0).getFirstTick());

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("batches_read").longValue());
    }

    @Test
    void testReadErrorTracked() throws Exception {
        StoragePath testPath = StoragePath.of("batch.pb.zst");
        doThrow(new IOException("Read failed"))
            .when(mockDelegate).forEachChunk(any(), any(), any());

        assertThrows(IOException.class, () -> monitoredReader.forEachChunk(testPath, chunk -> {}));

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("read_errors").longValue());
    }
}
