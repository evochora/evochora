package org.evochora.datapipeline.resources.storage.wrappers;

import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
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

        when(mockDelegate.writeChunkBatch(anyList(), anyLong(), anyLong()))
            .thenReturn(StoragePath.of("001/batch.pb"));

        monitoredWriter.writeChunkBatch(batch, 100, 129);

        verify(mockDelegate).writeChunkBatch(batch, 100, 129);

        Map<String, Number> metrics = monitoredWriter.getMetrics();
        assertEquals(1L, metrics.get("batches_written").longValue());
        long expectedBytes = batch.stream().mapToLong(TickDataChunk::getSerializedSize).sum();
        assertEquals(expectedBytes, metrics.get("bytes_written").longValue());
    }

    @Test
    void testErrorMetricTrackedOnFailure() throws IOException {
        List<TickDataChunk> batch = Collections.singletonList(createChunk(1, 10));

        when(mockDelegate.writeChunkBatch(anyList(), anyLong(), anyLong()))
            .thenThrow(new IOException("Storage failure"));

        assertThrows(IOException.class, () -> monitoredWriter.writeChunkBatch(batch, 1, 10));

        Map<String, Number> metrics = monitoredWriter.getMetrics();
        assertEquals(1L, metrics.get("write_errors").longValue());
        assertEquals(0L, metrics.get("batches_written").longValue());
    }

    @Test
    void testMultipleBatchesTracked() throws IOException {
        when(mockDelegate.writeChunkBatch(anyList(), anyLong(), anyLong()))
            .thenReturn(StoragePath.of("batch1.pb"))
            .thenReturn(StoragePath.of("batch2.pb"));

        List<TickDataChunk> batch1 = Arrays.asList(
            createChunk(1, 10),
            createChunk(11, 20)
        );
        List<TickDataChunk> batch2 = Collections.singletonList(createChunk(21, 30));

        monitoredWriter.writeChunkBatch(batch1, 1, 20);
        monitoredWriter.writeChunkBatch(batch2, 21, 30);

        Map<String, Number> metrics = monitoredWriter.getMetrics();
        assertEquals(2L, metrics.get("batches_written").longValue());
    }
}
