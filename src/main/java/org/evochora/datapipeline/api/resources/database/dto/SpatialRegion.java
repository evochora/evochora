package org.evochora.datapipeline.api.resources.database.dto;

/**
 * Spatial region bounds for n-dimensional filtering.
 * <p>
 * Format: Interleaved min/max pairs per dimension:
 * - 2D: [min_x, max_x, min_y, max_y]
 * - 3D: [min_x, max_x, min_y, max_y, min_z, max_z]
 */
public class SpatialRegion {
    /**
     * The interleaved min/max pairs described above. Stored as given, not copied, and its length
     * is the only place the region's dimensionality is recorded. Both ends are the caller's to
     * interpret; this class checks neither ordering nor environment bounds.
     */
    public final int[] bounds;
    
    /**
     * Creates a region from interleaved min/max pairs.
     *
     * @param bounds The interleaved min/max pairs, two per dimension.
     * @throws IllegalArgumentException if the array holds an odd number of values, which cannot
     *         form complete min/max pairs.
     */
    public SpatialRegion(int[] bounds) {
        if (bounds.length % 2 != 0) {
            throw new IllegalArgumentException(
                "Region must have even number of values (min/max pairs)"
            );
        }
        this.bounds = bounds;
    }
    
    /**
     * Derives the region's dimensionality from the number of min/max pairs it holds. A caller
     * uses it to check the region against the dimensionality of the environment it filters.
     *
     * @return Half the length of {@link #bounds}.
     */
    public int getDimensions() {
        return bounds.length / 2;
    }
}
