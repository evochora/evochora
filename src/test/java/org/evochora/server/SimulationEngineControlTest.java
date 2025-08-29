package org.evochora.server;

import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.contracts.IQueueMessage;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationEngineControlTest {

    private SimulationEngine sim;

    @AfterEach
    void tearDown() {
        if (sim != null && sim.isRunning()) {
            sim.shutdown();
            // Wait for shutdown to complete
            try {
                waitForShutdown(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

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

    private void waitForTick(long tick, long timeoutMillis) throws InterruptedException {
        assertTrue(waitForCondition(
            () -> sim.getCurrentTick() >= tick,
            timeoutMillis,
            "tick to reach " + tick + " (current: " + sim.getCurrentTick() + ")"
        ));
    }

    private void waitForShutdown(long timeoutMillis) throws InterruptedException {
        assertTrue(waitForCondition(
            () -> !sim.isRunning(),
            timeoutMillis,
            "simulation engine to shutdown"
        ));
    }

    @Test
    @Tag("integration")
    void simple_start_shutdown_test() throws Exception {
        ITickMessageQueue q = new InMemoryTickQueue();
        sim = new SimulationEngine(q, new int[]{10, 10}, true); // Small world

        sim.start();

        // Wait for simulation to start and produce some ticks
        waitForTick(0, 5000);
        assertThat(sim.getCurrentTick()).isGreaterThanOrEqualTo(0L);

        // Shutdown
        sim.shutdown();
        
        // Wait for shutdown to complete
        waitForShutdown(5000);
        
        assertThat(sim.isRunning()).isFalse();
    }

    @Test
    @Tag("integration")
    void start_pause_resume_shutdown_cycle_advances_ticks() throws Exception {
        ITickMessageQueue q = new InMemoryTickQueue();
        sim = new SimulationEngine(q, new int[]{10, 10}, true); // Much smaller world

        sim.start();

        // 1. Wait for the simulation to initialize and produce the first tick.
        waitForTick(0, 5000);
        IQueueMessage initialMessage = q.poll(5, TimeUnit.SECONDS);
        assertThat(initialMessage).isNotNull();
        long initialTick = ((RawTickState) initialMessage).tickNumber();
        assertThat(initialTick).isGreaterThanOrEqualTo(0L);

        // 2. Let the simulation run briefly.
        waitForTick(initialTick + 1, 5000);
        long t1 = sim.getCurrentTick();
        assertThat(t1).isGreaterThan(initialTick);

        // 3. Pause and wait for it to take effect.
        sim.pause();
        assertTrue(waitForCondition(
            sim::isPaused,
            1000,
            "simulation to pause"
        ));
        long t3 = sim.getCurrentTick();

        // 4. Drain the queue AFTER pausing.
        while (q.poll() != null) {
            // do nothing
        }

        // 5. Wait and assert that the queue REMAINS empty.
        assertTrue(waitForCondition(
            () -> q.size() == 0,
            1000,
            "queue to remain empty while paused"
        ));
        assertThat(q.size()).as("Queue should remain empty while paused").isZero();
        assertThat(sim.getCurrentTick()).isEqualTo(t3);

        // 6. Resume and wait for the next message.
        sim.resume();
        IQueueMessage nextMessage = q.poll(5, TimeUnit.SECONDS);
        assertThat(nextMessage).isNotNull();
        assertThat(((RawTickState) nextMessage).tickNumber()).isGreaterThan(t3);

        // 7. Shutdown.
        sim.shutdown();
        waitForShutdown(5000);
        assertThat(sim.isRunning()).isFalse();
    }

    @Test
    @Tag("integration")
    void minimal_shutdown_test() throws Exception {
        ITickMessageQueue q = new InMemoryTickQueue();
        sim = new SimulationEngine(q, new int[]{10, 10}, true); // Small world

        sim.start();

        // Wait for simulation to start
        waitForTick(0, 5000);
        assertThat(sim.getCurrentTick()).isGreaterThanOrEqualTo(0L);

        // Shutdown
        sim.shutdown();
        
        // Wait for shutdown to complete
        waitForShutdown(5000);
        
        assertThat(sim.isRunning()).isFalse();
    }

    @Test
    @Tag("integration")
    void immediate_shutdown_test() throws Exception {
        ITickMessageQueue q = new InMemoryTickQueue();
        sim = new SimulationEngine(q, new int[]{5, 5}, true); // Very small world

        sim.start();

        // Shutdown immediately
        sim.shutdown();
        
        // Wait for shutdown to complete
        waitForShutdown(5000);
        
        assertThat(sim.isRunning()).isFalse();
    }
}