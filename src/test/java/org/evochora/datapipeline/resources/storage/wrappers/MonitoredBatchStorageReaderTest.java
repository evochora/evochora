package org.evochora.datapipeline.resources.storage.wrappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.storage.CheckedConsumer;
import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
import org.evochora.datapipeline.api.resources.storage.IResourceBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.RawChunk;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link MonitoredBatchStorageReader}.
 * <p>
 * Tests delegation and metrics tracking for both {@code forEachRawChunk}
 * and {@code forEachChunk} operations.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class MonitoredBatchStorageReaderTest {

    private IResourceBatchStorageRead mockDelegate;
    private MonitoredBatchStorageReader reader;

    @BeforeEach
    void setUp() {
        mockDelegate = mock(IResourceBatchStorageRead.class);
        ResourceContext context = new ResourceContext(
            "test-service", "port", "storage-read", "test-storage", Map.of());
        reader = new MonitoredBatchStorageReader(mockDelegate, context);
    }

    // ========================================================================
    // forEachRawChunk
    // ========================================================================

    @Test
    void forEachRawChunk_delegatesToUnderlyingStorage() throws Exception {
        StoragePath path = StoragePath.of("test/batch.pb");
        AtomicInteger chunksReceived = new AtomicInteger(0);

        doAnswer(invocation -> {
            CheckedConsumer<RawChunk> consumer = invocation.getArgument(1);
            consumer.accept(new RawChunk(0, 99, 100, new byte[1024]));
            consumer.accept(new RawChunk(100, 199, 100, new byte[2048]));
            return null;
        }).when(mockDelegate).forEachRawChunk(any(), any());

        reader.forEachRawChunk(path, rawChunk -> chunksReceived.incrementAndGet());

        verify(mockDelegate).forEachRawChunk(any(), any());
        assertThat(chunksReceived.get()).isEqualTo(2);
    }

    @Test
    void forEachRawChunk_tracksBytesRead() throws Exception {
        StoragePath path = StoragePath.of("test/batch.pb");

        doAnswer(invocation -> {
            CheckedConsumer<RawChunk> consumer = invocation.getArgument(1);
            consumer.accept(new RawChunk(0, 99, 100, new byte[1024]));
            consumer.accept(new RawChunk(100, 199, 100, new byte[2048]));
            return null;
        }).when(mockDelegate).forEachRawChunk(any(), any());

        reader.forEachRawChunk(path, rawChunk -> {});

        Map<String, Number> metrics = reader.getMetrics();
        assertThat(metrics.get("batches_read").longValue()).isEqualTo(1);
        assertThat(metrics.get("bytes_read").longValue()).isEqualTo(1024 + 2048);
    }

    @Test
    void forEachRawChunk_noChunks_tracksBatchWithZeroBytes() throws Exception {
        StoragePath path = StoragePath.of("test/empty.pb");
        doAnswer(invocation -> null).when(mockDelegate).forEachRawChunk(any(), any());

        reader.forEachRawChunk(path, rawChunk -> {});

        Map<String, Number> metrics = reader.getMetrics();
        assertThat(metrics.get("batches_read").longValue()).isEqualTo(1);
        assertThat(metrics.get("bytes_read").longValue()).isEqualTo(0);
    }

    @Test
    void forEachRawChunk_onIOException_incrementsReadErrors() throws Exception {
        StoragePath path = StoragePath.of("test/fail.pb");
        doThrow(new IOException("read failed"))
            .when(mockDelegate).forEachRawChunk(any(), any());

        assertThatThrownBy(() -> reader.forEachRawChunk(path, rawChunk -> {}))
            .isInstanceOf(IOException.class);

        Map<String, Number> metrics = reader.getMetrics();
        assertThat(metrics.get("read_errors").longValue()).isEqualTo(1);
        assertThat(metrics.get("batches_read").longValue()).isEqualTo(0);
    }

    // ========================================================================
    // forEachChunk (3-arg)
    // ========================================================================

    @Test
    void forEachChunk_tracksBytesFromSerializedSize() throws Exception {
        StoragePath path = StoragePath.of("test/batch.pb");

        TickDataChunk chunk1 = TickDataChunk.newBuilder()
            .setSimulationRunId("run-1")
            .setFirstTick(0).setLastTick(99).setTickCount(100)
            .build();
        TickDataChunk chunk2 = TickDataChunk.newBuilder()
            .setSimulationRunId("run-1")
            .setFirstTick(100).setLastTick(199).setTickCount(100)
            .build();

        doAnswer(invocation -> {
            CheckedConsumer<TickDataChunk> consumer = invocation.getArgument(2);
            consumer.accept(chunk1);
            consumer.accept(chunk2);
            return null;
        }).when(mockDelegate).forEachChunk(any(), any(), any());

        reader.forEachChunk(path, ChunkFieldFilter.ALL, chunk -> {});

        Map<String, Number> metrics = reader.getMetrics();
        long expectedBytes = chunk1.getSerializedSize() + chunk2.getSerializedSize();
        assertThat(metrics.get("batches_read").longValue()).isEqualTo(1);
        assertThat(metrics.get("bytes_read").longValue()).isEqualTo(expectedBytes);
    }

    @Test
    void forEachChunk_onIOException_incrementsReadErrors() throws Exception {
        StoragePath path = StoragePath.of("test/fail.pb");
        doThrow(new IOException("chunk read failed"))
            .when(mockDelegate).forEachChunk(any(), any(), any());

        assertThatThrownBy(() ->
            reader.forEachChunk(path, ChunkFieldFilter.ALL, chunk -> {}))
            .isInstanceOf(IOException.class);

        Map<String, Number> metrics = reader.getMetrics();
        assertThat(metrics.get("read_errors").longValue()).isEqualTo(1);
    }

    // ========================================================================
    // Cumulative metrics across operations
    // ========================================================================

    @Test
    void multipleCalls_accumulateMetrics() throws Exception {
        doAnswer(invocation -> {
            CheckedConsumer<RawChunk> consumer = invocation.getArgument(1);
            consumer.accept(new RawChunk(0, 99, 100, new byte[500]));
            return null;
        }).when(mockDelegate).forEachRawChunk(any(), any());

        reader.forEachRawChunk(StoragePath.of("batch1.pb"), rawChunk -> {});
        reader.forEachRawChunk(StoragePath.of("batch2.pb"), rawChunk -> {});

        Map<String, Number> metrics = reader.getMetrics();
        assertThat(metrics.get("batches_read").longValue()).isEqualTo(2);
        assertThat(metrics.get("bytes_read").longValue()).isEqualTo(1000);
    }
}
