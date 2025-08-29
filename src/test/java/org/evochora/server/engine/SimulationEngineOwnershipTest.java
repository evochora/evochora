package org.evochora.server.engine;

import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.queue.InMemoryTickQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contains integration tests for the {@link SimulationEngine}, with a focus on ownership.
 * NOTE: The current tests only verify basic engine lifecycle and do not yet test ownership logic.
 */
class SimulationEngineOwnershipTest {

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
     * Verifies that the SimulationEngine can be started and that it initializes its
     * internal Simulation and Environment objects correctly. The test then shuts down the engine.
     * Although named to imply an ownership test, this test currently only covers the basic
     * lifecycle of the engine.
     * <p>
     * This is an integration test as it involves a live, threaded service.
     *
     * @throws Exception if thread operations fail.
     */
    @Test
    @Tag("unit")
    void places_code_and_world_objects_with_ownership() throws Exception {
        var queue = new InMemoryTickQueue();
        var engine = new SimulationEngine(queue, new int[]{10, 10}, true); // Small world

        engine.start();

        // Wait for simulation to start and produce some ticks
        assertTrue(waitForCondition(
            () -> {
                var sim = engine.getSimulation();
                return sim != null && sim.getCurrentTick() >= 0L;
            },
            2000,
            "simulation to start and produce ticks"
        ));
        
        // Check that simulation is running
        var sim = engine.getSimulation();
        assertThat(sim).isNotNull();
        assertThat(sim.getCurrentTick()).isGreaterThanOrEqualTo(0L);
        
        var env = sim.getEnvironment();
        assertThat(env).isNotNull();

        // Shutdown
        engine.shutdown();
        
        // Wait for shutdown to complete
        assertTrue(waitForCondition(
            () -> !engine.isRunning(),
            2000,
            "engine to shutdown"
        ));
        
        assertThat(engine.isRunning()).isFalse();
    }
}