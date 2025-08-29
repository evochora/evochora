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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Contains integration tests for the lifecycle control of the {@link SimulationEngine}.
 * These tests verify that the engine thread can be correctly started, paused, resumed, and shut down.
 * These are integration tests as they involve managing a live, threaded service.
 */
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

    /**
     * Helper method to wait until the simulation has reached a specific tick.
     * @param tick The target tick number.
     * @param timeoutMillis The maximum time to wait.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    private void waitForTick(long tick, long timeoutMillis) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (sim.getCurrentTick() < tick && (System.currentTimeMillis() - startTime) < timeoutMillis) {
            Thread.sleep(50);
        }
    }

    /**
     * Helper method to wait until the simulation engine thread has shut down.
     * @param timeoutMillis The maximum time to wait.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    private void waitForShutdown(long timeoutMillis) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (sim.isRunning() && (System.currentTimeMillis() - startTime) < timeoutMillis) {
            Thread.sleep(50);
        }
    }

    /**
     * A simple test to verify that the simulation engine can be started and then gracefully shut down.
     * This is an integration test of the service lifecycle.
     * @throws Exception if the test fails.
     */
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

    /**
     * A comprehensive test that verifies the full lifecycle of the simulation engine:
     * start, run, pause, resume, and shutdown. It checks that ticks are produced
     * only when the service is in a running state.
     * This is an integration test of the service lifecycle.
     * @throws Exception if the test fails.
     */
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
        Thread.sleep(100); // Give a moment for the pause to register
        long t3 = sim.getCurrentTick();

        // 4. Drain the queue AFTER pausing.
        while (q.poll() != null) {
            // do nothing
        }

        // 5. Wait again and assert that the queue REMAINS empty.
        Thread.sleep(100);
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

    /**
     * A minimal test to verify the shutdown functionality after a short run.
     * This is an integration test of the service lifecycle.
     * @throws Exception if the test fails.
     */
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

    /**
     * Verifies that the engine can be shut down immediately after starting.
     * This is an integration test of the service lifecycle robustness.
     * @throws Exception if the test fails.
     */
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