package org.evochora.datapipeline.resources.storage.wrappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.IAnalyticsStorageWrite;
import org.evochora.datapipeline.api.resources.storage.PublishedOutputStream;
import org.evochora.datapipeline.resources.storage.AbstractBatchStorageResource;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.ConfigFactory;

/**
 * Pins what the monitored analytics writer counts as a written file.
 * <p>
 * A write becomes a file when it is published; closing without that leaves nothing behind, and a
 * count of files nobody can read is worse than no count at all - it looks like data.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class MonitoredAnalyticsStorageWriterTest {

    private MonitoredAnalyticsStorageWriter writer;
    private RecordingStream raw;

    @BeforeEach
    void setUp() throws IOException {
        raw = new RecordingStream();
        AbstractBatchStorageResource resource = mock(AbstractBatchStorageResource.class,
                withSettings().extraInterfaces(IAnalyticsStorageWrite.class));
        when(resource.getResourceName()).thenReturn("test-storage");
        when(resource.getOptions()).thenReturn(ConfigFactory.empty());
        when(((IAnalyticsStorageWrite) resource)
                .openAnalyticsOutputStream(any(), any(), any(), any(), any())).thenReturn(raw);

        ResourceContext context = new ResourceContext("test-service", "test-port",
                "analytics-write", "test-resource", Collections.emptyMap());
        writer = new MonitoredAnalyticsStorageWriter(resource, context);
    }

    @Test
    void aPublishedWriteIsCountedOnce() throws IOException {
        try (PublishedOutputStream out = open()) {
            out.write(new byte[]{1, 2, 3}, 0, 3);
            out.publish();
        }

        assertEquals(1L, metric("files_written"));
        assertEquals(3L, metric("bytes_written"));
        assertEquals(0L, metric("write_errors"));
    }

    @Test
    void aWriteClosedWithoutPublishingIsNotCounted() throws IOException {
        try (PublishedOutputStream out = open()) {
            out.write(new byte[]{1, 2, 3}, 0, 3);
            // no publish - the storage below discards this, so there is no file and no bytes
        }

        assertEquals(0L, metric("files_written"));
        assertEquals(0L, metric("bytes_written"));
        assertEquals(0L, metric("write_errors"));
    }

    @Test
    void closingTwiceCountsOneFile() throws IOException {
        PublishedOutputStream out = open();
        out.write(new byte[]{1, 2}, 0, 2);
        out.publish();
        out.close();
        out.close();

        assertEquals(1L, metric("files_written"));
        assertEquals(2L, metric("bytes_written"));
    }

    @Test
    @AllowLog(level = LogLevel.WARN, messagePattern = "Failed to close analytics output stream.*")
    void aCloseThatFailsIsCountedAsAnError() throws IOException {
        raw.failOnClose = true;
        PublishedOutputStream out = open();
        out.publish();

        assertThrows(IOException.class, out::close);

        assertEquals(0L, metric("files_written"));
        assertEquals(1L, metric("write_errors"));
    }

    private PublishedOutputStream open() throws IOException {
        return writer.openAnalyticsOutputStream("run", "population", "lod0", "000", "batch.parquet");
    }

    private long metric(String name) {
        Map<String, Number> metrics = new HashMap<>();
        writer.addCustomMetrics(metrics);
        return metrics.get(name).longValue();
    }

    /** Stands in for the storage's own stream, and can be told to fail on close. */
    private static final class RecordingStream extends PublishedOutputStream {
        private boolean failOnClose;

        @Override public void write(int b) { }
        @Override public void write(byte[] b, int off, int len) { }
        @Override public void publish() { }

        @Override
        public void close() throws IOException {
            if (failOnClose) {
                throw new IOException("the disk went away");
            }
        }
    }
}
