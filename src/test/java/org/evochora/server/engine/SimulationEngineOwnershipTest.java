package org.evochora.server.engine;

import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.queue.InMemoryTickQueue;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SimulationEngineOwnershipTest {

    @Test
    void places_code_and_world_objects_with_ownership() throws Exception {
        var queue = new InMemoryTickQueue();
        var engine = new SimulationEngine(queue);

        // KORREKTUR: Erstelle eine temporäre Quelldatei, die die Engine laden kann.
        Path tempDir = Files.createTempDirectory("evochora-test-");
        Path sourceFile = tempDir.resolve("owner_test.s");
        Files.writeString(sourceFile, "NOP");

        // KORREKTUR: Verwende die neue Konfigurationsmethode, um einen Organismus zu definieren.
        SimulationConfiguration.OrganismDefinition def = new SimulationConfiguration.OrganismDefinition();
        def.id = "test_org";
        // Gib der Engine den Pfad zur echten Datei, die sie kompilieren kann.
        def.program = sourceFile.toAbsolutePath().toString();
        def.initialEnergy = 1000;
        def.placement = new SimulationConfiguration.PlacementConfig();
        def.placement.strategy = "fixed";
        def.placement.positions = new int[][]{{5, 5}}; // Platzierung bei 5,5

        engine.setOrganismDefinitions(new SimulationConfiguration.OrganismDefinition[]{def});
        // Das Artefakt muss nicht mehr manuell gesetzt werden, die Engine kompiliert selbst.

        engine.start();

        // Warte auf die ersten Nachrichten, um sicherzustellen, dass die Simulation läuft
        queue.take(); // ProgramArtifactMessage
        queue.take(); // Tick 0

        // Der Test terminiert jetzt, weil ein Organismus existiert und die Simulation läuft.
        // Wir können den Zustand der Simulation direkt überprüfen.
        var sim = engine.getSimulation();
        assertThat(sim).isNotNull();
        var env = sim.getEnvironment();

        // Nach Tick 0 sollte die Zelle (5,5) dem Organismus gehören.
        int ownerId = env.getOwnerId(5, 5);
        assertThat(ownerId).as("Cell at organism start position [5,5] should be owned.").isNotEqualTo(0);

        engine.shutdown();
    }
}