package org.evochora.datapipeline.utils.compression;

/**
 * Exception thrown when compression codec operations fail.
 * <p>
 * This checked exception is used for:
 * <ul>
 *   <li>Environment validation failures (missing native libraries, incompatible platforms)</li>
 *   <li>Configuration errors (invalid compression levels, unknown codecs)</li>
 *   <li>Runtime compression/decompression failures (corrupted data, I/O errors)</li>
 * </ul>
 * <p>
 * <strong>Fail-Fast Design:</strong> Most compression exceptions should be thrown at
 * initialization time (during {@link ICompressionCodec#validateEnvironment()}) rather
 * than during stream operations, allowing early detection of configuration problems.
 */
public class CompressionException extends Exception {

    /**
     * Constructs a new compression exception with the specified detail message.
     *
     * @param message the detail message explaining the failure
     */
    public CompressionException(String message) {
        super(message);
    }

    /**
     * Constructs a new compression exception with the specified detail message and cause.
     *
     * @param message the detail message explaining the failure
     * @param cause the underlying cause of the failure
     */
    public CompressionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new compression exception with the specified cause.
     *
     * @param cause the underlying cause of the failure
     */
    public CompressionException(Throwable cause) {
        super(cause);
    }
}
