package org.evochora.datapipeline.utils.compression;

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
@DisplayName("NoneCodec Unit Tests (Null Object Pattern)")
class NoneCodecTest {

    @Nested
    @DisplayName("Pass-Through Behavior Tests")
    class PassThroughBehavior {

        @Test
        @DisplayName("wrapOutputStream returns same stream unchanged")
        void wrapOutputStream_returnsSameStream() throws Exception {
            // Arrange
            NoneCodec codec = new NoneCodec();
            ByteArrayOutputStream originalStream = new ByteArrayOutputStream();

            // Act
            OutputStream wrappedStream = codec.wrapOutputStream(originalStream);

            // Assert: Should return the exact same instance (reference equality)
            assertThat(wrappedStream).isSameAs(originalStream);
        }

        @Test
        @DisplayName("wrapInputStream returns same stream unchanged")
        void wrapInputStream_returnsSameStream() throws Exception {
            // Arrange
            NoneCodec codec = new NoneCodec();
            ByteArrayInputStream originalStream = new ByteArrayInputStream(new byte[0]);

            // Act
            InputStream wrappedStream = codec.wrapInputStream(originalStream);

            // Assert: Should return the exact same instance (reference equality)
            assertThat(wrappedStream).isSameAs(originalStream);
        }

        @Test
        @DisplayName("Data passes through unchanged")
        void data_passesThrough() throws Exception {
            // Arrange
            NoneCodec codec = new NoneCodec();
            String originalData = "The quick brown fox jumps over the lazy dog";
            byte[] originalBytes = originalData.getBytes(StandardCharsets.UTF_8);

            // Act: Write through codec
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (OutputStream wrapped = codec.wrapOutputStream(outputStream)) {
                wrapped.write(originalBytes);
            }
            byte[] writtenBytes = outputStream.toByteArray();

            // Assert: Output should be identical to input (no compression)
            assertThat(writtenBytes).isEqualTo(originalBytes);
            assertThat(writtenBytes.length).isEqualTo(originalBytes.length);
        }

        @Test
        @DisplayName("Round-trip preserves data without modification")
        void roundTrip_preservesData() throws Exception {
            // Arrange
            NoneCodec codec = new NoneCodec();
            String originalData = "Evochora simulation tick data";
            byte[] originalBytes = originalData.getBytes(StandardCharsets.UTF_8);

            // Act: Write
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (OutputStream writer = codec.wrapOutputStream(outputStream)) {
                writer.write(originalBytes);
            }

            // Act: Read
            ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
            ByteArrayOutputStream readBuffer = new ByteArrayOutputStream();
            try (InputStream reader = codec.wrapInputStream(inputStream)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = reader.read(buffer)) != -1) {
                    readBuffer.write(buffer, 0, bytesRead);
                }
            }

            // Assert: Should be identical
            assertThat(readBuffer.toByteArray()).isEqualTo(originalBytes);
            assertThat(new String(readBuffer.toByteArray(), StandardCharsets.UTF_8)).isEqualTo(originalData);
        }
    }

    @Nested
    @DisplayName("Metadata Tests")
    class MetadataTests {

        @Test
        @DisplayName("getName returns 'none'")
        void getName_returnsNone() {
            // Arrange
            NoneCodec codec = new NoneCodec();

            // Act & Assert
            assertThat(codec.getName()).isEqualTo("none");
        }

        @Test
        @DisplayName("getFileExtension returns empty string")
        void getFileExtension_returnsEmpty() {
            // Arrange
            NoneCodec codec = new NoneCodec();

            // Act & Assert
            assertThat(codec.getFileExtension()).isEmpty();
        }

        @Test
        @DisplayName("getLevel returns 0")
        void getLevel_returnsZero() {
            // Arrange
            NoneCodec codec = new NoneCodec();

            // Act & Assert
            assertThat(codec.getLevel()).isEqualTo(0);
        }

        @Test
        @DisplayName("toString provides useful debug information")
        void toString_providesDebugInfo() {
            // Arrange
            NoneCodec codec = new NoneCodec();

            // Act
            String str = codec.toString();

            // Assert: Should indicate no compression
            assertThat(str).contains("NoneCodec");
            assertThat(str).contains("none");
            assertThat(str).containsIgnoringCase("disabled");
        }
    }

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("validateEnvironment always succeeds")
        void validateEnvironment_alwaysSucceeds() {
            // Arrange
            NoneCodec codec = new NoneCodec();

            // Act & Assert: Should never throw (no dependencies to validate)
            assertThatNoException().isThrownBy(codec::validateEnvironment);
        }
    }

    @Nested
    @DisplayName("Null Object Pattern Tests")
    class NullObjectPatternTests {

        @Test
        @DisplayName("NoneCodec can be used anywhere ICompressionCodec is expected")
        void canBeUsedPolymorphically() {
            // Arrange: Use NoneCodec through interface
            ICompressionCodec codec = new NoneCodec();

            // Act & Assert: All methods should work without null checks
            assertThat(codec.getName()).isNotNull();
            assertThat(codec.getFileExtension()).isNotNull();
            assertThat(codec.getLevel()).isGreaterThanOrEqualTo(0);
            assertThatNoException().isThrownBy(codec::validateEnvironment);
        }

        @Test
        @DisplayName("Eliminates need for null checks in calling code")
        void eliminatesNullChecks() throws Exception {
            // This test demonstrates how NoneCodec eliminates defensive programming

            // Without Null Object Pattern (fragile):
            // ICompressionCodec codec = getCodec(); // might return null
            // if (codec != null) {
            //     stream = codec.wrapOutputStream(stream);
            // }

            // With Null Object Pattern (robust):
            ICompressionCodec codec = new NoneCodec(); // Never null
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            OutputStream wrapped = codec.wrapOutputStream(stream);

            // Assert: Always safe to call, no null checks needed
            assertThat(wrapped).isNotNull();
        }
    }
}
