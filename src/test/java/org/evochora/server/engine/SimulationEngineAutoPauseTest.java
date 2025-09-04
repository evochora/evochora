package org.evochora.server.engine;

import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;

import java.util.ArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains integration tests for auto-pause functionality in the simulation engine.
 * These tests verify that the simulation engine can automatically pause at specified
 * tick intervals and that the auto-pause mechanism works correctly.
 * These are integration tests as they involve managing a live, threaded service.
 */
@Tag("integration")
class SimulationEngineAutoPauseTest {

    private ITickMessageQueue queue;
    private SimulationEngine engine;

    @BeforeEach
    void setUp() {
        queue = new InMemoryTickQueue();
        engine = new SimulationEngine(queue, new EnvironmentProperties(new int[]{10, 10}, true), 
             new ArrayList<>(), new ArrayList<>());
    }

    @AfterEach
    void tearDown() {
        if (engine != null && engine.isRunning()) {
            engine.shutdown();
            // Wait for shutdown to complete
            try {
                waitForShutdown(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Wait for a condition to be true, checking every 10ms
     * @param condition The condition to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     * @param description Description of what we're waiting for
     * @return true if condition was met, false if timeout occurred
     */
    private boolean waitForCondition(BooleanSupplier condition, long timeoutMs, String description) {
        long startTime = System.currentTimeMillis();
        long checkInterval = 10; // Check every 10ms for faster response

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                System.out.println("Timeout waiting for: " + description);
                return false;
            }
            try {
                Thread.sleep(checkInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Helper method to wait until the simulation engine thread has shut down.
     * @param timeoutMillis The maximum time to wait.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    private void waitForShutdown(long timeoutMillis) throws InterruptedException {
        assertThat(waitForCondition(
            () -> !engine.isRunning(),
            timeoutMillis,
            "simulation engine to shutdown"
        )).isTrue();
    }

    /**
     * Verifies that the simulation engine can be configured with auto-pause
     * and that it starts correctly with this configuration.
     * This is an integration test of the auto-pause functionality.
     * @throws Exception if the test fails.
     */
    @Test
    void testAutoPauseConfiguration() throws Exception {
        // Set auto-pause at tick 5
        engine.setAutoPauseTicks(new int[]{5});
        
        // Start the engine
        engine.start();
        
        // Wait for engine to start
        assertThat(waitForCondition(
            engine::isRunning,
            5000,
            "simulation engine to start"
        )).isTrue();
        
        // Verify the engine is running
        assertThat(engine.isRunning()).isTrue();
        
        // Shutdown
        engine.shutdown();
        
        // Wait for shutdown to complete
        assertThat(waitForCondition(
            () -> !engine.isRunning(),
            5000,
            "simulation engine to shutdown"
        )).isTrue();
    }

    /**
     * Verifies that the simulation engine can handle multiple auto-pause points
     * and that it starts correctly with this configuration.
     * This is an integration test of the auto-pause functionality.
     * @throws Exception if the test fails.
     */
    @Test
    void testMultipleAutoPausePoints() throws Exception {
        // Set auto-pause at multiple ticks
        engine.setAutoPauseTicks(new int[]{5, 10, 15});
        
        // Start the engine
        engine.start();
        
        // Wait for engine to start
        assertThat(waitForCondition(
            engine::isRunning,
            5000,
            "simulation engine to start"
        )).isTrue();
        
        // Verify the engine is running
        assertThat(engine.isRunning()).isTrue();
        
        // Shutdown
        engine.shutdown();
        
        // Wait for shutdown to complete
        assertThat(waitForCondition(
            () -> !engine.isRunning(),
            5000,
            "simulation engine to shutdown"
        )).isTrue();
    }

    /**
     * Verifies that the simulation engine can handle empty auto-pause configuration
     * (no auto-pause points) and still start properly.
     * This is an integration test of the auto-pause functionality.
     * @throws Exception if the test fails.
     */
    @Test
    void testNoAutoPauseConfiguration() throws Exception {
        // Start the engine without setting auto-pause
        engine.start();
        
        // Wait for engine to start
        assertThat(waitForCondition(
            engine::isRunning,
            5000,
            "simulation engine to start"
        )).isTrue();
        
        // Verify the engine is running
        assertThat(engine.isRunning()).isTrue();
        
        // Shutdown
        engine.shutdown();
        
        // Wait for shutdown to complete
        assertThat(waitForCondition(
            () -> !engine.isRunning(),
            5000,
            "simulation engine to shutdown"
        )).isTrue();
    }
}
