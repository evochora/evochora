package org.evochora.datapipeline.resources.storage;

import com.google.protobuf.Int32Value;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@DisplayName("FileSystemStorageResource Compression Integration Tests")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.INFO, messagePattern = ".*using compression.*")
class FileSystemStorageResourceCompressionTest {

    @TempDir
    Path tempDir;

    // Helper to create TickData for batch testing
    private TickData createTick(long tickNumber) {
        return TickData.newBuilder()
            .setTickNumber(tickNumber)
            .setSimulationRunId("test-sim")
            .setCaptureTimeMs(System.currentTimeMillis())
            .build();
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
            // Arrange: Repetitive data (compresses well) - use TickData batches
            List<TickData> batch = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                batch.add(createTick(42)); // Same tick repeated
            }

            // Act: Write compressed batch
            StoragePath batchPath = storage.writeBatch(batch, 42, 42);

            // Assert: Compressed file is significantly smaller than uncompressed would be
            // Note: writeBatch() now returns physical path including compression extension
            Path compressedFile = tempDir.resolve(batchPath.asString());
            long compressedSize = Files.size(compressedFile);

            // Each TickData message when delimited is ~30-40 bytes, so 1000 messages ~= 35000 bytes
            long estimatedUncompressedSize = 35000;

            // Compression should achieve at least 2x ratio for this repetitive data
            double ratio = (double) estimatedUncompressedSize / compressedSize;
            assertThat(ratio).isGreaterThan(2.0);
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

            // Determine compression extension dynamically (future-proof for gzip, lz4, etc.)
            String ext = org.evochora.datapipeline.utils.compression.CompressionCodecFactory
                .create(configLevel1).getFileExtension();

            // Arrange: 500 TickData messages (testing compression ratio with meaningful data volume)
            List<TickData> batch = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                batch.add(createTick(42));
            }

            // Act: Write with both levels using writeBatch
            StoragePath batchPath1 = storageLevel1.writeBatch(batch, 42, 42);
            StoragePath batchPath9 = storageLevel9.writeBatch(batch, 42, 42);

            // Assert: Both create valid compressed files
            // Note: writeBatch() now returns physical path including compression extension
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

            // Both should be readable - verify all 500 messages
            List<TickData> readBatch1 = storageLevel1.readBatch(batchPath1);
            List<TickData> readBatch9 = storageLevel9.readBatch(batchPath9);

            assertThat(readBatch1).hasSize(500);
            assertThat(readBatch9).hasSize(500);
            assertThat(readBatch1.get(0).getTickNumber()).isEqualTo(42);
            assertThat(readBatch9.get(0).getTickNumber()).isEqualTo(42);
        }
    }
}
