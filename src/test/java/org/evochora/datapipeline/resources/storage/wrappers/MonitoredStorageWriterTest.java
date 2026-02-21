package org.evochora.datapipeline.resources.storage.wrappers;

import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.storage.StreamingWriteResult;
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
class MonitoredStorageWriterTest {

    private IBatchStorageWrite mockDelegate;
    private MonitoredBatchStorageWriter monitoredWriter;

    @BeforeEach
    void setUp() {
        mockDelegate = mock(IBatchStorageWrite.class);
        ResourceContext context = new ResourceContext("test-service", "test-port", "storage-write", "test-resource", Collections.emptyMap());
        monitoredWriter = new MonitoredBatchStorageWriter(mockDelegate, context);
    }

    private TickDataChunk createChunk(long firstTick, long lastTick) {
        return TickDataChunk.newBuilder()
                .setSimulationRunId("test-sim")
                .setFirstTick(firstTick)
                .setLastTick(lastTick)
                .setTickCount(1)
                .setSnapshot(TickData.newBuilder().setTickNumber(firstTick).build())
                .build();
    }

    @Test
    void testMetricsTrackedOnChunkBatchWrite() throws IOException {
        List<TickDataChunk> batch = Arrays.asList(
            createChunk(100, 109),
            createChunk(110, 119),
            createChunk(120, 129)
        );

        long expectedBytes = batch.stream().mapToLong(TickDataChunk::getSerializedSize).sum();
        StreamingWriteResult result = new StreamingWriteResult(
            StoragePath.of("001/batch.pb"), "test-sim", 100, 129, 3, 30, expectedBytes);
        when(mockDelegate.writeChunkBatchStreaming(any())).thenReturn(result);

        monitoredWriter.writeChunkBatchStreaming(batch.iterator());

        verify(mockDelegate).writeChunkBatchStreaming(any());

        Map<String, Number> metrics = monitoredWriter.getMetrics();
        assertEquals(1L, metrics.get("batches_written").longValue());
        assertEquals(expectedBytes, metrics.get("bytes_written").longValue());
    }

    @Test
    void testErrorMetricTrackedOnFailure() throws IOException {
        List<TickDataChunk> batch = Collections.singletonList(createChunk(1, 10));

        when(mockDelegate.writeChunkBatchStreaming(any()))
            .thenThrow(new IOException("Storage failure"));

        assertThrows(IOException.class, () -> monitoredWriter.writeChunkBatchStreaming(batch.iterator()));

        Map<String, Number> metrics = monitoredWriter.getMetrics();
        assertEquals(1L, metrics.get("write_errors").longValue());
        assertEquals(0L, metrics.get("batches_written").longValue());
    }

    @Test
    void testMultipleBatchesTracked() throws IOException {
        List<TickDataChunk> batch1 = Arrays.asList(
            createChunk(1, 10),
            createChunk(11, 20)
        );
        List<TickDataChunk> batch2 = Collections.singletonList(createChunk(21, 30));

        long bytes1 = batch1.stream().mapToLong(TickDataChunk::getSerializedSize).sum();
        long bytes2 = batch2.stream().mapToLong(TickDataChunk::getSerializedSize).sum();
        when(mockDelegate.writeChunkBatchStreaming(any()))
            .thenReturn(new StreamingWriteResult(StoragePath.of("batch1.pb"), "test-sim", 1, 20, 2, 20, bytes1))
            .thenReturn(new StreamingWriteResult(StoragePath.of("batch2.pb"), "test-sim", 21, 30, 1, 10, bytes2));

        monitoredWriter.writeChunkBatchStreaming(batch1.iterator());
        monitoredWriter.writeChunkBatchStreaming(batch2.iterator());

        Map<String, Number> metrics = monitoredWriter.getMetrics();
        assertEquals(2L, metrics.get("batches_written").longValue());
    }
}
