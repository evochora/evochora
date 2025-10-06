package org.evochora.datapipeline.utils.compression;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.typesafe.config.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Zstandard (zstd) compression codec using zstd-jni.
 * <p>
 * Zstd provides excellent compression ratios with fast compression/decompression speeds,
 * making it ideal for real-time data pipelines. Measured on Evochora tick data:
 * <ul>
 *   <li>Compression ratio: 145-155x (14GB → 90MB)</li>
 *   <li>Compression speed: 400-500 MB/s at level 3</li>
 *   <li>Decompression speed: 1000+ MB/s</li>
 * </ul>
 * <p>
 * <strong>Compression Levels:</strong>
 * <ul>
 *   <li>Level 1: Fastest compression, lower ratio (~50-80x)</li>
 *   <li>Level 3: Recommended default, balanced speed/ratio (145-155x)</li>
 *   <li>Level 9: Slower compression, slightly better ratio (~160-180x)</li>
 *   <li>Level 19-22: Very slow, diminishing returns</li>
 * </ul>
 * <p>
 * <strong>Cross-Platform Support:</strong>
 * zstd-jni bundles native libraries for Windows, Linux, macOS, and Docker (glibc-based).
 * For Docker, use glibc-based images (ubuntu, debian, amazonlinux) instead of Alpine/musl.
 * <p>
 * <strong>Configuration Example:</strong>
 * <pre>
 * compression {
 *   enabled = true
 *   codec = "zstd"
 *   level = 3  # Optional, default: 3
 * }
 * </pre>
 *
 * @see ICompressionCodec
 * @see CompressionCodecFactory
 */
public class ZstdCodec implements ICompressionCodec {

    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 22;
    private static final int DEFAULT_LEVEL = 3;

    private final int level;

    /**
     * Creates a ZstdCodec with default compression level (3).
     */
    public ZstdCodec() {
        this.level = DEFAULT_LEVEL;
    }

    /**
     * Creates a ZstdCodec with configuration from Typesafe Config.
     * <p>
     * Reads compression level from {@code level} key, defaults to 3 if not specified.
     * Level is clamped to valid range [1, 22].
     *
     * @param config configuration containing optional {@code level} parameter
     */
    public ZstdCodec(Config config) {
        int configuredLevel = config.hasPath("level") ? config.getInt("level") : DEFAULT_LEVEL;
        this.level = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, configuredLevel));

        if (configuredLevel != this.level) {
            // Log warning if level was clamped (using System.err since we don't have logger here)
            System.err.printf("WARNING: Zstd compression level %d is outside valid range [%d, %d], " +
                "clamped to %d%n", configuredLevel, MIN_LEVEL, MAX_LEVEL, this.level);
        }
    }

    /**
     * Creates a ZstdCodec with explicit compression level.
     *
     * @param level compression level (1-22), will be clamped to valid range
     */
    public ZstdCodec(int level) {
        this.level = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    @Override
    public OutputStream wrapOutputStream(OutputStream out) throws IOException {
        return new ZstdOutputStream(out, level);
    }

    @Override
    public InputStream wrapInputStream(InputStream in) throws IOException {
        return new ZstdInputStream(in);
    }

    @Override
    public String getName() {
        return "zstd";
    }

    @Override
    public String getFileExtension() {
        return ".zst";
    }

    @Override
    public int getLevel() {
        return level;
    }

    /**
     * Validates that zstd-jni native library is loaded and functional.
     * <p>
     * This fail-fast check detects:
     * <ul>
     *   <li>Missing native libraries (wrong platform, corrupted installation)</li>
     *   <li>Incompatible Docker images (Alpine/musl instead of glibc)</li>
     *   <li>Native library loading failures</li>
     * </ul>
     * <p>
     * Called once at resource initialization, throws detailed error messages with
     * troubleshooting guidance.
     *
     * @throws CompressionException if zstd-jni cannot be loaded or is non-functional
     */
    @Override
    public void validateEnvironment() throws CompressionException {
        try {
            // Verify functionality with a simple compression test
            // This will fail with UnsatisfiedLinkError if native library isn't loaded
            String testData = "Evochora compression test";
            byte[] testBytes = testData.getBytes();
            byte[] compressed = Zstd.compress(testBytes, level);
            byte[] decompressed = Zstd.decompress(compressed, testBytes.length);

            if (!testData.equals(new String(decompressed))) {
                throw new CompressionException(
                    "Zstd library loaded but compression/decompression test failed. " +
                    "This indicates a corrupted or incompatible zstd-jni installation."
                );
            }

        } catch (UnsatisfiedLinkError e) {
            throw new CompressionException(
                "Zstd native library not found: " + e.getMessage() + "\n" +
                "This usually indicates:\n" +
                "  1. Platform mismatch (unsupported OS/architecture)\n" +
                "  2. Docker: Using Alpine/musl instead of glibc-based image (use ubuntu, debian, or amazonlinux)\n" +
                "  3. Corrupted zstd-jni installation\n" +
                "\nSupported platforms: Windows x64, Linux x64/ARM64, macOS x64/ARM64\n" +
                "If using Docker, ensure base image is glibc-based (NOT Alpine).\n" +
                "Ensure zstd-jni dependency is correctly included in build.gradle.kts:\n" +
                "  implementation(\"com.github.luben:zstd-jni:1.5.5-11\")",
                e
            );
        } catch (Exception e) {
            if (e instanceof CompressionException) {
                throw e;
            }
            throw new CompressionException(
                "Unexpected error during zstd environment validation: " + e.getMessage(),
                e
            );
        }
    }

    @Override
    public String toString() {
        return String.format("ZstdCodec{name='zstd', level=%d}", level);
    }
}
