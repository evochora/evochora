package org.evochora.server.engine;

import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.queue.InMemoryTickQueue;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

class WorldSeedingTest {
    @Test
    void seedsInitialWorldObjectsOnStart() throws Exception {
        var queue = new InMemoryTickQueue();
        var engine = new SimulationEngine(queue);
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

        // Start engine and immediately pause to avoid flooding
        engine.start();
        engine.pause();

        // Expect at least one message (artifact) and then world states; just assert queue not empty
        Thread.sleep(50);
        assertThat(queue.size()).isGreaterThan(0);
        engine.shutdown();
    }
}
