package org.evochora.datapipeline.resources.database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.evochora.datapipeline.api.resources.database.dto.SpatialRegion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Phase 1 unit tests: Interface contracts and data classes
 * No database I/O - pure unit tests
 */
@Tag("unit")  // <0.2s runtime, no I/O
class DatabaseReaderUnitTest {
    
    @Test
    void spatialRegion_constructsCorrectly() {
        int[] bounds = {0, 50, 0, 50};  // 2D: x:[0,50], y:[0,50]
        SpatialRegion region = new SpatialRegion(bounds);
        
        assertEquals(2, region.getDimensions());
        assertArrayEquals(bounds, region.bounds);
    }
    
    @Test
    void spatialRegion_throwsOnInvalidBounds() {
        int[] invalidBounds = {0, 50, 0};  // Odd number of values
        
        assertThrows(IllegalArgumentException.class, () -> 
            new SpatialRegion(invalidBounds)
        );
    }
    
    @Test
    void spatialRegion_constructs3D() {
        int[] bounds = {0, 100, 0, 100, 0, 50};  // 3D: x:[0,100], y:[0,100], z:[0,50]
        SpatialRegion region = new SpatialRegion(bounds);
        
        assertEquals(3, region.getDimensions());
        assertArrayEquals(bounds, region.bounds);
    }
    
    @Test
    void spatialRegion_serializesCorrectly() throws Exception {
        SpatialRegion region = new SpatialRegion(new int[]{0, 50, 0, 50});
        
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(region);
        
        assertTrue(json.contains("\"bounds\":[0,50,0,50]"));
    }
}
