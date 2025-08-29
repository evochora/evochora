package org.evochora.server.engine;

import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.queue.InMemoryTickQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains integration tests for the {@link SimulationEngine}, with a focus on ownership.
 * NOTE: The current tests only verify basic engine lifecycle and do not yet test ownership logic.
 */
class SimulationEngineOwnershipTest {

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
    @Tag("integration")
    void places_code_and_world_objects_with_ownership() throws Exception {
        var queue = new InMemoryTickQueue();
        var engine = new SimulationEngine(queue, new int[]{10, 10}, true); // Small world

        engine.start();

        // Wait for simulation to start and produce some ticks
        Thread.sleep(500);
        
        // Check that simulation is running
        var sim = engine.getSimulation();
        assertThat(sim).isNotNull();
        assertThat(sim.getCurrentTick()).isGreaterThanOrEqualTo(0L);
        
        var env = sim.getEnvironment();
        assertThat(env).isNotNull();

        // Shutdown
        engine.shutdown();
        
        // Wait for shutdown to complete
        Thread.sleep(1000);
        
        assertThat(engine.isRunning()).isFalse();
    }
}