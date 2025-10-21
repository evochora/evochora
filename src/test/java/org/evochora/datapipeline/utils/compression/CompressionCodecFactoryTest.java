package org.evochora.datapipeline.utils.compression;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
@DisplayName("CompressionCodecFactory Unit Tests")
class CompressionCodecFactoryTest {

    @Nested
    @DisplayName("Configuration Scenario Tests")
    class ConfigurationScenarios {

        @Test
        @DisplayName("Scenario 1: Missing compression section returns NoneCodec")
        void missingCompressionSection_returnsNoneCodec() {
            // Arrange: Config without compression section (backward compatible)
            Config config = ConfigFactory.parseString("""
                rootDirectory = "/data"
                someOtherOption = 123
                """);

            // Act
            ICompressionCodec codec = CompressionCodecFactory.create(config);

            // Assert
            assertThat(codec).isInstanceOf(NoneCodec.class);
            assertThat(codec.getName()).isEqualTo("none");
            assertThat(codec.getFileExtension()).isEmpty();
            assertThat(codec.getLevel()).isEqualTo(0);
        }

        @Test
        @DisplayName("Scenario 2a: Explicit enabled=false returns NoneCodec")
        void explicitDisabled_returnsNoneCodec() {
            // Arrange: Compression explicitly disabled
            Config config = ConfigFactory.parseString("""
                rootDirectory = "/data"
                compression {
                  enabled = false
                }
                """);

            // Act
            ICompressionCodec codec = CompressionCodecFactory.create(config);

            // Assert
            assertThat(codec).isInstanceOf(NoneCodec.class);
            assertThat(codec.getName()).isEqualTo("none");
        }

        @Test
        @DisplayName("Scenario 2b: Missing enabled key returns NoneCodec (conservative default)")
        void missingEnabledKey_returnsNoneCodec() {
            // Arrange: Compression section exists but 'enabled' key is missing
            Config config = ConfigFactory.parseString("""
                rootDirectory = "/data"
                compression {
                  someOtherKey = "value"
                }
                """);

            // Act
            ICompressionCodec codec = CompressionCodecFactory.create(config);

            // Assert: Conservative default is disabled
            assertThat(codec).isInstanceOf(NoneCodec.class);
        }

        @Test
        @DisplayName("Scenario 3a: Enabled with codec=zstd returns ZstdCodec")
        void enabledWithZstd_returnsZstdCodec() {
            // Arrange: Compression enabled with zstd codec
            Config config = ConfigFactory.parseString("""
                rootDirectory = "/data"
                compression {
                  enabled = true
                  codec = "zstd"
                  level = 5
                }
                """);

            // Act
            ICompressionCodec codec = CompressionCodecFactory.create(config);

            // Assert
            assertThat(codec).isInstanceOf(ZstdCodec.class);
            assertThat(codec.getName()).isEqualTo("zstd");
            assertThat(codec.getFileExtension()).isEqualTo(".zst");
            assertThat(codec.getLevel()).isEqualTo(5);
        }

        @Test
        @DisplayName("Scenario 3b: Enabled with codec=none returns NoneCodec (explicit)")
        void enabledWithNoneCodec_returnsNoneCodec() {
            // Arrange: Compression enabled but explicitly set to "none"
            Config config = ConfigFactory.parseString("""
                compression {
                  enabled = true
                  codec = "none"
                }
                """);

            // Act
            ICompressionCodec codec = CompressionCodecFactory.create(config);

            // Assert
            assertThat(codec).isInstanceOf(NoneCodec.class);
        }

