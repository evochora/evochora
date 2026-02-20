package org.evochora.datapipeline.resources.storage;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
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

    private TickDataChunk createChunk(long firstTick, long lastTick, int tickCount) {
        TickData snapshot = TickData.newBuilder()
                .setTickNumber(firstTick)
                .setSimulationRunId("test-sim")
                .setCaptureTimeMs(System.currentTimeMillis())
                .setCellColumns(CellDataColumns.newBuilder()
                        .addFlatIndices(0)
                        .addMoleculeData(100)
                        .addOwnerIds(1)
                        .build())
                .build();

        TickDataChunk.Builder chunkBuilder = TickDataChunk.newBuilder()
                .setSimulationRunId("test-sim")
                .setFirstTick(firstTick)
                .setLastTick(lastTick)
                .setTickCount(tickCount)
                .setSnapshot(snapshot);

        for (long tick = firstTick + 1; tick <= lastTick; tick++) {
            TickDelta delta = TickDelta.newBuilder()
                    .setTickNumber(tick)
                    .setCaptureTimeMs(System.currentTimeMillis())
                    .setDeltaType(DeltaType.INCREMENTAL)
                    .setChangedCells(CellDataColumns.getDefaultInstance())
                    .build();
            chunkBuilder.addDeltas(delta);
        }

        return chunkBuilder.build();
    }

    @Test
    void testPhysicalPathsReturnedWithCompression() throws IOException {
        // Write batches with compression
        storageCompressed.writeChunkBatchStreaming(List.of(createChunk(0, 10, 1)).iterator());
        storageCompressed.writeChunkBatchStreaming(List.of(createChunk(100, 200, 1)).iterator());

        // List all batches (no tick filter)
        BatchFileListResult result = storageCompressed.listBatchFiles("test-sim/", null, 100);

        assertEquals(2, result.getFilenames().size(), "Should find 2 batches");
        
        // Verify that returned paths are PHYSICAL (include .pb.zst extension when compression enabled)
        for (StoragePath filename : result.getFilenames()) {
            assertTrue(filename.asString().contains(".pb"), 
                "Physical paths should contain .pb");
            assertTrue(filename.asString().endsWith(".pb.zst"), 
                "Compressed storage should return physical paths with .zst extension, but got: " + filename);
        }
    }

    @Test
    void testTickFilteringWorksWithCompression() throws IOException {
        // Write batches at different tick ranges with compression
        storageCompressed.writeChunkBatchStreaming(List.of(createChunk(0, 10, 1)).iterator());
        storageCompressed.writeChunkBatchStreaming(List.of(createChunk(100, 200, 1)).iterator());
        storageCompressed.writeChunkBatchStreaming(List.of(createChunk(1000, 2000, 1)).iterator());

        // Filter by start tick with compression
        BatchFileListResult result = storageCompressed.listBatchFiles("test-sim/", null, 100, 100L);

        assertEquals(2, result.getFilenames().size(), "Should find 2 batches >= tick 100");
        
        // Verify logical paths
        for (StoragePath filename : result.getFilenames()) {
            assertTrue(filename.asString().contains(".pb"), "Should return physical paths with .pb");
            // Compression extension depends on config;
        }
        
        // Verify correct batches were returned
        assertTrue(result.getFilenames().stream().anyMatch(f -> f.asString().contains("batch_0000000000000000100_")));
        assertTrue(result.getFilenames().stream().anyMatch(f -> f.asString().contains("batch_0000000000000001000_")));
        assertFalse(result.getFilenames().stream().anyMatch(f -> f.asString().contains("batch_0000000000000000000_")));
    }

    @Test
    void testTickRangeFilteringWorksWithCompression() throws IOException {
        // Write batches with compression
        storageCompressed.writeChunkBatchStreaming(List.of(createChunk(0, 99, 1)).iterator());
        storageCompressed.writeChunkBatchStreaming(List.of(createChunk(500, 599, 1)).iterator());
        storageCompressed.writeChunkBatchStreaming(List.of(createChunk(1000, 1099, 1)).iterator());
        storageCompressed.writeChunkBatchStreaming(List.of(createChunk(2000, 2099, 1)).iterator());

        // Filter by tick range
        BatchFileListResult result = storageCompressed.listBatchFiles("test-sim/", null, 100, 500L, 1000L);

        assertEquals(2, result.getFilenames().size(), "Should find 2 batches in range [500, 1000]");
        
        // Verify logical paths
        for (StoragePath filename : result.getFilenames()) {
            assertTrue(filename.asString().contains(".pb"), "Should return physical paths with .pb");
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
        assertEquals(0, storageCompressed.parseBatchStartTick("test-sim/raw/000/000/batch_0000000000000000000_0000000000000000999.pb.zst"));
        assertEquals(999, storageCompressed.parseBatchEndTick("test-sim/raw/000/000/batch_0000000000000000000_0000000000000000999.pb.zst"));
    }

    @Test
    void testBehaviorIdenticalWithAndWithoutCompression() throws IOException {
        // Write same batches to both storages
        for (int i = 0; i < 5; i++) {
            long start = i * 1000L;
            long end = start + 999;
            storageCompressed.writeChunkBatchStreaming(List.of(createChunk(start, end, 1)).iterator());
            storageUncompressed.writeChunkBatchStreaming(List.of(createChunk(start, end, 1)).iterator());
        }

        // Query both with same tick filter
        BatchFileListResult compressedResult = storageCompressed.listBatchFiles("test-sim/", null, 100, 2000L);
        BatchFileListResult uncompressedResult = storageUncompressed.listBatchFiles("test-sim/", null, 100, 2000L);

        // Results should be identical (both return logical paths)
        assertEquals(compressedResult.getFilenames().size(), uncompressedResult.getFilenames().size(),
            "Compressed and uncompressed storage should return same number of results");
        
        // Verify both return logical paths
        for (StoragePath filename : compressedResult.getFilenames()) {
            assertTrue(filename.asString().contains(".pb"), "Should return physical paths with .pb");
            // Compression extension depends on config;
        }
        
        for (StoragePath filename : uncompressedResult.getFilenames()) {
            assertTrue(filename.asString().contains(".pb"), "Should return physical paths with .pb");
        }
    }

    @Test
    void testReadChunkBatchWorksWithTickFilteredResults() throws IOException {
        // Write compressed chunk batch
        List<TickDataChunk> originalBatch = List.of(createChunk(1000, 1020, 21));
        storageCompressed.writeChunkBatchStreaming(originalBatch.iterator());

        // Filter to find this batch
        BatchFileListResult result = storageCompressed.listBatchFiles("test-sim/", null, 100, 1000L, 1000L);
        
        assertEquals(1, result.getFilenames().size(), "Should find exactly one batch");
        StoragePath path = result.getFilenames().get(0);
        
        // Read the batch using the path
        List<TickDataChunk> readBatch = storageCompressed.readChunkBatch(path);
        
        assertEquals(originalBatch.size(), readBatch.size(), "Should read all chunks");
        assertEquals(originalBatch, readBatch, "Data should be preserved through compression round-trip");
    }
}

