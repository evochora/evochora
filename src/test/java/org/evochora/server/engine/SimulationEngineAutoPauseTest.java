package org.evochora.server.engine;

import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains integration tests for the auto-pause functionality of the {@link SimulationEngine}.
 * These tests verify that the auto-pause feature can be configured correctly and that
 * the engine's initial state reflects this configuration.
 */
class SimulationEngineAutoPauseTest {

    private SimulationEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null && engine.isRunning()) {
            engine.shutdown();
            // Give the thread time to terminate
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Verifies that setting an array of auto-pause ticks correctly configures the engine
     * without immediately putting it into a paused state.
     * This is an integration test of the engine's configuration.
     */
    @Test
    @Tag("integration")
    void testAutoPauseConfiguration() {
        ITickMessageQueue queue = new InMemoryTickQueue();
        engine = new SimulationEngine(queue, new int[]{10, 10}, true);
        
        int[] autoPauseTicks = {5, 10, 15};
        engine.setAutoPauseTicks(autoPauseTicks);
        
        // Engine should not be auto-paused initially
        assertThat(engine.isAutoPaused()).isFalse();
        assertThat(engine.isPaused()).isFalse();
    }

    /**
     * Verifies that the engine is not in an auto-paused state when no auto-pause ticks are configured.
     * This is an integration test of the engine's default configuration.
     * @throws Exception if the test fails.
     */
    @Test
    @Tag("integration")
    void testAutoPauseDisabled() throws Exception {
        ITickMessageQueue queue = new InMemoryTickQueue();
        engine = new SimulationEngine(queue, new int[]{10, 10}, true);
        
        // Don't set auto-pause ticks
        assertThat(engine.isRunning()).isFalse();
        
        // Engine should not be auto-paused
        assertThat(engine.isAutoPaused()).isFalse();
        assertThat(engine.isPaused()).isFalse();
    }

    /**
     * Verifies that configuring the engine with an empty array of auto-pause ticks
     * does not put it into an auto-paused state.
     * This is an integration test of the engine's configuration handling.
     * @throws Exception if the test fails.
     */
    @Test
    @Tag("integration")
    void testAutoPauseWithEmptyArray() throws Exception {
        ITickMessageQueue queue = new InMemoryTickQueue();
        engine = new SimulationEngine(queue, new int[]{10, 10}, true);
        
        // Set empty auto-pause array
        engine.setAutoPauseTicks(new int[0]);
        assertThat(engine.isRunning()).isFalse();
        
        // Engine should not be auto-paused
        assertThat(engine.isAutoPaused()).isFalse();
        assertThat(engine.isPaused()).isFalse();
    }

    /**
     * Verifies that configuring the engine with a null array of auto-pause ticks
     * does not put it into an auto-paused state.
     * This is an integration test of the engine's robustness to null configuration.
     * @throws Exception if the test fails.
     */
    @Test
    @Tag("integration")
    void testAutoPauseWithNullConfiguration() throws Exception {
        ITickMessageQueue queue = new InMemoryTickQueue();
        engine = new SimulationEngine(queue, new int[]{10, 10}, true);
        
        // Set null auto-pause configuration
        engine.setAutoPauseTicks(null);
        assertThat(engine.isRunning()).isFalse();
        
        // Engine should not be auto-paused
        assertThat(engine.isAutoPaused()).isFalse();
        assertThat(engine.isPaused()).isFalse();
    }
}
