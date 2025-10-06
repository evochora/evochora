package org.evochora.datapipeline.utils.compression;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * No-op compression codec that passes data through unchanged.
 * <p>
 * This codec implements the Null Object Pattern, eliminating the need for null checks
 * throughout the codebase. When compression is disabled or not configured, NoneCodec
 * provides transparent pass-through behavior with zero overhead.
 * <p>
 * <strong>Use Cases:</strong>
 * <ul>
 *   <li>Configuration without compression section (backward compatibility)</li>
 *   <li>Explicit {@code compression.enabled = false}</li>
 *   <li>Testing and development scenarios</li>
 *   <li>Data that doesn't compress well (already compressed, encrypted)</li>
 * </ul>
 * <p>
 * <strong>Design Pattern:</strong>
 * <pre>
 * // Without Null Object Pattern (fragile):
 * if (codec != null) {
 *     stream = codec.wrapOutputStream(stream);
 * }
 *
 * // With Null Object Pattern (robust):
 * stream = codec.wrapOutputStream(stream);  // Always safe
 * </pre>
 *
 * @see ICompressionCodec
 * @see CompressionCodecFactory
 */
public class NoneCodec implements ICompressionCodec {

    /**
     * Returns the output stream unchanged (no compression).
     *
     * @param out the underlying output stream
     * @return the same output stream
     */
    @Override
    public OutputStream wrapOutputStream(OutputStream out) throws IOException {
        return out;
    }

    /**
     * Returns the input stream unchanged (no decompression).
     *
     * @param in the underlying input stream
     * @return the same input stream
     */
    @Override
    public InputStream wrapInputStream(InputStream in) throws IOException {
        return in;
    }

    @Override
    public String getName() {
        return "none";
    }

    @Override
    public String getFileExtension() {
        return "";  // No extension for uncompressed files
    }

    @Override
    public int getLevel() {
        return 0;  // No compression level
    }

    /**
     * Always succeeds - no environment dependencies for pass-through codec.
     */
    @Override
    public void validateEnvironment() throws CompressionException {
        // No validation needed for pass-through codec
    }

    @Override
    public String toString() {
        return "NoneCodec{name='none', compression=disabled}";
    }
}
