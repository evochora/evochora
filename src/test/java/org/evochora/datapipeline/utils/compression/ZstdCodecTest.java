package org.evochora.datapipeline.utils.compression;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

@Tag("unit")
@DisplayName("ZstdCodec Unit Tests")
class ZstdCodecTest {

    @Nested
    @DisplayName("Construction Tests")
    class ConstructionTests {

        @Test
        @DisplayName("Default constructor uses level 3")
        void defaultConstructor_usesLevel3() {
            // Act
            ZstdCodec codec = new ZstdCodec();

            // Assert
            assertThat(codec.getLevel()).isEqualTo(3);
            assertThat(codec.getName()).isEqualTo("zstd");
            assertThat(codec.getFileExtension()).isEqualTo(".zst");
        }

        @Test
        @DisplayName("Constructor with explicit level")
        void constructorWithLevel_usesSpecifiedLevel() {
            // Act
            ZstdCodec codec = new ZstdCodec(5);

            // Assert
            assertThat(codec.getLevel()).isEqualTo(5);
        }

        @Test
        @DisplayName("Constructor clamps level to valid range (too low)")
        void constructorClampsLevelTooLow() {
            // Act
            ZstdCodec codec = new ZstdCodec(0);

            // Assert: Level clamped to minimum (1)
            assertThat(codec.getLevel()).isEqualTo(1);
        }

        @Test
        @DisplayName("Constructor clamps level to valid range (too high)")
        void constructorClampsLevelTooHigh() {
            // Act
            ZstdCodec codec = new ZstdCodec(25);

            // Assert: Level clamped to maximum (22)
            assertThat(codec.getLevel()).isEqualTo(22);
        }

        @Test
        @DisplayName("Config constructor reads level from config")
        void configConstructor_readsLevel() {
            // Arrange
            Config config = ConfigFactory.parseString("level = 7");

            // Act
            ZstdCodec codec = new ZstdCodec(config);

            // Assert
            assertThat(codec.getLevel()).isEqualTo(7);
        }

        @Test
        @DisplayName("Config constructor uses default level 3 when not specified")
        void configConstructor_usesDefaultLevel() {
            // Arrange
            Config config = ConfigFactory.empty();

            // Act
            ZstdCodec codec = new ZstdCodec(config);

            // Assert
            assertThat(codec.getLevel()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Compression/Decompression Tests")
    class CompressionDecompressionTests {

        @Test
        @DisplayName("Round-trip compression preserves data")
        void roundTripCompression_preservesData() throws Exception {
            // Arrange
            ZstdCodec codec = new ZstdCodec();
            String originalData = "The quick brown fox jumps over the lazy dog. ".repeat(100);
            byte[] originalBytes = originalData.getBytes(StandardCharsets.UTF_8);

            // Act: Compress
            ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();
            try (OutputStream compressor = codec.wrapOutputStream(compressedOutput)) {
                compressor.write(originalBytes);
            }
            byte[] compressedBytes = compressedOutput.toByteArray();

            // Act: Decompress
            ByteArrayInputStream compressedInput = new ByteArrayInputStream(compressedBytes);
            ByteArrayOutputStream decompressedOutput = new ByteArrayOutputStream();
            try (InputStream decompressor = codec.wrapInputStream(compressedInput)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = decompressor.read(buffer)) != -1) {
                    decompressedOutput.write(buffer, 0, bytesRead);
                }
            }
            byte[] decompressedBytes = decompressedOutput.toByteArray();

            // Assert
            assertThat(decompressedBytes).isEqualTo(originalBytes);
            assertThat(new String(decompressedBytes, StandardCharsets.UTF_8)).isEqualTo(originalData);
        }

        @Test
        @DisplayName("Compression achieves size reduction")
        void compression_achievesSizeReduction() throws Exception {
            // Arrange: Repetitive data compresses well
            ZstdCodec codec = new ZstdCodec();
            String originalData = "AAAAAAAAAA".repeat(1000); // 10,000 bytes of 'A'
            byte[] originalBytes = originalData.getBytes(StandardCharsets.UTF_8);

            // Act: Compress
            ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();
            try (OutputStream compressor = codec.wrapOutputStream(compressedOutput)) {
                compressor.write(originalBytes);
            }
            byte[] compressedBytes = compressedOutput.toByteArray();

            // Assert: Compressed size should be much smaller (at least 10x reduction)
            int originalSize = originalBytes.length;
            int compressedSize = compressedBytes.length;
            double ratio = (double) originalSize / compressedSize;

            assertThat(compressedSize).isLessThan(originalSize);
            assertThat(ratio).isGreaterThan(10.0);
        }

        @Test
        @DisplayName("Higher compression level produces smaller output")
        void higherLevel_producesSmallerOutput() throws Exception {
            // Arrange: Compressible data
            String originalData = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(100);
            byte[] originalBytes = originalData.getBytes(StandardCharsets.UTF_8);

            // Act: Compress with level 1
            ZstdCodec codecLevel1 = new ZstdCodec(1);
            ByteArrayOutputStream outputLevel1 = new ByteArrayOutputStream();
            try (OutputStream compressor = codecLevel1.wrapOutputStream(outputLevel1)) {
                compressor.write(originalBytes);
            }

            // Act: Compress with level 9
            ZstdCodec codecLevel9 = new ZstdCodec(9);
            ByteArrayOutputStream outputLevel9 = new ByteArrayOutputStream();
            try (OutputStream compressor = codecLevel9.wrapOutputStream(outputLevel9)) {
                compressor.write(originalBytes);
            }

            // Assert: Level 9 should produce smaller output than level 1
            int sizeLevel1 = outputLevel1.size();
            int sizeLevel9 = outputLevel9.size();
            assertThat(sizeLevel9).isLessThanOrEqualTo(sizeLevel1);
        }

        @Test
        @DisplayName("Empty data compression works")
        void emptyData_compressesSuccessfully() throws Exception {
            // Arrange
            ZstdCodec codec = new ZstdCodec();
            byte[] emptyData = new byte[0];

            // Act: Compress
            ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();
            try (OutputStream compressor = codec.wrapOutputStream(compressedOutput)) {
                compressor.write(emptyData);
            }

            // Act: Decompress
            ByteArrayInputStream compressedInput = new ByteArrayInputStream(compressedOutput.toByteArray());
            ByteArrayOutputStream decompressedOutput = new ByteArrayOutputStream();
            try (InputStream decompressor = codec.wrapInputStream(compressedInput)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = decompressor.read(buffer)) != -1) {
                    decompressedOutput.write(buffer, 0, bytesRead);
                }
            }

