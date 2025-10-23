package org.evochora.datapipeline.resources.database.h2;

import org.evochora.datapipeline.api.resources.database.SpatialRegion;
import org.evochora.datapipeline.utils.compression.CompressionCodecFactory;
import org.evochora.datapipeline.utils.compression.ICompressionCodec;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 unit tests: Strategy interface and data conversion
 * No database I/O - pure unit tests
 */
@Tag("unit")  // <0.2s runtime, no I/O
class StrategyUnitTest {
    
    @Test
    void spatialRegion_filtering2D() {
        SpatialRegion region = new SpatialRegion(new int[]{10, 20, 30, 40});
        
        // Test 2D filtering logic
        assertTrue(isInRegion2D(15, 35, region));  // Inside
        assertFalse(isInRegion2D(5, 35, region));   // X outside
        assertFalse(isInRegion2D(15, 25, region));  // Y outside
    }
    
    @Test
    void spatialRegion_filtering3D() {
        SpatialRegion region = new SpatialRegion(new int[]{10, 20, 30, 40, 50, 60});
        
        // Test 3D filtering logic
        assertTrue(isInRegion3D(15, 35, 55, region));  // Inside
        assertFalse(isInRegion3D(15, 35, 45, region)); // Z outside
    }
    
    @Test
    void cellStateToCoordinates_conversion() {
        // Test flatIndex to coordinates conversion
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, false);
        
        int flatIndex = 150;  // x=1, y=50 in 100x100 grid (row-major order)
        int[] coordinates = envProps.flatIndexToCoordinates(flatIndex);
        
        assertEquals(1, coordinates[0]);   // x
        assertEquals(50, coordinates[1]);  // y
        
        // Test another conversion: flatIndex = 0 should be (0,0)
        int[] coords0 = envProps.flatIndexToCoordinates(0);
        assertEquals(0, coords0[0]);  // x
        assertEquals(0, coords0[1]);  // y
        
        // Test another conversion: flatIndex = 99 should be (0,99)
        int[] coords99 = envProps.flatIndexToCoordinates(99);
        assertEquals(0, coords99[0]);  // x
        assertEquals(99, coords99[1]);  // y
        
        // Test another conversion: flatIndex = 100 should be (1,0)
        int[] coords100 = envProps.flatIndexToCoordinates(100);
        assertEquals(1, coords100[0]);  // x
        assertEquals(0, coords100[1]);  // y
    }
    
    @Test
    void compressionCodec_detection() {
        // Test compression detection from magic bytes
        byte[] zstdMagic = {(byte)0x28, (byte)0xB5, (byte)0x2F, (byte)0xFD};
        
        ICompressionCodec codec = CompressionCodecFactory.detectFromMagicBytes(zstdMagic);
        assertEquals("zstd", codec.getName());
        
        // Test uncompressed data
        byte[] noMagic = {1, 2, 3, 4};
        ICompressionCodec noneCodec = CompressionCodecFactory.detectFromMagicBytes(noMagic);
        assertEquals("none", noneCodec.getName());
    }
    
    private boolean isInRegion2D(int x, int y, SpatialRegion region) {
        int[] bounds = region.bounds;
        return x >= bounds[0] && x <= bounds[1] && y >= bounds[2] && y <= bounds[3];
    }
    
    private boolean isInRegion3D(int x, int y, int z, SpatialRegion region) {
        int[] bounds = region.bounds;
        return x >= bounds[0] && x <= bounds[1] && 
               y >= bounds[2] && y <= bounds[3] && 
               z >= bounds[4] && z <= bounds[5];
    }
}
