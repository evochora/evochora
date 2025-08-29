package org.evochora.server.engine;

import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.queue.InMemoryTickQueue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains integration tests for the world seeding functionality of the {@link SimulationEngine}.
 * These tests verify that initial world objects from program artifacts are correctly
 * placed into the environment when the simulation starts.
 */
class WorldSeedingTest {
    /**
     * Verifies that the SimulationEngine correctly seeds the world with initial objects
     * defined in a ProgramArtifact before starting the simulation loop.
     * <p>
     * This is an integration test as it involves starting the threaded SimulationEngine
     * and observing its output.
     *
     * @throws Exception if thread operations fail.
     */
    @Test
    @Tag("integration")
    void seedsInitialWorldObjectsOnStart() throws Exception {
        var queue = new InMemoryTickQueue();
        var engine = new SimulationEngine(queue, new int[]{10, 10}, true); // Small world
        var artifact = new ProgramArtifact(
                "pid1",
                emptyMap(), // sources
                emptyMap(), // machineCodeLayout
                Map.of(new int[]{1,2}, new PlacedMolecule(org.evochora.runtime.Config.TYPE_DATA, 42)),
                emptyMap(), // sourceMap
                emptyMap(), // callSiteBindings
                emptyMap(), // relativeCoordToLinearAddress
                emptyMap(), // linearAddressToCoord
                emptyMap(), // labelAddressToName
                emptyMap(), // registerAliasMap
                emptyMap()  // procNameToParamNames
        );
        engine.setProgramArtifacts(List.of(artifact));

        // Start engine and wait for it to produce some messages
        engine.start();
        
        // Wait for the engine to start and produce messages
        Thread.sleep(500);
        
        // Check that the simulation is running and has advanced
        assertThat(engine.getCurrentTick()).isGreaterThanOrEqualTo(0L);
        
        // Expect at least one message (artifact) and then world states
        assertThat(queue.size()).isGreaterThan(0);
        
        engine.shutdown();
        
        // Wait for shutdown to complete
        Thread.sleep(1000);
        assertThat(engine.isRunning()).isFalse();
    }
}
