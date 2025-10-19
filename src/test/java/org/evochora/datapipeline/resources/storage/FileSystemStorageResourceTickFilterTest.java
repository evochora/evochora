package org.evochora.datapipeline.resources.storage;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for tick-based filtering functionality in FileSystemStorageResource.
 * <p>
 * These tests verify the new listBatchFiles overloads that support filtering
 * by tick range.
 */
@Tag("unit")
class FileSystemStorageResourceTickFilterTest {

    @TempDir
    Path tempDir;

    private FileSystemStorageResource storage;

    @BeforeEach
    void setUp() {
        Map<String, String> configMap = Map.of("rootDirectory", tempDir.toAbsolutePath().toString());
        Config config = ConfigFactory.parseMap(configMap);
        storage = new FileSystemStorageResource("test-storage", config);
    }

    private TickData createTick(long tickNumber) {
        return TickData.newBuilder()
                .setTickNumber(tickNumber)
                .setSimulationRunId("test-sim")
                .setCaptureTimeMs(System.currentTimeMillis())
                .build();
    }

    @Test
    void testListBatchFiles_WithStartTick() throws IOException {
        // Write batches at different tick ranges
        storage.writeBatch(List.of(createTick(0), createTick(10)), 0, 10);
        storage.writeBatch(List.of(createTick(100), createTick(200)), 100, 200);
        storage.writeBatch(List.of(createTick(1000), createTick(2000)), 1000, 2000);
        storage.writeBatch(List.of(createTick(5000), createTick(6000)), 5000, 6000);

        // List all batches starting from tick 100
        BatchFileListResult result = storage.listBatchFiles("test-sim/", null, 100, 100L);

        assertEquals(3, result.getFilenames().size(), "Should find 3 batches >= tick 100");
        assertTrue(result.getFilenames().stream().anyMatch(f -> f.asString().contains("batch_0000000000000000100_")));
        assertTrue(result.getFilenames().stream().anyMatch(f -> f.asString().contains("batch_0000000000000001000_")));
        assertTrue(result.getFilenames().stream().anyMatch(f -> f.asString().contains("batch_0000000000000005000_")));
        assertFalse(result.getFilenames().stream().anyMatch(f -> f.asString().contains("batch_0000000000000000000_")));
    }

    @Test
    void testListBatchFiles_WithTickRange() throws IOException {
        // Write batches at different tick ranges
        storage.writeBatch(List.of(createTick(0), createTick(10)), 0, 10);
        storage.writeBatch(List.of(createTick(100), createTick(200)), 100, 200);
        storage.writeBatch(List.of(createTick(1000), createTick(2000)), 1000, 2000);
        storage.writeBatch(List.of(createTick(5000), createTick(6000)), 5000, 6000);

        // List batches in range [100, 1000]
        BatchFileListResult result = storage.listBatchFiles("test-sim/", null, 100, 100L, 1000L);

        assertEquals(2, result.getFilenames().size(), "Should find 2 batches in range [100, 1000]");
        assertTrue(result.getFilenames().stream().anyMatch(f -> f.asString().contains("batch_0000000000000000100_")));
        assertTrue(result.getFilenames().stream().anyMatch(f -> f.asString().contains("batch_0000000000000001000_")));
        assertFalse(result.getFilenames().stream().anyMatch(f -> f.asString().contains("batch_0000000000000000000_")));
        assertFalse(result.getFilenames().stream().anyMatch(f -> f.asString().contains("batch_0000000000000005000_")));
    }

    @Test
    void testListBatchFiles_StartTickZero() throws IOException {
        // Write batches starting from 0
        storage.writeBatch(List.of(createTick(0), createTick(10)), 0, 10);
        storage.writeBatch(List.of(createTick(100), createTick(200)), 100, 200);

        // List batches from tick 0
        BatchFileListResult result = storage.listBatchFiles("test-sim/", null, 100, 0L);

        assertEquals(2, result.getFilenames().size(), "Should find all batches");
    }

