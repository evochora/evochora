package org.evochora.datapipeline.resources.storage;

import com.google.protobuf.Int32Value;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.resources.storage.MessageReader;
import org.evochora.datapipeline.api.resources.storage.MessageWriter;
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

    @Nested
    @DisplayName("Compression Enabled Tests")
    class CompressionEnabled {

        private FileSystemStorageResource storage;

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
        }

        @Test
        @DisplayName("Writing creates .zst compressed files")
        void writingCreatesCompressedFiles() throws IOException {
            // Arrange
            String key = "test/data.pb";
            Int32Value message = Int32Value.of(42);

            // Act: Write message
            try (MessageWriter writer = storage.openWriter(key)) {
                writer.writeMessage(message);
            }

            // Assert: File exists with .zst extension
            Path compressedFile = tempDir.resolve("test/data.pb.zst");
            assertThat(compressedFile).exists();

            // Assert: Original key without .zst should NOT exist
            Path uncompressedFile = tempDir.resolve("test/data.pb");
            assertThat(uncompressedFile).doesNotExist();
        }

        @Test
        @DisplayName("Round-trip preserves data integrity")
        void roundTripPreservesData() throws IOException {
            // Arrange: Multiple messages
            String key = "test/messages.pb";
            List<Int32Value> originalMessages = List.of(
                Int32Value.of(1),
                Int32Value.of(42),
                Int32Value.of(12345),
                Int32Value.of(-999)
            );

            // Act: Write messages
            try (MessageWriter writer = storage.openWriter(key)) {
                for (Int32Value msg : originalMessages) {
                    writer.writeMessage(msg);
                }
            }

            // Act: Read messages back
            List<Int32Value> readMessages = new ArrayList<>();
            try (MessageReader<Int32Value> reader = storage.openReader(key + ".zst", Int32Value.parser())) {
                while (reader.hasNext()) {
                    readMessages.add(reader.next());
                }
            }

            // Assert: Data preserved
            assertThat(readMessages).hasSize(originalMessages.size());
            for (int i = 0; i < originalMessages.size(); i++) {
                assertThat(readMessages.get(i).getValue()).isEqualTo(originalMessages.get(i).getValue());
            }
        }

        @Test
        @DisplayName("Compression achieves size reduction")
        void compressionAchievesSizeReduction() throws IOException {
            // Arrange: Repetitive data (compresses well)
            String key = "test/repetitive.pb";
            List<Int32Value> messages = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                messages.add(Int32Value.of(42)); // Same value repeated
            }

            // Act: Write compressed
            try (MessageWriter writer = storage.openWriter(key)) {
                for (Int32Value msg : messages) {
                    writer.writeMessage(msg);
                }
            }

            // Assert: Compressed file is significantly smaller than uncompressed would be
            Path compressedFile = tempDir.resolve(key + ".zst");
            long compressedSize = Files.size(compressedFile);

            // Each Int32Value message when delimited is ~3 bytes, so 1000 messages ~= 3000 bytes
            long estimatedUncompressedSize = 3000;

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

            try (MessageWriter writer = storage.openWriter(key)) {
                writer.writeMessage(originalMessage);
            }

            // Act: Read using readMessage
            Int32Value readMessage = storage.readMessage(key + ".zst", Int32Value.parser());

            // Assert
            assertThat(readMessage.getValue()).isEqualTo(originalMessage.getValue());
        }

        @Test
        @DisplayName("listKeys includes compressed files")
        void listKeysIncludesCompressedFiles() throws IOException {
            // Arrange: Write multiple compressed files
            try (MessageWriter w1 = storage.openWriter("file1.pb")) {
                w1.writeMessage(Int32Value.of(1));
            }
            try (MessageWriter w2 = storage.openWriter("file2.pb")) {
                w2.writeMessage(Int32Value.of(2));
            }

            // Act
            List<String> keys = storage.listKeys("");

            // Assert: Keys include .zst extension
            assertThat(keys).containsExactlyInAnyOrder("file1.pb.zst", "file2.pb.zst");
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

            try (MessageWriter writer = uncompressedStorage.openWriter(key)) {
                writer.writeMessage(originalMessage);
            }

            // Act: Read back with same uncompressed storage
            Int32Value readMessage = uncompressedStorage.readMessage(key, Int32Value.parser());

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

            try (MessageWriter writer = uncompressedStorage.openWriter(key)) {
                writer.writeMessage(originalMessage);
            }

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

            Int32Value readMessage = compressedStorage.readMessage(key, Int32Value.parser());

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
            try (MessageWriter writer = storage.openWriter(key)) {
                writer.writeMessage(message);
            }

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

            // Arrange: Repetitive data
            String key = "data.pb";
            List<Int32Value> messages = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                messages.add(Int32Value.of(42));
            }

            // Act: Write with both levels
            try (MessageWriter w1 = storageLevel1.openWriter(key)) {
                for (Int32Value msg : messages) {
                    w1.writeMessage(msg);
                }
            }
            try (MessageWriter w9 = storageLevel9.openWriter(key)) {
                for (Int32Value msg : messages) {
                    w9.writeMessage(msg);
                }
            }

            // Assert: Both create valid compressed files
            Path file1 = tempDir.resolve("level1/" + key + ".zst");
            Path file9 = tempDir.resolve("level9/" + key + ".zst");
            assertThat(file1).exists();
            assertThat(file9).exists();

            // Level 9 should produce smaller or equal size
            long size1 = Files.size(file1);
            long size9 = Files.size(file9);
            assertThat(size9).isLessThanOrEqualTo(size1);

            // Both should be readable
            Int32Value msg1 = storageLevel1.readMessage(key + ".zst", Int32Value.parser());
            Int32Value msg9 = storageLevel9.readMessage(key + ".zst", Int32Value.parser());
            assertThat(msg1.getValue()).isEqualTo(42);
            assertThat(msg9.getValue()).isEqualTo(42);
        }
    }
}
