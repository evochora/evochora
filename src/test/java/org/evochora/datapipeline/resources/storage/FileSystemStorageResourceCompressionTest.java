package org.evochora.datapipeline.resources.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.google.protobuf.Int32Value;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

@Tag("integration")
@DisplayName("FileSystemStorageResource Compression Integration Tests")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.INFO, messagePattern = ".*using compression.*")
class FileSystemStorageResourceCompressionTest {

    @TempDir
    Path tempDir;

    // Helper to create TickDataChunk for chunk batch testing
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

        // Add deltas if tickCount > 1
        for (long tick = firstTick + 1; tick <= lastTick; tick++) {
            TickDelta delta = TickDelta.newBuilder()
                    .setTickNumber(tick)
                    .setCaptureTimeMs(System.currentTimeMillis())
                    .setDeltaType(DeltaType.INCREMENTAL)
                    .setChangedCells(CellDataColumns.newBuilder()
                            .addFlatIndices((int) tick)
                            .addMoleculeData((int) (100 + tick))
                            .addOwnerIds(1)
                            .build())
                    .build();
            chunkBuilder.addDeltas(delta);
        }

        return chunkBuilder.build();
    }

    @Nested
    @DisplayName("Compression Enabled Tests")
    class CompressionEnabled {

        private FileSystemStorageResource storage;
        private String compressionExtension; // Dynamically determined from config

        @BeforeEach
        void setUp() {
            Config config = ConfigFactory.parseString(String.format("""
                rootDirectory = "%s"
                compression {
                  enabled = true
                  codec = "zstd"
                  level = 3
                }
                """, tempDir.toString().replace("\\", "\\\\")));
            storage = new FileSystemStorageResource("test-storage", config);
            
            // Determine compression extension dynamically from config (future-proof for gzip, lz4, etc.)
            compressionExtension = org.evochora.datapipeline.utils.compression.CompressionCodecFactory
                .create(config).getFileExtension();
        }

        @Test
        @DisplayName("Writing creates compressed files")
        void writingCreatesCompressedFiles() throws IOException {
            // Arrange
            String key = "test/data.pb";
            Int32Value message = Int32Value.of(42);

            // Act: Write message using interface method
            storage.writeMessage(key, message);

            // Assert: File exists with compression extension
            Path compressedFile = tempDir.resolve("test/data.pb" + compressionExtension);
            assertThat(compressedFile).exists();

            // Assert: Original key without compression extension should NOT exist
            Path uncompressedFile = tempDir.resolve("test/data.pb");
            assertThat(uncompressedFile).doesNotExist();
        }

        @Test
        @DisplayName("Round-trip preserves data integrity")
        void roundTripPreservesData() throws IOException {
            // Arrange
            String key = "test/message.pb";
            Int32Value originalMessage = Int32Value.of(12345);

            // Act: Write and read back
            storage.writeMessage(key, originalMessage);
            Int32Value readMessage = storage.readMessage(StoragePath.of(key + ".zst"), Int32Value.parser());

            // Assert: Data preserved
            assertThat(readMessage.getValue()).isEqualTo(originalMessage.getValue());
        }

        @Test
        @DisplayName("Compression achieves size reduction")
        void compressionAchievesSizeReduction() throws IOException {
            // Arrange: Repetitive data (compresses well) - use TickDataChunk batches
            // Create many chunks with identical simulation run IDs and similar structure
            List<TickDataChunk> batch = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                batch.add(createChunk(i * 10, i * 10 + 9, 10)); // Multiple chunks
            }

            // Act: Write compressed batch
            StoragePath batchPath = storage.writeChunkBatch(batch, 0, 999);

            // Assert: Compressed file is smaller than uncompressed would be
            // Note: writeChunkBatch() now returns physical path including compression extension
            Path compressedFile = tempDir.resolve(batchPath.asString());
            long compressedSize = Files.size(compressedFile);

            // Calculate actual uncompressed size by summing message sizes
            long actualUncompressedSize = batch.stream()
                .mapToLong(chunk -> chunk.getSerializedSize() + 1) // +1 for varint delimiter
                .sum();

            // Compression should achieve measurable size reduction
            // ZSTD typically achieves 10-20% reduction on protobuf data
            double ratio = (double) actualUncompressedSize / compressedSize;
            assertThat(ratio).isGreaterThan(1.0); // At minimum, verify some compression
            assertThat(compressedSize).isLessThan(actualUncompressedSize);
        }

        @Test
        @DisplayName("readMessage works with compressed files")
        void readMessageWorksWithCompression() throws IOException {
            // Arrange: Write a single message
            String key = "test/single.pb";
            Int32Value originalMessage = Int32Value.of(9999);

            storage.writeMessage(key, originalMessage);

            // Act: Read using readMessage
            Int32Value readMessage = storage.readMessage(StoragePath.of(key + ".zst"), Int32Value.parser());

            // Assert
            assertThat(readMessage.getValue()).isEqualTo(originalMessage.getValue());
        }

        @Test
        @DisplayName("listKeys includes compressed files")
        void listKeysIncludesCompressedFiles() throws IOException {
            // Arrange: Write multiple compressed files
            storage.writeMessage("file1.pb", Int32Value.of(1));
            storage.writeMessage("file2.pb", Int32Value.of(2));

            // Act & Assert: Verify files exist with .zst extension by reading them back
            // (listKeys() removed in Step 1, will be replaced with paginated API in Step 2)
            // Files are written with .zst extension when compression is enabled
            Int32Value read1 = storage.readMessage(StoragePath.of("file1.pb.zst"), Int32Value.parser());
            Int32Value read2 = storage.readMessage(StoragePath.of("file2.pb.zst"), Int32Value.parser());

            assertThat(read1.getValue()).isEqualTo(1);
            assertThat(read2.getValue()).isEqualTo(2);
        }

        @Test
        @DisplayName("Metrics are tracked on write operations")
        void testMetricsTrackedOnWrite() throws IOException {
            // Arrange
            String key = "metrics/write-test.pb";
            Int32Value message = Int32Value.of(42);

            // Get initial metrics
            var initialMetrics = storage.getMetrics();
            long initialWrites = initialMetrics.containsKey("write_operations") ?
                initialMetrics.get("write_operations").longValue() : 0L;
            long initialBytes = initialMetrics.containsKey("bytes_written") ?
                initialMetrics.get("bytes_written").longValue() : 0L;

            // Act: Write message
            storage.writeMessage(key, message);

            // Assert: Metrics updated
            var finalMetrics = storage.getMetrics();
            long finalWrites = finalMetrics.get("write_operations").longValue();
            long finalBytes = finalMetrics.get("bytes_written").longValue();

            assertThat(finalWrites).isEqualTo(initialWrites + 1);
            assertThat(finalBytes).isGreaterThan(initialBytes);
        }

        @Test
        @DisplayName("Metrics are tracked on readMessage operations")
        void testMetricsTrackedOnReadMessage() throws IOException {
            // Arrange: Write a test file
            String key = "metrics/read-test.pb";
            Int32Value message = Int32Value.of(42);
            StoragePath physicalPath = storage.writeMessage(key, message);

            // Get initial metrics
            var initialMetrics = storage.getMetrics();
            long initialReads = initialMetrics.containsKey("read_operations") ?
                initialMetrics.get("read_operations").longValue() : 0L;
            long initialBytes = initialMetrics.containsKey("bytes_read") ?
                initialMetrics.get("bytes_read").longValue() : 0L;

            // Act: Read the message using physical path returned from write
            Int32Value readMessage = storage.readMessage(physicalPath, Int32Value.parser());

            // Assert: Metrics updated
            var finalMetrics = storage.getMetrics();
            long finalReads = finalMetrics.get("read_operations").longValue();
            long finalBytes = finalMetrics.get("bytes_read").longValue();

            assertThat(readMessage.getValue()).isEqualTo(42);
            assertThat(finalReads).isEqualTo(initialReads + 1);
            assertThat(finalBytes).isGreaterThan(initialBytes);
        }

        @Test
        @DisplayName("findMetadataPath finds compressed metadata files")
        void findMetadataPathFindsCompressedMetadata() throws IOException {
            // Arrange
            String runId = "test-sim-123";
            SimulationMetadata metadata = SimulationMetadata.newBuilder()
                    .setSimulationRunId(runId)
                    .setStartTimeMs(System.currentTimeMillis())
                    .setInitialSeed(42)
                    .build();
            
            // Act: Write metadata (will be compressed)
            String key = runId + "/raw/metadata.pb";
            StoragePath writtenPath = storage.writeMessage(key, metadata);
            
            // Assert: File exists with compression extension
            Path compressedFile = tempDir.resolve(writtenPath.asString());
            assertThat(compressedFile).exists();
            assertThat(compressedFile.toString()).contains(compressionExtension);
            
            // Act: Find metadata path (should find compressed file)
            java.util.Optional<StoragePath> foundPath = storage.findMetadataPath(runId);
            
            // Assert: Metadata path should be found and match written path
            assertThat(foundPath).isPresent();
            assertThat(foundPath.get().asString()).isEqualTo(writtenPath.asString());
            
            // Assert: Can read metadata back from found path
            SimulationMetadata readMetadata = storage.readMessage(foundPath.get(), SimulationMetadata.parser());
            assertThat(readMetadata.getSimulationRunId()).isEqualTo(runId);
        }
    }

    @Nested
    @DisplayName("Backward Compatibility Tests")
    class BackwardCompatibility {

        @Test
        @DisplayName("Uncompressed storage can read old files")
        void uncompressedStorageCanReadOldFiles() throws IOException {
            // Arrange: Create storage WITHOUT compression and write file
            Config uncompressedConfig = ConfigFactory.parseString(String.format("""
                rootDirectory = "%s"
                """, tempDir.toString().replace("\\", "\\\\")));
            FileSystemStorageResource uncompressedStorage =
                new FileSystemStorageResource("uncompressed", uncompressedConfig);

            String key = "old-file.pb";
            Int32Value originalMessage = Int32Value.of(12345);

            uncompressedStorage.writeMessage(key, originalMessage);

            // Act: Read back with same uncompressed storage
            Int32Value readMessage = uncompressedStorage.readMessage(StoragePath.of(key), Int32Value.parser());

            // Assert
            assertThat(readMessage.getValue()).isEqualTo(originalMessage.getValue());

            // Verify file doesn't have .zst extension
            Path file = tempDir.resolve(key);
            assertThat(file).exists();
            assertThat(file.toString()).doesNotContain(".zst");
        }

        @Test
        @DisplayName("Compressed storage can read legacy uncompressed files")
        void compressedStorageCanReadLegacyFiles() throws IOException {
            // Arrange: Write file WITHOUT compression
            Config uncompressedConfig = ConfigFactory.parseString(String.format("""
                rootDirectory = "%s"
                """, tempDir.toString().replace("\\", "\\\\")));
            FileSystemStorageResource uncompressedStorage =
                new FileSystemStorageResource("uncompressed", uncompressedConfig);

            String key = "legacy-file.pb";
            Int32Value originalMessage = Int32Value.of(67890);

            uncompressedStorage.writeMessage(key, originalMessage);

            // Act: Read with compressed storage (reading legacy uncompressed file)
            Config compressedConfig = ConfigFactory.parseString(String.format("""
                rootDirectory = "%s"
                compression {
                  enabled = true
                  codec = "zstd"
                  level = 3
                }
                """, tempDir.toString().replace("\\", "\\\\")));
            FileSystemStorageResource compressedStorage =
                new FileSystemStorageResource("compressed", compressedConfig);

            Int32Value readMessage = compressedStorage.readMessage(StoragePath.of(key), Int32Value.parser());

            // Assert: Can read legacy file correctly
            assertThat(readMessage.getValue()).isEqualTo(originalMessage.getValue());
        }
    }

    @Nested
    @DisplayName("Compression Configuration Tests")
    class CompressionConfiguration {

        @Test
        @DisplayName("Compression can be explicitly disabled")
        void compressionExplicitlyDisabled() throws IOException {
            // Arrange
            Config config = ConfigFactory.parseString(String.format("""
                rootDirectory = "%s"
                compression {
                  enabled = false
                }
                """, tempDir.toString().replace("\\", "\\\\")));
            FileSystemStorageResource storage = new FileSystemStorageResource("test", config);

            String key = "test.pb";
            Int32Value message = Int32Value.of(100);

            // Act: Write
            storage.writeMessage(key, message);

            // Assert: File has NO .zst extension
            Path file = tempDir.resolve(key);
            assertThat(file).exists();
            assertThat(file.toString()).doesNotContain(".zst");
        }

        @Test
        @DisplayName("Different compression levels work correctly")
        void differentCompressionLevels() throws IOException {
            // Level 1 (fast, lower ratio)
            Config configLevel1 = ConfigFactory.parseString(String.format("""
                rootDirectory = "%s"
                compression {
                  enabled = true
                  codec = "zstd"
                  level = 1
                }
                """, tempDir.resolve("level1").toString().replace("\\", "\\\\")));
            FileSystemStorageResource storageLevel1 = new FileSystemStorageResource("level1", configLevel1);

            // Level 9 (slower, better ratio)
            Config configLevel9 = ConfigFactory.parseString(String.format("""
                rootDirectory = "%s"
                compression {
                  enabled = true
                  codec = "zstd"
                  level = 9
                }
                """, tempDir.resolve("level9").toString().replace("\\", "\\\\")));
            FileSystemStorageResource storageLevel9 = new FileSystemStorageResource("level9", configLevel9);

            // Arrange: 50 chunks (testing compression ratio with meaningful data volume)
            List<TickDataChunk> batch = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                batch.add(createChunk(i * 10, i * 10 + 9, 10));
            }

            // Act: Write with both levels using writeChunkBatch
            StoragePath batchPath1 = storageLevel1.writeChunkBatch(batch, 0, 499);
            StoragePath batchPath9 = storageLevel9.writeChunkBatch(batch, 0, 499);

            // Assert: Both create valid compressed files
            // Note: writeChunkBatch() returns physical path including compression extension
            Path file1 = tempDir.resolve("level1/" + batchPath1.asString());
            Path file9 = tempDir.resolve("level9/" + batchPath9.asString());
            assertThat(file1).exists();
            assertThat(file9).exists();

            // Level 9 should produce smaller or equal size (with tolerance for dictionary overhead)
            long size1 = Files.size(file1);
            long size9 = Files.size(file9);
            // Allow 5% tolerance - at small data sizes, Level 9 dictionary overhead can dominate
            double tolerance = size1 * 0.05;
            assertThat((double) size9).isLessThanOrEqualTo(size1 + tolerance);

            // Both should be readable - verify all 50 chunks
            List<TickDataChunk> readBatch1 = storageLevel1.readChunkBatch(batchPath1);
            List<TickDataChunk> readBatch9 = storageLevel9.readChunkBatch(batchPath9);

            assertThat(readBatch1).hasSize(50);
            assertThat(readBatch9).hasSize(50);
            assertThat(readBatch1.get(0).getFirstTick()).isEqualTo(0);
            assertThat(readBatch9.get(0).getFirstTick()).isEqualTo(0);
        }
    }
}
