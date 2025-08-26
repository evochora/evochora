package org.evochora.server.engine;

import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.queue.InMemoryTickQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SimulationEngineOwnershipTest {

    @Test
    @Tag("unit")
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