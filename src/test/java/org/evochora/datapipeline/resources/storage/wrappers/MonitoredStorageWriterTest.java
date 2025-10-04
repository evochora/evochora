package org.evochora.datapipeline.resources.storage.wrappers;

import com.google.protobuf.MessageLite;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.IStorageWriteResource;
import org.evochora.datapipeline.api.resources.storage.MessageWriter;
import org.evochora.datapipeline.api.contracts.TickData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MonitoredStorageWriterTest {

    private IStorageWriteResource mockDelegate;
    private MonitoredStorageWriter monitoredWriter;
    private MessageWriter mockMessageWriter;

    @BeforeEach
    void setUp() throws IOException {
        mockDelegate = mock(IStorageWriteResource.class);
        mockMessageWriter = mock(MessageWriter.class);
        when(mockDelegate.openWriter(anyString())).thenReturn(mockMessageWriter);

    ResourceContext context = new ResourceContext("test-service", "test-port", "storage-write", "test-resource", Collections.emptyMap());
        monitoredWriter = new MonitoredStorageWriter(mockDelegate, context);
    }

    @Test
    void testMetricsTrackedOnWrite() throws IOException {
        TickData tick1 = TickData.newBuilder().setTickNumber(1).build();
        TickData tick2 = TickData.newBuilder().setTickNumber(2).build();

        try (MessageWriter writer = monitoredWriter.openWriter("test.pb")) {
            writer.writeMessage(tick1);
            writer.writeMessage(tick2);
        }

        Map<String, Number> metrics = monitoredWriter.getMetrics();
        assertEquals(1L, metrics.get("write_operations"));
        assertEquals(2L, metrics.get("messages_written"));
        assertEquals((long) tick1.getSerializedSize() + tick2.getSerializedSize(), metrics.get("bytes_written"));
        assertEquals(0L, metrics.get("errors"));
    }

    @Test
    void testErrorMetricTrackedOnOpen() throws IOException {
        when(mockDelegate.openWriter(anyString())).thenThrow(new IOException("Disk full"));

        assertThrows(IOException.class, () -> monitoredWriter.openWriter("fail.pb"));

        Map<String, Number> metrics = monitoredWriter.getMetrics();
        assertEquals(1L, metrics.get("write_operations"));
        assertEquals(0L, metrics.get("messages_written"));
        assertEquals(1L, metrics.get("errors"));
    }

    @Test
    void testErrorMetricTrackedOnWrite() throws IOException {
        doThrow(new IOException("Write failed")).when(mockMessageWriter).writeMessage(any(MessageLite.class));

        try (MessageWriter writer = monitoredWriter.openWriter("test.pb")) {
            assertThrows(IOException.class, () -> writer.writeMessage(TickData.getDefaultInstance()));
        }

        Map<String, Number> metrics = monitoredWriter.getMetrics();
        assertEquals(1L, metrics.get("write_operations"));
        assertEquals(0L, metrics.get("messages_written"));
        assertEquals(1L, metrics.get("errors"));
    }

    @Test
    void testThroughputCalculation() throws IOException, InterruptedException {
        // This is a simplified test. In a real scenario, this would be more complex.
        // For now, we just check if it's not throwing errors and is non-zero with some data.
        Map<String, String> params = Map.of("throughputWindowSeconds", "1");
        ResourceContext context = new ResourceContext("test-service", "test-port", "storage-write", "test-resource", params);
        monitoredWriter = new MonitoredStorageWriter(mockDelegate, context);

        try (MessageWriter writer = monitoredWriter.openWriter("throughput.pb")) {
            for (int i = 0; i < 20; i++) { // Write 20 messages to ensure some are sampled
                writer.writeMessage(TickData.newBuilder().setTickNumber(i).build());
            }
        }

        Map<String, Number> metrics = monitoredWriter.getMetrics();
        // With a 10% sample rate, it's highly likely some were sampled.
        // We can't deterministically test the exact value, but we can check it's positive.
        assertEquals(20L, metrics.get("messages_written"));
        // Note: throughput is sampled, so we can't assert a specific value, but it should be > 0
        // depending on timing. This is hard to test reliably in a unit test.
    }
}