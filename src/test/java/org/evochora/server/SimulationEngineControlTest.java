package org.evochora.server;

import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.contracts.IQueueMessage;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SimulationEngineControlTest {

    private SimulationEngine sim;

    @AfterEach
    void tearDown() {
        if (sim != null && sim.isRunning()) sim.shutdown();
    }

    @Test
    void start_pause_resume_shutdown_cycle_advances_ticks() throws Exception {
        ITickMessageQueue q = new InMemoryTickQueue();
        sim = new SimulationEngine(q, false);

        // KORREKTUR: Gib der Simulation einen Organismus, um sie zu verlangsamen und den Test robust zu machen.
        Path tempDir = Files.createTempDirectory("evochora-test-");
        Path sourceFile = tempDir.resolve("control_test.s");
        Files.writeString(sourceFile, "NOP");

        SimulationConfiguration.OrganismDefinition def = new SimulationConfiguration.OrganismDefinition();
        def.program = sourceFile.toAbsolutePath().toString();
        def.initialEnergy = 1000;
        def.placement = new SimulationConfiguration.PlacementConfig();
        def.placement.positions = new int[][]{{0, 0}};
        sim.setOrganismDefinitions(new SimulationConfiguration.OrganismDefinition[]{def});

        sim.start();

        // 1. Warten, bis die Simulation initialisiert ist (erste Nachrichten).
        q.take(); // Nimm die Artefakt-Nachricht heraus
        IQueueMessage initialMessage = assertTimeoutPreemptively(Duration.ofSeconds(5), q::take, "Simulation failed to start.");
        assertThat(((RawTickState) initialMessage).tickNumber()).isZero();

        // 2. Lassen Sie die Simulation kurz laufen.
        Thread.sleep(50);
        long t1 = sim.getCurrentTick();
        assertThat(t1).isGreaterThan(0L);

        // 3. Pausieren und dem Thread Zeit geben, die Pause zu registrieren.
        sim.pause();
        Thread.sleep(100);
        long t3 = sim.getCurrentTick();

        // 4. Leeren Sie die Warteschlange NACH dem Pausieren.
        while (q.size() > 0) {
            q.take();
        }

        // 5. Warten Sie erneut und stellen Sie sicher, dass die Warteschlange leer BLEIBT.
        Thread.sleep(100);
        assertThat(q.size()).as("Queue should remain empty while paused").isZero();
        assertThat(sim.getCurrentTick()).isEqualTo(t3);

        // 6. Fortsetzen und auf die nächste Nachricht warten.
        sim.resume();
        IQueueMessage nextMessage = assertTimeoutPreemptively(Duration.ofSeconds(5), q::take, "Simulation did not resume.");

        assertThat(((RawTickState) nextMessage).tickNumber()).isGreaterThan(t3);

        // 7. Herunterfahren.
        sim.shutdown();
        Thread.sleep(50);
        assertThat(sim.isRunning()).isFalse();
    }
}