package org.evochora.datapipeline.api.resources.database.dto;

/**
 * Spatial region bounds for n-dimensional filtering.
 * <p>
 * Format: Interleaved min/max pairs per dimension:
 * - 2D: [min_x, max_x, min_y, max_y]
 * - 3D: [min_x, max_x, min_y, max_y, min_z, max_z]
 */
public class SpatialRegion {
    public final int[] bounds;
    
    public SpatialRegion(int[] bounds) {
        if (bounds.length % 2 != 0) {
            throw new IllegalArgumentException(
                "Region must have even number of values (min/max pairs)"
            );
        }
        this.bounds = bounds;
    }
    
    public int getDimensions() {
        return bounds.length / 2;
    }
}
