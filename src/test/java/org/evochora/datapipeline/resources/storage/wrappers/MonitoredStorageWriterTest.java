package org.evochora.datapipeline.resources.storage.wrappers;

import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.contracts.TickData;
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

    @Test
    void testMetricsTrackedOnBatchWrite() throws IOException {
        List<TickData> batch = Arrays.asList(
            TickData.newBuilder().setTickNumber(100).build(),
            TickData.newBuilder().setTickNumber(101).build(),
            TickData.newBuilder().setTickNumber(102).build()
        );

        when(mockDelegate.writeBatch(anyList(), anyLong(), anyLong()))
            .thenReturn("001/batch.pb.zst");

        monitoredWriter.writeBatch(batch, 100, 102);

        verify(mockDelegate).writeBatch(batch, 100, 102);

        Map<String, Number> metrics = monitoredWriter.getMetrics();
        assertEquals(1L, metrics.get("batches_written").longValue());
        long expectedBytes = batch.stream().mapToLong(TickData::getSerializedSize).sum();
        assertEquals(expectedBytes, metrics.get("bytes_written").longValue());
    }

    @Test
    void testErrorMetricTrackedOnFailure() throws IOException {
        List<TickData> batch = Collections.singletonList(
            TickData.newBuilder().setTickNumber(1).build()
        );

        when(mockDelegate.writeBatch(anyList(), anyLong(), anyLong()))
            .thenThrow(new IOException("Storage failure"));

        assertThrows(IOException.class, () -> monitoredWriter.writeBatch(batch, 1, 1));

        Map<String, Number> metrics = monitoredWriter.getMetrics();
        assertEquals(1L, metrics.get("write_errors").longValue());
        assertEquals(0L, metrics.get("batches_written").longValue());
    }

    @Test
    void testMultipleBatchesTracked() throws IOException {
        when(mockDelegate.writeBatch(anyList(), anyLong(), anyLong()))
            .thenReturn("batch1.pb.zst")
            .thenReturn("batch2.pb.zst");

        List<TickData> batch1 = Arrays.asList(
            TickData.newBuilder().setTickNumber(1).build(),
            TickData.newBuilder().setTickNumber(2).build()
        );
        List<TickData> batch2 = Collections.singletonList(
            TickData.newBuilder().setTickNumber(3).build()
        );

        monitoredWriter.writeBatch(batch1, 1, 2);
        monitoredWriter.writeBatch(batch2, 3, 3);

        Map<String, Number> metrics = monitoredWriter.getMetrics();
        assertEquals(2L, metrics.get("batches_written").longValue());
    }
}
