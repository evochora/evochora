package org.evochora.server.compression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CompressionUtils class.
 */
@Tag("unit")
public class CompressionUtilsTest {

    @Test
    public void testCompressWithMagic() throws Exception {
        String testJson = "{\"test\":\"data\",\"number\":123}";
        byte[] compressed = CompressionUtils.compressWithMagic(testJson);
        
        // Should have magic bytes at the beginning
        assertTrue(compressed.length > 4);
        assertTrue(CompressionUtils.hasMagicBytes(compressed, "GZIP".getBytes()));
    }

    @Test
    public void testCompressWithMagicWithAlgorithm() throws Exception {
        String testJson = "{\"test\":\"data\",\"number\":123}";
        byte[] compressed = CompressionUtils.compressWithMagic(testJson, "gzip");
        
        // Should have magic bytes at the beginning
        assertTrue(compressed.length > 4);
        assertTrue(CompressionUtils.hasMagicBytes(compressed, "GZIP".getBytes()));
    }

    @Test
    public void testCompressWithMagicUnsupportedAlgorithm() {
        String testJson = "{\"test\":\"data\",\"number\":123}";
        
        assertThrows(IllegalArgumentException.class, () -> {
            CompressionUtils.compressWithMagic(testJson, "brotli");
        });
    }

    @Test
    public void testDecompressIfNeeded() throws Exception {
        String testJson = "{\"test\":\"data\",\"number\":123}";
        byte[] compressed = CompressionUtils.compressWithMagic(testJson);
        
        String decompressed = CompressionUtils.decompressIfNeeded(compressed);
        assertEquals(testJson, decompressed);
    }

    @Test
    public void testDecompressIfNeededWithPlainData() throws Exception {
        String testJson = "{\"test\":\"data\",\"number\":123}";
        byte[] plainData = testJson.getBytes();
        
        String result = CompressionUtils.decompressIfNeeded(plainData);
        assertEquals(testJson, result);
    }

    @Test
    public void testHasMagicBytes() {
        byte[] dataWithMagic = "GZIPcompresseddata".getBytes();
        byte[] dataWithoutMagic = "plaindata".getBytes();
        byte[] magic = "GZIP".getBytes();
        
        assertTrue(CompressionUtils.hasMagicBytes(dataWithMagic, magic));
        assertFalse(CompressionUtils.hasMagicBytes(dataWithoutMagic, magic));
    }

    @Test
    public void testCompressionRatio() throws Exception {
        // Create a larger JSON string to test compression
        StringBuilder sb = new StringBuilder();
        sb.append("{\"cells\":[");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"position\":[").append(i).append(",").append(i).append("],\"type\":\"CODE\",\"value\":").append(i).append("}");
        }
        sb.append("]}");
        
        String largeJson = sb.toString();
        byte[] compressed = CompressionUtils.compressWithMagic(largeJson);
        
        // Should be significantly smaller
        assertTrue(compressed.length < largeJson.length() * 0.5);
        
        // Should decompress correctly
        String decompressed = CompressionUtils.decompressIfNeeded(compressed);
        assertEquals(largeJson, decompressed);
    }
}
