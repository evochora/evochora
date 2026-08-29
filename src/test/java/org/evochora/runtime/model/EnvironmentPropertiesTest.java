package org.evochora.runtime.model;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class EnvironmentPropertiesTest {
    
    @Test
    void testFlatIndexToCoordinates_1D() {
        EnvironmentProperties props = new EnvironmentProperties(new int[]{100}, false);
        
        assertArrayEquals(new int[]{0}, props.flatIndexToCoordinates(0));
        assertArrayEquals(new int[]{1}, props.flatIndexToCoordinates(1));
        assertArrayEquals(new int[]{50}, props.flatIndexToCoordinates(50));
        assertArrayEquals(new int[]{99}, props.flatIndexToCoordinates(99));
    }
    
    @Test
    void testFlatIndexToCoordinates_2D() {
        EnvironmentProperties props = new EnvironmentProperties(new int[]{100, 200}, false);
        
        // Test corners
        assertArrayEquals(new int[]{0, 0}, props.flatIndexToCoordinates(0));
        assertArrayEquals(new int[]{0, 1}, props.flatIndexToCoordinates(1));
        assertArrayEquals(new int[]{1, 0}, props.flatIndexToCoordinates(200));
        assertArrayEquals(new int[]{0, 199}, props.flatIndexToCoordinates(199));
        assertArrayEquals(new int[]{99, 199}, props.flatIndexToCoordinates(19999));
        
        // Test middle
        assertArrayEquals(new int[]{5, 10}, props.flatIndexToCoordinates(5 * 200 + 10));
    }
    
    @Test
    void testFlatIndexToCoordinates_3D() {
        EnvironmentProperties props = new EnvironmentProperties(new int[]{10, 20, 30}, false);
        
        // Test corners
        assertArrayEquals(new int[]{0, 0, 0}, props.flatIndexToCoordinates(0));
        assertArrayEquals(new int[]{0, 0, 1}, props.flatIndexToCoordinates(1));
        assertArrayEquals(new int[]{0, 1, 0}, props.flatIndexToCoordinates(30));
        assertArrayEquals(new int[]{1, 0, 0}, props.flatIndexToCoordinates(600));
        assertArrayEquals(new int[]{9, 19, 29}, props.flatIndexToCoordinates(5999));
        
        // Test middle
        int flatIndex = 3 * 600 + 7 * 30 + 15;  // [3, 7, 15]
        assertArrayEquals(new int[]{3, 7, 15}, props.flatIndexToCoordinates(flatIndex));
    }
    
    @Test
    void testFlatIndexToCoordinates_NegativeThrows() {
        EnvironmentProperties props = new EnvironmentProperties(new int[]{100}, false);
        
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> props.flatIndexToCoordinates(-1)
        );
        assertTrue(ex.getMessage().contains("non-negative"));
    }
    
    @Test
    void testFlatIndexToCoordinates_ThreadSafety() throws InterruptedException {
        EnvironmentProperties props = new EnvironmentProperties(new int[]{100, 100}, false);
        int numThreads = 10;
        int iterationsPerThread = 1000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger failures = new AtomicInteger(0);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();  // All threads start at once
                    for (int i = 0; i < iterationsPerThread; i++) {
                        int flatIndex = threadId * 100 + i % 100;
                        int[] coords = props.flatIndexToCoordinates(flatIndex);
                        
                        // Verify consistency
                        int expectedX = flatIndex / 100;
                        int expectedY = flatIndex % 100;
                        if (coords[0] != expectedX || coords[1] != expectedY) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }
        
        startLatch.countDown();  // Start all threads
        doneLatch.await();       // Wait for completion
        
        assertEquals(0, failures.get(), "Thread-safety test failed");
    }
    
    @Test
    void testFlatIndexToCoordinates_RoundTrip() {
        // Verify conversion matches Environment.getFlatIndex formula
        EnvironmentProperties props = new EnvironmentProperties(new int[]{10, 20, 30}, false);
        
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 20; y++) {
                for (int z = 0; z < 30; z++) {
                    int flatIndex = x * 600 + y * 30 + z;
                    int[] coords = props.flatIndexToCoordinates(flatIndex);
                    
                    assertEquals(x, coords[0], "X coordinate mismatch");
                    assertEquals(y, coords[1], "Y coordinate mismatch");
                    assertEquals(z, coords[2], "Z coordinate mismatch");
                }
            }
        }
    }

    @Test
    void relativeVectorIsTheInverseOfApplyingIt() {
        EnvironmentProperties props = new EnvironmentProperties(new int[]{100, 50}, true);
        int[] from = new int[]{10, 20};

        for (int x = 0; x < 100; x++) {
            for (int y = 0; y < 50; y++) {
                int[] to = new int[]{x, y};
                int[] relative = props.getRelativeVector(from, to);
                assertArrayEquals(to, props.getTargetCoordinate(from, relative),
                        "Applying the relative vector must reach the original position");
            }
        }
    }

    @Test
    void relativeVectorCrossesTheWrapOnTheShortWay() {
        // A cell two columns before the origin is two columns away, not almost a world away
        EnvironmentProperties props = new EnvironmentProperties(new int[]{100, 50}, true);

        int[] relative = props.getRelativeVector(new int[]{1, 0}, new int[]{99, 49});

        assertArrayEquals(new int[]{-2, -1}, relative);
    }

    @Test
    void relativeVectorStaysWithinHalfTheWorld() {
        EnvironmentProperties props = new EnvironmentProperties(new int[]{100, 51}, true);

        for (int x = 0; x < 100; x++) {
            for (int y = 0; y < 51; y++) {
                int[] relative = props.getRelativeVector(new int[]{0, 0}, new int[]{x, y});
                assertTrue(relative[0] >= -50 && relative[0] < 50, "x out of range: " + relative[0]);
                assertTrue(relative[1] >= -25 && relative[1] <= 25, "y out of range: " + relative[1]);
            }
        }
    }

    @Test
    void relativeVectorOnAFlatWorldIsPlainSubtraction() {
        // Without wrapping, a position before the origin stays negative and far stays far
        EnvironmentProperties props = new EnvironmentProperties(new int[]{100, 50}, false);

        assertArrayEquals(new int[]{-9, -19}, props.getRelativeVector(new int[]{10, 20}, new int[]{1, 1}));
        assertArrayEquals(new int[]{89, 29}, props.getRelativeVector(new int[]{10, 20}, new int[]{99, 49}));
    }
}
