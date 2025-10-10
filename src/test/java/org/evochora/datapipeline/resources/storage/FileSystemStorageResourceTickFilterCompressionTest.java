package org.evochora.datapipeline.resources.storage;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
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
 * Tests to verify that tick-based filtering works correctly with compressed storage.
 * <p>
 * This ensures that the logical/physical path separation is maintained correctly
 * when using compression (files have .zst extension on disk, but logical API
 * sees only .pb extensions).
 */
@Tag("unit")
class FileSystemStorageResourceTickFilterCompressionTest {

    @TempDir
    Path tempDir;

    private FileSystemStorageResource storageCompressed;
    private FileSystemStorageResource storageUncompressed;

    @BeforeEach
    void setUp() {
        // Storage with compression enabled
        Map<String, Object> compressedConfig = Map.of(
            "rootDirectory", tempDir.toAbsolutePath().toString() + "/compressed",
            "compression", Map.of(
                "enabled", true,
                "codec", "zstd",
                "level", 3
            )
        );
        Config configCompressed = ConfigFactory.parseMap(compressedConfig);
        storageCompressed = new FileSystemStorageResource("compressed-storage", configCompressed);

        // Storage without compression
        Map<String, String> uncompressedConfig = Map.of(
            "rootDirectory", tempDir.toAbsolutePath().toString() + "/uncompressed"
        );
        Config configUncompressed = ConfigFactory.parseMap(uncompressedConfig);
        storageUncompressed = new FileSystemStorageResource("uncompressed-storage", configUncompressed);
    }

    private TickData createTick(long tickNumber) {
        return TickData.newBuilder()
                .setTickNumber(tickNumber)
                .setSimulationRunId("test-sim")
                .setCaptureTimeMs(System.currentTimeMillis())
                .build();
    }

    @Test
    void testLogicalPathsReturnedWithCompression() throws IOException {
        // Write batches with compression
        storageCompressed.writeBatch(List.of(createTick(0), createTick(10)), 0, 10);
        storageCompressed.writeBatch(List.of(createTick(100), createTick(200)), 100, 200);

        // List all batches (no tick filter)
        BatchFileListResult result = storageCompressed.listBatchFiles("test-sim/", null, 100);

        assertEquals(2, result.getFilenames().size(), "Should find 2 batches");
        
        // Verify that returned paths are LOGICAL (no .zst extension)
        for (String filename : result.getFilenames()) {
            assertTrue(filename.endsWith(".pb"), 
                "Public API should return logical paths ending with .pb, but got: " + filename);
            assertFalse(filename.endsWith(".zst"), 
                "Public API should NOT expose physical .zst extension, but got: " + filename);
        }
    }

    @Test
    void testTickFilteringWorksWithCompression() throws IOException {
        // Write batches at different tick ranges with compression
        storageCompressed.writeBatch(List.of(createTick(0), createTick(10)), 0, 10);
        storageCompressed.writeBatch(List.of(createTick(100), createTick(200)), 100, 200);
        storageCompressed.writeBatch(List.of(createTick(1000), createTick(2000)), 1000, 2000);

        // Filter by start tick with compression
        BatchFileListResult result = storageCompressed.listBatchFiles("test-sim/", null, 100, 100L);

        assertEquals(2, result.getFilenames().size(), "Should find 2 batches >= tick 100");
        
        // Verify logical paths
        for (String filename : result.getFilenames()) {
            assertTrue(filename.endsWith(".pb"), "Should return logical paths");
            assertFalse(filename.endsWith(".zst"), "Should not expose compression extension");
        }
        
        // Verify correct batches were returned
        assertTrue(result.getFilenames().stream().anyMatch(f -> f.contains("batch_0000000000000000100_")));
        assertTrue(result.getFilenames().stream().anyMatch(f -> f.contains("batch_0000000000000001000_")));
        assertFalse(result.getFilenames().stream().anyMatch(f -> f.contains("batch_0000000000000000000_")));
    }