    @Test
    void testListBatchFiles_NoMatchingBatches() throws IOException {
        // Write batches in low range
        storage.writeBatch(List.of(createTick(0), createTick(10)), 0, 10);
        storage.writeBatch(List.of(createTick(100), createTick(200)), 100, 200);

        // Request batches starting from much higher tick
        BatchFileListResult result = storage.listBatchFiles("test-sim/", null, 100, 100000L);

        assertEquals(0, result.getFilenames().size(), "Should find no batches");
        assertFalse(result.isTruncated());
    }

    @Test
    void testListBatchFiles_WithMaxResults() throws IOException {
        // Write many batches
        for (int i = 0; i < 10; i++) {
            long start = i * 1000L;
            long end = start + 999;
            storage.writeBatch(List.of(createTick(start)), start, end);
        }

        // Request only 3 batches starting from tick 0
        BatchFileListResult result = storage.listBatchFiles("test-sim/", null, 3, 0L);

        assertEquals(3, result.getFilenames().size(), "Should respect maxResults limit");
        assertTrue(result.isTruncated(), "Should indicate more results available");
        assertNotNull(result.getNextContinuationToken(), "Should provide continuation token");
    }

    @Test
    void testListBatchFiles_RangeExcludesOutOfBounds() throws IOException {
        // Write batches at specific ticks
        storage.writeBatch(List.of(createTick(500)), 500, 599);
        storage.writeBatch(List.of(createTick(1000)), 1000, 1099);
        storage.writeBatch(List.of(createTick(1500)), 1500, 1599);
        storage.writeBatch(List.of(createTick(2000)), 2000, 2099);

        // Request range [1000, 1500]
        BatchFileListResult result = storage.listBatchFiles("test-sim/", null, 100, 1000L, 1500L);

        assertEquals(2, result.getFilenames().size(), "Should find exactly 2 batches in range");
        assertTrue(result.getFilenames().stream().anyMatch(f -> f.asString().contains("batch_0000000000000001000_")));
        assertTrue(result.getFilenames().stream().anyMatch(f -> f.asString().contains("batch_0000000000000001500_")));
    }

    @Test
    void testListBatchFiles_BackwardCompatibility() throws IOException {
        // Write batches
        storage.writeBatch(List.of(createTick(0), createTick(10)), 0, 10);
        storage.writeBatch(List.of(createTick(100), createTick(200)), 100, 200);

        // Use old method without tick filtering
        BatchFileListResult result = storage.listBatchFiles("test-sim/", null, 100);

        assertEquals(2, result.getFilenames().size(), "Old method should still work");
        assertFalse(result.isTruncated());
    }

    @Test
    void testParseBatchStartTick() {
        // Test the protected helper method through the public interface
        long tick = storage.parseBatchStartTick("batch_0000000000000001234_0000000000000005678.pb.zst");
        assertEquals(1234, tick, "Should parse start tick correctly");

        tick = storage.parseBatchStartTick("sim123/000/001/batch_0000000000000000000_0000000000000000999.pb");
        assertEquals(0, tick, "Should parse start tick from path");

        tick = storage.parseBatchStartTick("not_a_batch_file.pb");
        assertEquals(-1, tick, "Should return -1 for non-batch files");
    }

    @Test
    void testParseBatchEndTick() {
        // Test the protected helper method through the public interface
        long tick = storage.parseBatchEndTick("batch_0000000000000001234_0000000000000005678.pb.zst");
        assertEquals(5678, tick, "Should parse end tick correctly");

        tick = storage.parseBatchEndTick("sim123/000/001/batch_0000000000000000000_0000000000000000999.pb");
        assertEquals(999, tick, "Should parse end tick from path");

        tick = storage.parseBatchEndTick("not_a_batch_file.pb");
        assertEquals(-1, tick, "Should return -1 for non-batch files");
    }

    @Test
    void testListBatchFiles_InvalidTickRange() {
        // Test validation: endTick < startTick
        assertThrows(IllegalArgumentException.class, () -> {
            storage.listBatchFiles("test-sim/", null, 100, 1000L, 500L);
        }, "Should reject invalid tick range");
    }

    @Test
    void testListBatchFiles_NegativeStartTick() {
        // Test validation: negative startTick
        assertThrows(IllegalArgumentException.class, () -> {
            storage.listBatchFiles("test-sim/", null, 100, -1L);
        }, "Should reject negative start tick");
    }
}

