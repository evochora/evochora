package org.evochora.datapipeline.resources.storage.wrappers;

import com.google.protobuf.Parser;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.IStorageReadResource;
import org.evochora.datapipeline.api.resources.storage.MessageReader;
import org.evochora.datapipeline.api.contracts.TickData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonitoredStorageReaderTest {

    private IStorageReadResource mockDelegate;
    private MonitoredStorageReader monitoredReader;
    private MessageReader<TickData> mockMessageReader;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        mockDelegate = mock(IStorageReadResource.class);
        mockMessageReader = mock(MessageReader.class);
        when(mockDelegate.openReader(anyString(), any(Parser.class))).thenReturn(mockMessageReader);

    ResourceContext context = new ResourceContext("test-service", "test-port", "storage-read", "test-resource", Collections.emptyMap());
        monitoredReader = new MonitoredStorageReader(mockDelegate, context);
    }

    @Test
    void testMetricsTrackedOnReadMessage() throws IOException {
        TickData tick = TickData.newBuilder().setTickNumber(1).build();
        when(mockDelegate.readMessage(anyString(), any(Parser.class))).thenReturn(tick);

        monitoredReader.readMessage("test.pb", TickData.parser());

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("read_operations"));
        assertEquals(1L, metrics.get("messages_read"));
        assertEquals((long) tick.getSerializedSize(), metrics.get("bytes_read"));
        assertEquals(0L, metrics.get("errors"));
    }

    @Test
    void testMetricsTrackedOnOpenReader() throws IOException {
        TickData tick1 = TickData.newBuilder().setTickNumber(1).build();
        TickData tick2 = TickData.newBuilder().setTickNumber(2).build();

        when(mockMessageReader.hasNext()).thenReturn(true, true, false);
        when(mockMessageReader.next()).thenReturn(tick1, tick2);

        try (MessageReader<TickData> reader = monitoredReader.openReader("test.pb", TickData.parser())) {
            while(reader.hasNext()) {
                reader.next();
            }
        }

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("read_operations"));
        assertEquals(2L, metrics.get("messages_read"));
        assertEquals((long) tick1.getSerializedSize() + tick2.getSerializedSize(), metrics.get("bytes_read"));
        assertEquals(0L, metrics.get("errors"));
    }

    @Test
    void testMetricsTrackedOnListKeys() {
        when(mockDelegate.listKeys(anyString())).thenReturn(Collections.singletonList("key1"));
        monitoredReader.listKeys("prefix");
        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("list_operations"));
    }

    @Test
    void testErrorMetricTrackedOnReadMessage() throws IOException {
        when(mockDelegate.readMessage(anyString(), any(Parser.class))).thenThrow(new IOException("Read error"));

        assertThrows(IOException.class, () -> monitoredReader.readMessage("fail.pb", TickData.parser()));

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("read_operations"));
        assertEquals(1L, metrics.get("errors"));
        assertEquals(0L, metrics.get("messages_read"));
    }

    @Test
    void testErrorMetricTrackedOnOpenReader() throws IOException {
        when(mockDelegate.openReader(anyString(), any(Parser.class))).thenThrow(new IOException("Open error"));

        assertThrows(IOException.class, () -> monitoredReader.openReader("fail.pb", TickData.parser()));

        Map<String, Number> metrics = monitoredReader.getMetrics();
        assertEquals(1L, metrics.get("read_operations"));
        assertEquals(1L, metrics.get("errors"));
    }
}