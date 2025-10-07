package org.evochora.datapipeline.resources.storage.wrappers;

import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.BatchMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.time.Instant;
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

    private IBatchStorageRead mockDelegate;
    private MonitoredBatchStorageReader monitoredReader;

    @BeforeEach
    void setUp() {
        mockDelegate = mock(IBatchStorageRead.class);
        ResourceContext context = new ResourceContext("test-service", "test-port", "storage-read", "test-resource", Collections.emptyMap());
        monitoredReader = new MonitoredBatchStorageReader(mockDelegate, context);
    }

    @Test
    void testQueryMetricsTracked() throws IOException {
        List<BatchMetadata> batches = Arrays.asList(
            new BatchMetadata("batch1.pb.zst", 100, 199, 100, 50000, Instant.now()),
            new BatchMetadata("batch2.pb.zst", 200, 299, 100, 50000, Instant.now())
        );

        when(mockDelegate.queryBatches(anyLong(), anyLong())).thenReturn(batches);

        monitoredReader.queryBatches(100, 299);

        verify(mockDelegate).queryBatches(100, 299);

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("queries_performed").longValue());
        assertEquals(2L, metrics.get("batches_queried").longValue());
    }

    @Test
    void testReadMetricsTracked() throws IOException {
        List<TickData> ticks = Arrays.asList(
            TickData.newBuilder().setTickNumber(100).build(),
            TickData.newBuilder().setTickNumber(101).build()
        );

        when(mockDelegate.readBatch(anyString())).thenReturn(ticks);

        monitoredReader.readBatch("batch.pb.zst");

        verify(mockDelegate).readBatch("batch.pb.zst");

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("batches_read").longValue());
        assertEquals(ticks.stream().mapToLong(TickData::getSerializedSize).sum(),
            metrics.get("bytes_read").longValue());
    }

    @Test
    void testQueryErrorTracked() throws IOException {
        when(mockDelegate.queryBatches(anyLong(), anyLong()))
            .thenThrow(new IOException("Query failed"));

        assertThrows(IOException.class, () -> monitoredReader.queryBatches(0, 100));

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("query_errors").longValue());
    }

    @Test
    void testReadErrorTracked() throws IOException {
        when(mockDelegate.readBatch(anyString()))
            .thenThrow(new IOException("Read failed"));

        assertThrows(IOException.class, () -> monitoredReader.readBatch("batch.pb.zst"));

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("read_errors").longValue());
    }
}
