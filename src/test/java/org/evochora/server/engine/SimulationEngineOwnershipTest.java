package org.evochora.server.engine;

import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.queue.InMemoryTickQueue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

class SimulationEngineOwnershipTest {

    @Test
    void places_code_and_world_objects_with_ownership() throws Exception {
        var queue = new InMemoryTickQueue();
        var engine = new SimulationEngine(queue);
        // Artifact with code and data
        var artifact = new ProgramArtifact(
                "pidX",
                emptyMap(), // sources
                Map.of(new int[]{0,0}, org.evochora.runtime.Config.TYPE_CODE | 0x1234), // machineCodeLayout
                Map.of(new int[]{1,0}, new PlacedMolecule(org.evochora.runtime.Config.TYPE_DATA, 99)), // initial world
                emptyMap(), // sourceMap
                emptyMap(), // callSiteBindings
                emptyMap(), // relativeCoordToLinearAddress
                emptyMap(), // linearAddressToCoord
                emptyMap(), // labelAddressToName
                emptyMap(), // registerAliasMap
                emptyMap()  // procNameToParamNames
        );
        engine.setProgramArtifacts(List.of(artifact));
        // Ensure deterministic origin
        UserLoadRegistry.registerDesiredStart("pidX", new int[]{0,0});

        engine.start();

        // Wait for first WorldStateMessage which is sent immediately after seeding (tick 0)
        // Drain queue until we receive a WorldStateMessage, then check for any owned cell.
        // Allow a couple of ticks in case ownership-only cells were not included at tick 0 in some envs.
        boolean anyOwned = false;
        for (int attempts = 0; attempts < 3 && !anyOwned; attempts++) {
            org.evochora.server.contracts.IQueueMessage msg;
            do {
                msg = queue.take();
            } while (!(msg instanceof org.evochora.server.contracts.WorldStateMessage));
            org.evochora.server.contracts.WorldStateMessage wsm = (org.evochora.server.contracts.WorldStateMessage) msg;
            anyOwned = wsm.cellStates().stream().anyMatch(c -> c.ownerId() != 0);
        }
        engine.pause();

        assertThat(anyOwned).as("at least one seeded cell should be owned by tick 1").isTrue();

        engine.shutdown();
    }
}


