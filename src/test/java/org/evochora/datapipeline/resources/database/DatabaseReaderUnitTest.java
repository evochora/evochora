package org.evochora.datapipeline.resources.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.datapipeline.api.resources.database.CellWithCoordinates;
import org.evochora.datapipeline.api.resources.database.SpatialRegion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
    void cellWithCoordinates_serializesCorrectly() throws Exception {
        CellWithCoordinates cell = new CellWithCoordinates(
            new int[]{5, 10}, "DATA", 255, 7, null
        );
        
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(cell);
        
        assertTrue(json.contains("\"coordinates\":[5,10]"));
        assertTrue(json.contains("\"moleculeType\":\"DATA\""));
        assertTrue(json.contains("\"moleculeValue\":255"));
        assertTrue(json.contains("\"ownerId\":7"));
    }
    
    @Test
    void cellWithCoordinates_deserializesCorrectly() throws Exception {
        String json = "{\"coordinates\":[5,10],\"moleculeType\":\"DATA\",\"moleculeValue\":255,\"ownerId\":7,\"opcodeName\":null}";
        
        ObjectMapper mapper = new ObjectMapper();
        CellWithCoordinates cell = mapper.readValue(json, CellWithCoordinates.class);
        
        assertArrayEquals(new int[]{5, 10}, cell.coordinates());
        assertEquals("DATA", cell.moleculeType());
        assertEquals(255, cell.moleculeValue());
        assertEquals(7, cell.ownerId());
        assertNull(cell.opcodeName());
    }
    
    @Test
    void cellWithCoordinates_handles3DCoordinates() throws Exception {
        CellWithCoordinates cell = new CellWithCoordinates(
            new int[]{5, 10, 15}, "ENERGY", 128, 42, null
        );
        
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(cell);
        
        assertTrue(json.contains("\"coordinates\":[5,10,15]"));
        assertEquals("ENERGY", cell.moleculeType());
        assertEquals(128, cell.moleculeValue());
        assertEquals(42, cell.ownerId());
        assertNull(cell.opcodeName());
    }
}