    @Test
    void testTickRangeFilteringWorksWithCompression() throws IOException {
        // Write batches with compression
        storageCompressed.writeBatch(List.of(createTick(0)), 0, 99);
        storageCompressed.writeBatch(List.of(createTick(500)), 500, 599);
        storageCompressed.writeBatch(List.of(createTick(1000)), 1000, 1099);
        storageCompressed.writeBatch(List.of(createTick(2000)), 2000, 2099);

        // Filter by tick range
        BatchFileListResult result = storageCompressed.listBatchFiles("test-sim/", null, 100, 500L, 1000L);

        assertEquals(2, result.getFilenames().size(), "Should find 2 batches in range [500, 1000]");
        
        // Verify logical paths
        for (String filename : result.getFilenames()) {
            assertTrue(filename.endsWith(".pb"), "Should return logical paths");
        }
    }

    @Test
    void testParseBatchTicksWorksWithCompression() {
        // Test with various compression extensions
        assertEquals(1234, storageCompressed.parseBatchStartTick("batch_0000000000000001234_0000000000000005678.pb.zst"));
        assertEquals(5678, storageCompressed.parseBatchEndTick("batch_0000000000000001234_0000000000000005678.pb.zst"));
        
        // Test with no compression
        assertEquals(1234, storageCompressed.parseBatchStartTick("batch_0000000000000001234_0000000000000005678.pb"));
        assertEquals(5678, storageCompressed.parseBatchEndTick("batch_0000000000000001234_0000000000000005678.pb"));
        
        // Test with path prefix
        assertEquals(0, storageCompressed.parseBatchStartTick("test-sim/000/000/batch_0000000000000000000_0000000000000000999.pb.zst"));
        assertEquals(999, storageCompressed.parseBatchEndTick("test-sim/000/000/batch_0000000000000000000_0000000000000000999.pb.zst"));
    }

    @Test
    void testBehaviorIdenticalWithAndWithoutCompression() throws IOException {
        // Write same batches to both storages
        for (int i = 0; i < 5; i++) {
            long start = i * 1000L;
            long end = start + 999;
            storageCompressed.writeBatch(List.of(createTick(start)), start, end);
            storageUncompressed.writeBatch(List.of(createTick(start)), start, end);
        }

        // Query both with same tick filter
        BatchFileListResult compressedResult = storageCompressed.listBatchFiles("test-sim/", null, 100, 2000L);
        BatchFileListResult uncompressedResult = storageUncompressed.listBatchFiles("test-sim/", null, 100, 2000L);

        // Results should be identical (both return logical paths)
        assertEquals(compressedResult.getFilenames().size(), uncompressedResult.getFilenames().size(),
            "Compressed and uncompressed storage should return same number of results");
        
        // Verify both return logical paths
        for (String filename : compressedResult.getFilenames()) {
            assertTrue(filename.endsWith(".pb"), "Compressed storage should return logical paths");
            assertFalse(filename.contains(".zst"), "Compressed storage should not expose .zst in public API");
        }
        
        for (String filename : uncompressedResult.getFilenames()) {
            assertTrue(filename.endsWith(".pb"), "Uncompressed storage should return logical paths");
        }
    }

    @Test
    void testReadBatchWorksWithTickFilteredResults() throws IOException {
        // Write compressed batches
        List<TickData> originalBatch = List.of(createTick(1000), createTick(1010), createTick(1020));
        storageCompressed.writeBatch(originalBatch, 1000, 1020);

        // Filter to find this batch
        BatchFileListResult result = storageCompressed.listBatchFiles("test-sim/", null, 100, 1000L, 1000L);
        
        assertEquals(1, result.getFilenames().size(), "Should find exactly one batch");
        String logicalPath = result.getFilenames().get(0);
        
        // Read the batch using the logical path
        List<TickData> readBatch = storageCompressed.readBatch(logicalPath);
        
        assertEquals(originalBatch.size(), readBatch.size(), "Should read all ticks");
        assertEquals(originalBatch, readBatch, "Data should be preserved through compression round-trip");
    }
}

