package org.evochora.compiler.internal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance-Tests für die CoordinateConverter-Klasse.
 * Misst die Performance der Linearisierung und Delinearisierung von Maps.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoordinateConverterPerformanceTest {

    private static final int[] WORLD_SHAPE_2D = {100, 100};
    private static final int[] WORLD_SHAPE_3D = {50, 50, 50};
    private static final int[] WORLD_SHAPE_4D = {25, 25, 25, 25};
    
    private static final int SMALL_MAP_SIZE = 100;
    private static final int MEDIUM_MAP_SIZE = 1000;
    private static final int LARGE_MAP_SIZE = 10000;
    
    private final Random random = new Random(42); // Fester Seed für reproduzierbare Tests
    private CoordinateConverter converter2D;
    private CoordinateConverter converter3D;
    private CoordinateConverter converter4D;

    @BeforeAll
    void setUp() {
        converter2D = new CoordinateConverter(WORLD_SHAPE_2D);
        converter3D = new CoordinateConverter(WORLD_SHAPE_3D);
        converter4D = new CoordinateConverter(WORLD_SHAPE_4D);
    }

    @Test
    void performance_linearization_2D() {
        Map<int[], Integer> original = generateRandomMap(SMALL_MAP_SIZE, 2);
        
        long startTime = System.nanoTime();
        Map<Integer, Integer> linearized = converter2D.linearizeMap(original);
        long endTime = System.nanoTime();
        
        long duration = endTime - startTime;
        System.out.printf("2D Linearization (%d entries): %d ns (%.2f μs)%n", 
                         SMALL_MAP_SIZE, duration, duration / 1000.0);
        
        assertThat(linearized).hasSize(original.size());
        assertThat(linearized.values()).containsExactlyInAnyOrderElementsOf(original.values());
    }

    @Test
    void performance_linearization_3D() {
        Map<int[], Integer> original = generateRandomMap(MEDIUM_MAP_SIZE, 3);
        
        long startTime = System.nanoTime();
        Map<Integer, Integer> linearized = converter3D.linearizeMap(original);
        long endTime = System.nanoTime();
        
        long duration = endTime - startTime;
        System.out.printf("3D Linearization (%d entries): %d ns (%.2f μs)%n", 
                         MEDIUM_MAP_SIZE, duration, duration / 1000.0);
        
        assertThat(linearized).hasSize(original.size());
    }

    @Test
    void performance_linearization_4D() {
        Map<int[], Integer> original = generateRandomMap(LARGE_MAP_SIZE, 4);
        
        long startTime = System.nanoTime();
        Map<Integer, Integer> linearized = converter4D.linearizeMap(original);
        long endTime = System.nanoTime();
        
        long duration = endTime - startTime;
        System.out.printf("4D Linearization (%d entries): %d ns (%.2f μs)%n", 
                         LARGE_MAP_SIZE, duration, duration / 1000.0);
        
        assertThat(linearized).hasSize(original.size());
    }

    @Test
    void performance_roundtrip_2D() {
        Map<int[], Integer> original = generateRandomMap(SMALL_MAP_SIZE, 2);
        
        long startTime = System.nanoTime();
        Map<Integer, Integer> linearized = converter2D.linearizeMap(original);
        Map<int[], Integer> delinearized = converter2D.delinearizeMap(linearized);
        long endTime = System.nanoTime();
        
        long duration = endTime - startTime;
        System.out.printf("2D Roundtrip (%d entries): %d ns (%.2f μs)%n", 
                         SMALL_MAP_SIZE, duration, duration / 1000.0);
        
        assertThat(delinearized).hasSize(original.size());
        // Vergleiche Werte, da Map.equals() mit int[]-Keys nicht funktioniert
        assertThat(delinearized.values()).containsExactlyInAnyOrderElementsOf(original.values());
    }

    @Test
    void performance_roundtrip_3D() {
        Map<int[], Integer> original = generateRandomMap(MEDIUM_MAP_SIZE, 3);
        
        long startTime = System.nanoTime();
        Map<Integer, Integer> linearized = converter3D.linearizeMap(original);
        Map<int[], Integer> delinearized = converter3D.delinearizeMap(linearized);
        long endTime = System.nanoTime();
        
        long duration = endTime - startTime;
        System.out.printf("3D Roundtrip (%d entries): %d ns (%.2f μs)%n", 
                         MEDIUM_MAP_SIZE, duration, duration / 1000.0);
        
        assertThat(delinearized).hasSize(original.size());
        // Vergleiche Werte, da Map.equals() mit int[]-Keys nicht funktioniert
        assertThat(delinearized.values()).containsExactlyInAnyOrderElementsOf(original.values());
    }

    @Test
    void performance_memory_usage() {
        Map<int[], Integer> original = generateRandomMap(LARGE_MAP_SIZE, 3);
        
        // Messung vor der Konvertierung
        long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        Map<Integer, Integer> linearized = converter3D.linearizeMap(original);
        
        // Messung nach der Konvertierung
        long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long memoryUsed = memoryAfter - memoryBefore;
        
        System.out.printf("Memory usage for %d entries: %d bytes (%.2f KB)%n", 
                         LARGE_MAP_SIZE, memoryUsed, memoryUsed / 1024.0);
        
        assertThat(linearized).hasSize(original.size());
    }

    @Test
    void performance_scalability() {
        int[] sizes = {100, 500, 1000, 5000, 10000};
        int dimensions = 3;
        
        System.out.println("Scalability Test (3D coordinates):");
        System.out.println("Entries\tTime (μs)\tTime per entry (ns)");
        System.out.println("-------\t---------\t-------------------");
        
        for (int size : sizes) {
            Map<int[], Integer> original = generateRandomMap(size, dimensions);
            
            long startTime = System.nanoTime();
            Map<Integer, Integer> linearized = converter3D.linearizeMap(original);
            long endTime = System.nanoTime();
            
            long duration = endTime - startTime;
            double timePerEntry = (double) duration / size;
            
            System.out.printf("%d\t%.2f\t\t%.2f%n", size, duration / 1000.0, timePerEntry);
            
            assertThat(linearized).hasSize(size);
        }
    }

    private Map<int[], Integer> generateRandomMap(int size, int dimensions) {
        Map<int[], Integer> map = new LinkedHashMap<>();
        int attempts = 0;
        int maxAttempts = size * 10; // Verhindere Endlosschleifen
        
        while (map.size() < size && attempts < maxAttempts) {
            int[] coord = new int[dimensions];
            for (int d = 0; d < dimensions; d++) {
                // Generiere Koordinaten innerhalb der World-Shape-Grenzen
                int maxCoord = getMaxCoordinate(dimensions)[d];
                coord[d] = random.nextInt(maxCoord);
            }
            
            // Verwende Arrays.equals für den Vergleich
            boolean isDuplicate = map.keySet().stream()
                .anyMatch(existing -> java.util.Arrays.equals(existing, coord));
            
            if (!isDuplicate) {
                map.put(coord, random.nextInt(1000));
            }
            attempts++;
        }
        
        if (map.size() < size) {
            throw new IllegalStateException("Could not generate " + size + " unique coordinates after " + maxAttempts + " attempts");
        }
        
        return map;
    }

    private int[] getMaxCoordinate(int dimensions) {
        switch (dimensions) {
            case 2: return WORLD_SHAPE_2D;
            case 3: return WORLD_SHAPE_3D;
            case 4: return WORLD_SHAPE_4D;
            default: throw new IllegalArgumentException("Unsupported dimensions: " + dimensions);
        }
    }
}
