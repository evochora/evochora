package org.evochora.compiler.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.evochora.runtime.model.EnvironmentProperties;

/**
 * Utility class for converting int[] coordinates into the linearized Integer keys that Jackson
 * can write, because a JSON object key must be a string and an array is not one.
 * 
 * <h2>Usage</h2>
 * <pre>{@code
 * // 2D world with shape [100, 100] and toroidal=true
 * EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
 * CoordinateConverter converter = new CoordinateConverter(envProps);
 * 
 * // Coordinate [5, 10] to linearized index
 * int[] coord = {5, 10};
 * int flatIndex = converter.linearizeCoordinate(coord); // = 5*100 + 10 = 510
 * }</pre>
 * 
 * <h2>Stride Calculation</h2>
 * The strides come from {@link EnvironmentProperties}, so a linearized artifact uses the same
 * row-major convention as the running environment: the last dimension has stride 1. For a world
 * shape [W, H, D] that gives:
 * <ul>
 *   <li>Stride[0] = H * D</li>
 *   <li>Stride[1] = D</li>
 *   <li>Stride[2] = 1</li>
 * </ul>
 * 
 * Linearization follows the formula: {@code coord[0]*stride[0] + coord[1]*stride[1] + coord[2]}
 * 
 * @see LinearizedProgramArtifact
 * @see org.evochora.compiler.api.ProgramArtifact#toLinearized(org.evochora.runtime.model.EnvironmentProperties)
 * @since 1.0
 */
public class CoordinateConverter {
    private final EnvironmentProperties envProps;
    
    /**
     * Creates a converter for one world geometry. The world shape of the given properties supplies the
     * strides of every conversion, so a converter fits only artifacts laid out in a world of that
     * shape, and the toroidal flag decides whether out-of-range coordinates are wrapped or rejected.
     * The properties are kept by reference and not copied.
     *
     * @param envProps The environment properties providing world shape and toroidal flag.
     * @throws IllegalArgumentException if {@code envProps} is null.
     */
    public CoordinateConverter(EnvironmentProperties envProps) {
        if (envProps == null) {
            throw new IllegalArgumentException("EnvironmentProperties must not be null");
        }
        this.envProps = envProps;
    }
    
    /**
     * Converts a map with int[] keys to a map with Integer keys (linearization).
     * @param original The original map with int[] keys.
     * @return A new map with Integer keys.
     * @param <V> The value type of the map.
     */
    public <V> Map<Integer, V> linearizeMap(Map<int[], V> original) {
        if (original == null) return Map.of();
        
        // Collect the coordinates per flat index first, so that a collision can name both.
        Map<Integer, int[]> linearizedCoords = new HashMap<>();
        Map<Integer, int[]> collisionCoords = new HashMap<>();
        
        for (Map.Entry<int[], V> entry : original.entrySet()) {
            int[] coord = entry.getKey();
            int linearized = linearizeCoordinate(coord);
            if (linearizedCoords.containsKey(linearized)) {
                int[] existingCoord = linearizedCoords.get(linearized);
                collisionCoords.put(linearized, existingCoord);
                // Keep the colliding coordinate as well, offset so both survive in one map
                collisionCoords.put(linearized + 1000000, coord); // offset, so both fit in one map
            } else {
                linearizedCoords.put(linearized, coord);
            }
        }
        
        // Report every collision at once, naming the index and both coordinates
        if (!collisionCoords.isEmpty()) {
            StringBuilder errorMsg = new StringBuilder();
            errorMsg.append("Coordinate collision detected during linearization. ");
            errorMsg.append("Multiple coordinates map to the same linearized index in toroidal world. ");
            errorMsg.append("This usually indicates invalid coordinates in the program artifact. ");
            errorMsg.append("Collisions found: ");
            
            boolean first = true;
            for (Map.Entry<Integer, int[]> entry : collisionCoords.entrySet()) {
                if (entry.getKey() < 1000000) { // the first coordinate of each pair
                    if (!first) errorMsg.append(", ");
                    int linearized = entry.getKey();
                    int[] coord1 = entry.getValue();
                    int[] coord2 = collisionCoords.get(linearized + 1000000);
                    errorMsg.append(String.format("index %d: [%s] and [%s]", 
                        linearized, 
                        java.util.Arrays.toString(coord1), 
                        java.util.Arrays.toString(coord2)));
                    first = false;
                }
            }
            
            throw new IllegalArgumentException(errorMsg.toString());
        }
        
        return original.entrySet().stream()
            .collect(Collectors.toMap(
                entry -> linearizeCoordinate(entry.getKey()),
                Map.Entry::getValue
            ));
    }
    
    /**
     * Linearizes a coordinate to a flat integer index.
     * @param coord The coordinate to linearize.
     * @return The flat integer index.
     */
    private int linearizeCoordinate(int[] coord) {
        if (coord == null || coord.length != envProps.getWorldShape().length) {
            throw new IllegalArgumentException("Coordinate dimensions must match world shape");
        }
        
        // Wrap into the world first where the environment is toroidal
        int[] normalizedCoord = normalizeCoordinate(coord);

        return envProps.toFlatIndex(normalizedCoord);
    }
    
    /**
     * Normalize a coordinate based on EnvironmentProperties.
     * For toroidal environments the coordinates will be set to valid boundaries.
     */
    private int[] normalizeCoordinate(int[] coord) {
        if (!envProps.isToroidal()) {
            return coord; // a non-toroidal world does not wrap
        }

        int[] normalized = new int[coord.length];
        for (int i = 0; i < coord.length; i++) {
            normalized[i] = Math.floorMod(coord[i], envProps.getWorldShape()[i]);
        }
        return normalized;
    }

}
