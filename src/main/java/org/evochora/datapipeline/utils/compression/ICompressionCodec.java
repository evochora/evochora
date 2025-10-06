package org.evochora.datapipeline.utils.compression;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Interface for stream-based compression codecs.
 * <p>
 * Compression codecs wrap input/output streams to transparently compress/decompress data.
 * This design works for both file I/O (storage) and network I/O (queues).
 * <p>
 * Implementations must be thread-safe for concurrent stream creation, though individual
 * streams are not required to be thread-safe.
 * <p>
 * <strong>Design Principles:</strong>
 * <ul>
 *   <li>Stream-based: Works with any InputStream/OutputStream (files, sockets, memory)</li>
 *   <li>Fail-fast: {@link #validateEnvironment()} checks dependencies at startup</li>
 *   <li>Stateless: Codec instances can be reused for multiple streams</li>
 *   <li>Null Object Pattern: {@link NoneCodec} eliminates null checks</li>
 * </ul>
 * <p>
 * <strong>Usage Pattern:</strong>
 * <pre>
 * // Compression (writing)
 * try (OutputStream raw = new FileOutputStream("data.pb.zst");
 *      OutputStream compressed = codec.wrapOutputStream(raw)) {
 *     message.writeTo(compressed);
 * }
 *
 * // Decompression (reading)
 * try (InputStream raw = new FileInputStream("data.pb.zst");
 *      InputStream decompressed = codec.wrapInputStream(raw)) {
 *     Message msg = Message.parseFrom(decompressed);
 * }
 * </pre>
 *
 * @see CompressionCodecFactory
 * @see NoneCodec
 * @see ZstdCodec
 */
public interface ICompressionCodec {

    /**
     * Wraps an output stream with compression.
     * <p>
     * The returned stream compresses data written to it and forwards compressed
     * data to the underlying output stream.
     * <p>
     * <strong>Important:</strong> Caller must close the returned stream to flush
     * any buffered data and finalize the compression format.
     *
     * @param out the underlying output stream to write compressed data to
     * @return a compressing output stream
     * @throws IOException if the stream cannot be created
     */
    OutputStream wrapOutputStream(OutputStream out) throws IOException;

    /**
     * Wraps an input stream with decompression.
     * <p>
     * The returned stream decompresses data from the underlying input stream
     * and provides uncompressed data to the caller.
     *
     * @param in the underlying input stream to read compressed data from
     * @return a decompressing input stream
     * @throws IOException if the stream cannot be created
     */
    InputStream wrapInputStream(InputStream in) throws IOException;

    /**
     * Returns the codec name for logging and configuration.
     * <p>
     * Examples: "zstd", "none", "gzip"
     *
     * @return the codec name (lowercase, no spaces)
     */
    String getName();

    /**
     * Returns the file extension for compressed files (storage only).
     * <p>
     * Used by storage implementations to generate filenames. Queues ignore this.
     * <p>
     * Examples:
     * <ul>
     *   <li>".zst" for zstd-compressed files (file.pb → file.pb.zst)</li>
     *   <li>"" for uncompressed files (no extension change)</li>
     * </ul>
     *
     * @return the file extension including leading dot, or empty string for none
     */
    String getFileExtension();

    /**
     * Returns the compression level (codec-specific meaning).
     * <p>
     * Higher levels typically mean better compression but slower speed.
     * For codecs without configurable levels (like NoneCodec), returns 0.
     * <p>
     * Zstd levels: 1 (fastest) to 22 (highest compression), default 3
     *
     * @return the compression level, or 0 if not applicable
     */
    int getLevel();

    /**
     * Validates that the codec's runtime environment is correct.
     * <p>
     * This method performs fail-fast validation at startup to detect:
     * <ul>
     *   <li>Missing native libraries (e.g., zstd-jni)</li>
     *   <li>Incompatible platform configurations</li>
     *   <li>Invalid compression levels or parameters</li>
     * </ul>
     * <p>
     * Implementations should throw {@link CompressionException} with clear error
     * messages explaining how to fix the problem.
     * <p>
     * Called once at resource initialization, not on every stream creation.
     *
     * @throws CompressionException if environment validation fails
     */
    void validateEnvironment() throws CompressionException;
}