        @Test
        @DisplayName("Scenario 3c: ZstdCodec uses default level 3 when not specified")
        void zstdWithoutLevel_usesDefaultLevel() {
            // Arrange: Zstd without explicit level
            Config config = ConfigFactory.parseString("""
                compression {
                  enabled = true
                  codec = "zstd"
                }
                """);

            // Act
            ICompressionCodec codec = CompressionCodecFactory.create(config);

            // Assert
            assertThat(codec).isInstanceOf(ZstdCodec.class);
            assertThat(codec.getLevel()).isEqualTo(3); // Default level
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandling {

        @Test
        @DisplayName("Enabled without codec parameter throws IllegalArgumentException")
        void enabledWithoutCodec_throwsException() {
            // Arrange: Compression enabled but codec parameter missing
            Config config = ConfigFactory.parseString("""
                compression {
                  enabled = true
                }
                """);

            // Act & Assert
            assertThatThrownBy(() -> CompressionCodecFactory.create(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Compression is enabled but 'codec' parameter is missing");
        }

        @Test
        @DisplayName("Unknown codec name throws IllegalArgumentException")
        void unknownCodec_throwsException() {
            // Arrange: Invalid codec name
            Config config = ConfigFactory.parseString("""
                compression {
                  enabled = true
                  codec = "invalid-codec"
                }
                """);

            // Act & Assert
            assertThatThrownBy(() -> CompressionCodecFactory.create(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown compression codec: 'invalid-codec'")
                .hasMessageContaining("Supported codecs: 'zstd', 'none'");
        }

        @Test
        @DisplayName("Codec name is case-insensitive")
        void codecNameCaseInsensitive() {
            // Arrange: Codec name with mixed case
            Config config = ConfigFactory.parseString("""
                compression {
                  enabled = true
                  codec = "ZSTD"
                }
                """);

            // Act
            ICompressionCodec codec = CompressionCodecFactory.create(config);

            // Assert: Should work regardless of case
            assertThat(codec).isInstanceOf(ZstdCodec.class);
            assertThat(codec.getName()).isEqualTo("zstd");
        }
    }

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("createAndValidate returns validated codec for NoneCodec")
        void createAndValidate_noneCodec_succeeds() throws CompressionException {
            // Arrange: Config without compression (NoneCodec)
            Config config = ConfigFactory.parseString("rootDirectory = \"/data\"");

            // Act
            ICompressionCodec codec = CompressionCodecFactory.createAndValidate(config);

            // Assert: Should succeed (NoneCodec has no validation requirements)
            assertThat(codec).isInstanceOf(NoneCodec.class);
        }

        @Test
        @DisplayName("createAndValidate returns validated codec for ZstdCodec")
        void createAndValidate_zstdCodec_succeeds() throws CompressionException {
            // Arrange: Config with zstd compression
            Config config = ConfigFactory.parseString("""
                compression {
                  enabled = true
                  codec = "zstd"
                  level = 3
                }
                """);

            // Act
            ICompressionCodec codec = CompressionCodecFactory.createAndValidate(config);

            // Assert: Should succeed if zstd-jni native library is available
            assertThat(codec).isInstanceOf(ZstdCodec.class);
            assertThat(codec.getName()).isEqualTo("zstd");
        }
    }

    @Nested
    @DisplayName("Magic Byte Detection Tests")
    class MagicByteDetection {

        @Test
        @DisplayName("ZSTD magic bytes are detected correctly")
        void zstdMagicBytes_returnsZstdCodec() {
            // Arrange: Data starting with ZSTD magic bytes
            byte[] zstdData = new byte[]{
                0x28, (byte)0xB5, 0x2F, (byte)0xFD,  // ZSTD magic
                0x01, 0x02, 0x03  // Dummy compressed data
            };

            // Act
            ICompressionCodec codec = CompressionCodecFactory.detectFromMagicBytes(zstdData);

            // Assert
            assertThat(codec).isInstanceOf(ZstdCodec.class);
            assertThat(codec.getName()).isEqualTo("zstd");
        }

        @Test
        @DisplayName("Uncompressed data returns NoneCodec")
        void noMagicBytes_returnsNoneCodec() {
            // Arrange: Random data without magic bytes
            byte[] uncompressedData = new byte[]{
                0x00, 0x01, 0x02, 0x03, 0x04
            };

            // Act
            ICompressionCodec codec = CompressionCodecFactory.detectFromMagicBytes(uncompressedData);

            // Assert
            assertThat(codec).isInstanceOf(NoneCodec.class);
        }

        @Test
        @DisplayName("Null data returns NoneCodec")
        void nullData_returnsNoneCodec() {
            // Act
            ICompressionCodec codec = CompressionCodecFactory.detectFromMagicBytes(null);

            // Assert
            assertThat(codec).isInstanceOf(NoneCodec.class);
        }

        @Test
        @DisplayName("Data shorter than 4 bytes returns NoneCodec")
        void shortData_returnsNoneCodec() {
            // Arrange: Data with only 3 bytes (not enough for ZSTD magic)
            byte[] shortData = new byte[]{0x28, (byte)0xB5, 0x2F};

            // Act
            ICompressionCodec codec = CompressionCodecFactory.detectFromMagicBytes(shortData);

            // Assert
            assertThat(codec).isInstanceOf(NoneCodec.class);
        }

        @Test
        @DisplayName("Partial ZSTD magic bytes return NoneCodec")
        void partialMagicBytes_returnsNoneCodec() {
            // Arrange: Data starting with partial ZSTD magic (only first 2 bytes match)
            byte[] partialMagic = new byte[]{
                0x28, (byte)0xB5, 0x00, 0x00  // First 2 match, last 2 don't
            };

            // Act
            ICompressionCodec codec = CompressionCodecFactory.detectFromMagicBytes(partialMagic);

            // Assert
            assertThat(codec).isInstanceOf(NoneCodec.class);
        }
    }
}