            // Assert: Empty input produces empty output
            assertThat(decompressedOutput.toByteArray()).isEmpty();
        }

        @Test
        @DisplayName("Large data compression works")
        void largeData_compressesSuccessfully() throws Exception {
            // Arrange: 1MB of repetitive data (simulates tick data patterns)
            ZstdCodec codec = new ZstdCodec();
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 10000; i++) {
                builder.append("Tick ").append(i).append(": organism_id=123, energy=100, position=(50,50)\n");
            }
            byte[] originalBytes = builder.toString().getBytes(StandardCharsets.UTF_8);

            // Act: Compress
            ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();
            try (OutputStream compressor = codec.wrapOutputStream(compressedOutput)) {
                compressor.write(originalBytes);
            }
            byte[] compressedBytes = compressedOutput.toByteArray();

            // Act: Decompress
            ByteArrayInputStream compressedInput = new ByteArrayInputStream(compressedBytes);
            ByteArrayOutputStream decompressedOutput = new ByteArrayOutputStream();
            try (InputStream decompressor = codec.wrapInputStream(compressedInput)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = decompressor.read(buffer)) != -1) {
                    decompressedOutput.write(buffer, 0, bytesRead);
                }
            }

            // Assert: Data preserved and highly compressed
            assertThat(decompressedOutput.toByteArray()).isEqualTo(originalBytes);
            double ratio = (double) originalBytes.length / compressedBytes.length;
            assertThat(ratio).isGreaterThan(5.0); // Should achieve at least 5x compression
        }
    }

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("validateEnvironment succeeds when zstd-jni is available")
        void validateEnvironment_succeeds() {
            // Arrange
            ZstdCodec codec = new ZstdCodec();

            // Act & Assert: Should not throw
            assertThatNoException().isThrownBy(codec::validateEnvironment);
        }

        @Test
        @DisplayName("validateEnvironment performs functional test")
        void validateEnvironment_performsFunctionalTest() throws CompressionException {
            // Arrange
            ZstdCodec codec = new ZstdCodec();

            // Act: Validation includes actual compression/decompression test
            codec.validateEnvironment();

            // Assert: If we got here, validation passed (functional test succeeded)
            assertThat(codec.getName()).isEqualTo("zstd");
        }
    }

    @Nested
    @DisplayName("Metadata Tests")
    class MetadataTests {

        @Test
        @DisplayName("getName returns 'zstd'")
        void getName_returnsZstd() {
            // Arrange
            ZstdCodec codec = new ZstdCodec();

            // Act & Assert
            assertThat(codec.getName()).isEqualTo("zstd");
        }

        @Test
        @DisplayName("getFileExtension returns '.zst'")
        void getFileExtension_returnsZst() {
            // Arrange
            ZstdCodec codec = new ZstdCodec();

            // Act & Assert
            assertThat(codec.getFileExtension()).isEqualTo(".zst");
        }

        @Test
        @DisplayName("toString provides useful debug information")
        void toString_providesDebugInfo() {
            // Arrange
            ZstdCodec codec = new ZstdCodec(5);

            // Act
            String str = codec.toString();

            // Assert: Should contain codec name and level
            assertThat(str).contains("ZstdCodec");
            assertThat(str).contains("zstd");
            assertThat(str).contains("5");
        }
    }
}
