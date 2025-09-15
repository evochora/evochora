package org.evochora.server.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Utility class for compression operations with Magic Byte support.
 * Provides methods to compress/decompress data and detect compression format.
 */
public final class CompressionUtils {
    
    // Magic bytes for different compression algorithms
    private static final byte[] GZIP_MAGIC = "GZIP".getBytes(StandardCharsets.UTF_8);
    private static final int MAGIC_BYTE_LENGTH = 4;
    
    private CompressionUtils() {
        // Utility class - no instantiation
    }
    
    /**
     * Compresses a JSON string using gzip and adds magic bytes for format detection.
     * 
     * @param jsonString The JSON string to compress
     * @return Compressed data with magic bytes prepended
     * @throws IOException if compression fails
     */
    public static byte[] compressWithMagic(String jsonString) throws IOException {
        return compressWithMagic(jsonString, "gzip");
    }

    /**
     * Compresses a JSON string using the specified algorithm and adds magic bytes for format detection.
     * 
     * @param jsonString The JSON string to compress
     * @param algorithm The compression algorithm to use
     * @return Compressed data with magic bytes prepended
     * @throws IOException if compression fails
     */
    public static byte[] compressWithMagic(String jsonString, String algorithm) throws IOException {
        byte[] jsonBytes = jsonString.getBytes(StandardCharsets.UTF_8);
        byte[] compressed;
        
        switch (algorithm.toLowerCase()) {
            case "gzip":
                compressed = gzipCompress(jsonBytes);
                return addMagicBytes(compressed, GZIP_MAGIC);
            default:
                throw new IllegalArgumentException("Unsupported compression algorithm: " + algorithm);
        }
    }
    
    /**
     * Decompresses data if it has magic bytes, otherwise returns as string.
     * 
     * @param data The data to decompress
     * @return Decompressed JSON string
     * @throws IOException if decompression fails
     */
    public static String decompressIfNeeded(byte[] data) throws IOException {
        if (hasMagicBytes(data, GZIP_MAGIC)) {
            byte[] compressed = removeMagicBytes(data);
            byte[] decompressed = gzipDecompress(compressed);
            return new String(decompressed, StandardCharsets.UTF_8);
        } else {
            return new String(data, StandardCharsets.UTF_8);
        }
    }
    
    /**
     * Checks if data has the specified magic bytes.
     * 
     * @param data The data to check
     * @param magic The magic bytes to look for
     * @return true if magic bytes are found at the beginning
     */
    public static boolean hasMagicBytes(byte[] data, byte[] magic) {
        if (data == null || data.length < magic.length) {
            return false;
        }
        
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Adds magic bytes to the beginning of compressed data.
     * 
     * @param data The compressed data
     * @param magic The magic bytes to add
     * @return Data with magic bytes prepended
     */
    private static byte[] addMagicBytes(byte[] data, byte[] magic) {
        byte[] result = new byte[magic.length + data.length];
        System.arraycopy(magic, 0, result, 0, magic.length);
        System.arraycopy(data, 0, result, magic.length, data.length);
        return result;
    }
    
    /**
     * Removes magic bytes from the beginning of data.
     * 
     * @param data The data with magic bytes
     * @return Data without magic bytes
     */
    private static byte[] removeMagicBytes(byte[] data) {
        byte[] result = new byte[data.length - MAGIC_BYTE_LENGTH];
        System.arraycopy(data, MAGIC_BYTE_LENGTH, result, 0, result.length);
        return result;
    }
    
    /**
     * Compresses data using gzip.
     * 
     * @param data The data to compress
     * @return Compressed data
     * @throws IOException if compression fails
     */
    private static byte[] gzipCompress(byte[] data) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(data);
            gzipOut.finish();
            return baos.toByteArray();
        }
    }
    
    /**
     * Decompresses gzip-compressed data.
     * 
     * @param compressedData The compressed data
     * @return Decompressed data
     * @throws IOException if decompression fails
     */
    private static byte[] gzipDecompress(byte[] compressedData) throws IOException {
        try (GZIPInputStream gzipIn = new GZIPInputStream(new java.io.ByteArrayInputStream(compressedData));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipIn.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        }
    }
}
