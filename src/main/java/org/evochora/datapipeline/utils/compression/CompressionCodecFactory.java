package org.evochora.datapipeline.utils.compression;

import com.typesafe.config.Config;

/**
 * Factory for creating compression codecs from Typesafe Config.
 * <p>
 * Supports three configuration scenarios for backward compatibility and explicit control:
 * <ol>
 *   <li><strong>No compression section:</strong> Returns {@link NoneCodec} (backward compatible)</li>
 *   <li><strong>Explicit disabled:</strong> {@code compression.enabled = false} → {@link NoneCodec}</li>
 *   <li><strong>Enabled with codec:</strong> {@code compression.enabled = true, codec = "zstd"} → {@link ZstdCodec}</li>
 * </ol>
 * <p>
 * <strong>Configuration Examples:</strong>
 * <pre>
 * # Scenario 1: No compression (backward compatible with existing configs)
 * # compression section missing entirely
 * storage {
 *   className = "org.evochora.datapipeline.resources.storage.FileSystemStorageResource"
 *   options {
 *     rootDirectory = "/data"
 *   }
 * }
 *
 * # Scenario 2: Explicitly disabled compression
 * storage {
 *   className = "org.evochora.datapipeline.resources.storage.FileSystemStorageResource"
 *   options {
 *     rootDirectory = "/data"
 *     compression {
 *       enabled = false
 *     }
 *   }
 * }
 *
 * # Scenario 3: Enabled with zstd codec (recommended for production)
 * storage {
 *   className = "org.evochora.datapipeline.resources.storage.FileSystemStorageResource"
 *   options {
 *     rootDirectory = "/data"
 *     compression {
 *       enabled = true
 *       codec = "zstd"
 *       level = 3  # Optional, default: 3
 *     }
 *   }
 * }
 * </pre>
 * <p>
 * <strong>Thread Safety:</strong> This factory is stateless and thread-safe.
 * Returned codec instances are also thread-safe for concurrent stream creation.
 *
 * @see ICompressionCodec
 * @see NoneCodec
 * @see ZstdCodec
 */
public class CompressionCodecFactory {

    /**
     * Creates a compression codec from configuration.
     * <p>
     * Configuration resolution logic:
     * <ol>
     *   <li>If {@code compression} section is missing → {@link NoneCodec}</li>
     *   <li>If {@code compression.enabled} is false or missing → {@link NoneCodec}</li>
     *   <li>If {@code compression.enabled} is true → create codec specified by {@code compression.codec}</li>
     * </ol>
     * <p>
     * When compression is enabled, the {@code codec} parameter is required and must be one of:
     * <ul>
     *   <li>"zstd" → {@link ZstdCodec} (recommended)</li>
     *   <li>"none" → {@link NoneCodec} (explicit no-op)</li>
     * </ul>
     *
     * @param config the configuration object (typically a resource's options config)
     * @return a compression codec instance (never null due to Null Object Pattern)
     * @throws IllegalArgumentException if compression is enabled but codec is invalid or missing
     */
    public static ICompressionCodec create(Config config) {
        // Scenario 1: No compression section → backward compatible default (NoneCodec)
        if (!config.hasPath("compression")) {
            return new NoneCodec();
        }

        Config compressionConfig = config.getConfig("compression");

        // Scenario 2: Explicitly disabled (compression.enabled = false or missing)
        // Note: If 'enabled' key is missing, we treat it as disabled (conservative default)
        boolean enabled = compressionConfig.hasPath("enabled")
            ? compressionConfig.getBoolean("enabled")
            : false;

        if (!enabled) {
            return new NoneCodec();
        }

        // Scenario 3: Compression is enabled - codec parameter is required
        if (!compressionConfig.hasPath("codec")) {
            throw new IllegalArgumentException(
                "Compression is enabled but 'codec' parameter is missing. " +
                "Specify a codec: compression { enabled = true, codec = \"zstd\" }"
            );
        }

        String codecName = compressionConfig.getString("codec").toLowerCase();

        return switch (codecName) {
            case "zstd" -> new ZstdCodec(compressionConfig);
            case "none" -> new NoneCodec();
            default -> throw new IllegalArgumentException(
                "Unknown compression codec: '" + codecName + "'. " +
                "Supported codecs: 'zstd', 'none'"
            );
        };
    }

    /**
     * Convenience method to create codec and validate environment in one step.
     * <p>
     * Equivalent to:
     * <pre>
     * ICompressionCodec codec = create(config);
     * codec.validateEnvironment();
     * return codec;
     * </pre>
     * <p>
     * Use this method at resource initialization to fail-fast if compression
     * environment is invalid (e.g., missing native libraries).
     *
     * @param config the configuration object
     * @return a validated compression codec instance
     * @throws IllegalArgumentException if codec configuration is invalid
     * @throws CompressionException if codec environment validation fails
     */
    public static ICompressionCodec createAndValidate(Config config) throws CompressionException {
        ICompressionCodec codec = create(config);
        codec.validateEnvironment();
        return codec;
    }

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private CompressionCodecFactory() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
