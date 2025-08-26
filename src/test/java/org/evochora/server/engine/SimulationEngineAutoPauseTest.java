package org.evochora.server.engine;

import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the auto-pause functionality of SimulationEngine.
 * Note: These tests verify the configuration loading and basic functionality.
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
