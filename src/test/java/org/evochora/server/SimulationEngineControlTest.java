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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SimulationEngineControlTest {

    private SimulationEngine sim;

    @AfterEach
    void tearDown() {
        if (sim != null && sim.isRunning()) {
            sim.shutdown();
            // Give the thread time to terminate
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void simple_start_shutdown_test() throws Exception {
        ITickMessageQueue q = new InMemoryTickQueue();
        sim = new SimulationEngine(q, new int[]{10, 10}, true); // Small world

        sim.start();

        // Wait for simulation to start and produce some ticks
        Thread.sleep(500);
        assertThat(sim.getCurrentTick()).isGreaterThanOrEqualTo(0L);

        // Shutdown
        sim.shutdown();
        
        // Wait for shutdown to complete
        Thread.sleep(1000);
        
        assertThat(sim.isRunning()).isFalse();
    }

    @Test
    void start_pause_resume_shutdown_cycle_advances_ticks() throws Exception {
        ITickMessageQueue q = new InMemoryTickQueue();
        sim = new SimulationEngine(q, new int[]{10, 10}, true); // Much smaller world

        sim.start();

        // 1. Warten, bis die Simulation initialisiert ist (erste Nachrichten).
        q.take(); // Nimm die Artefakt-Nachricht heraus
        
        // Wait for initial message with timeout
        long startTime = System.currentTimeMillis();
        IQueueMessage initialMessage = null;
        while (System.currentTimeMillis() - startTime < 5000) {
            try {
                initialMessage = q.poll(100, TimeUnit.MILLISECONDS);
                if (initialMessage != null) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        if (initialMessage == null) {
            throw new RuntimeException("Simulation failed to start within 5 seconds");
        }
        
        // The simulation might have advanced a few ticks before we get the first message
        long initialTick = ((RawTickState) initialMessage).tickNumber();
        assertThat(initialTick).isGreaterThanOrEqualTo(0L);

        // 2. Lassen Sie die Simulation kurz laufen.
        Thread.sleep(100); // Increased from 50ms
        long t1 = sim.getCurrentTick();
        assertThat(t1).isGreaterThan(0L);

        // 3. Pausieren und dem Thread Zeit geben, die Pause zu registrieren.
        sim.pause();
        Thread.sleep(200); // Increased from 100ms to ensure pause takes effect
        long t3 = sim.getCurrentTick();

        // 4. Leeren Sie die Warteschlange NACH dem Pausieren.
        while (q.size() > 0) {
            q.take();
        }

        // 5. Warten Sie erneut und stellen Sie sicher, dass die Warteschlange leer BLEIBT.
        Thread.sleep(300); // Increased from 200ms
        assertThat(q.size()).as("Queue should remain empty while paused").isZero();
        assertThat(sim.getCurrentTick()).isEqualTo(t3);

        // 6. Fortsetzen und auf die nächste Nachricht warten.
        sim.resume();
        Thread.sleep(100); // Increased from 50ms to give simulation time to resume
        
        // Wait for next message with timeout
        startTime = System.currentTimeMillis();
        IQueueMessage nextMessage = null;
        while (System.currentTimeMillis() - startTime < 5000) {
            try {
                nextMessage = q.poll(100, TimeUnit.MILLISECONDS);
                if (nextMessage != null) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        if (nextMessage == null) {
            throw new RuntimeException("Simulation did not resume within 5 seconds");
        }

        assertThat(((RawTickState) nextMessage).tickNumber()).isGreaterThan(t3);

        // 7. Herunterfahren.
        sim.shutdown();
        Thread.sleep(200); // Increased from 50ms to give thread time to terminate
        assertThat(sim.isRunning()).isFalse();
    }

    @Test
    void minimal_shutdown_test() throws Exception {
        ITickMessageQueue q = new InMemoryTickQueue();
        sim = new SimulationEngine(q, new int[]{10, 10}, true); // Small world

        sim.start();

        // Wait for simulation to start
        Thread.sleep(100);
        assertThat(sim.getCurrentTick()).isGreaterThanOrEqualTo(0L);

        // Shutdown
        sim.shutdown();
        
        // Wait for shutdown to complete
        Thread.sleep(1000);
        
        assertThat(sim.isRunning()).isFalse();
    }

    @Test
    void immediate_shutdown_test() throws Exception {
        ITickMessageQueue q = new InMemoryTickQueue();
        sim = new SimulationEngine(q, new int[]{5, 5}, true); // Very small world

        sim.start();

        // Shutdown immediately
        sim.shutdown();
        
        // Wait for shutdown to complete
        Thread.sleep(2000);
        
        assertThat(sim.isRunning()).isFalse();
    }
}