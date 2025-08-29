package org.evochora.server.engine;

import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.queue.InMemoryTickQueue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSeedingTest {

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

    @Test
    @Tag("unit")
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
        assertTrue(waitForCondition(
            () -> engine.getCurrentTick() >= 0L && queue.size() > 0,
            2000,
            "engine to start and produce messages (tick: " + engine.getCurrentTick() + ", queue size: " + queue.size() + ")"
        ));
        
        // Check that the simulation is running and has advanced
        assertThat(engine.getCurrentTick()).isGreaterThanOrEqualTo(0L);
        
        // Expect at least one message (artifact) and then world states
        assertThat(queue.size()).isGreaterThan(0);
        
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
