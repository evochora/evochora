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
}
