package org.evochora.server;

import org.evochora.server.engine.SimulationEngine;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
        sim.start();

        // Wait until engine ticks at least once
        long start = System.currentTimeMillis();
        while (sim.getCurrentTick() < 0 && (System.currentTimeMillis() - start) < 2000) {
            Thread.sleep(10);
        }
        assertThat(sim.isRunning()).isTrue();

        // Let it tick a bit
        Thread.sleep(50);
        long t1 = sim.getCurrentTick();
        assertThat(t1).isGreaterThanOrEqualTo(0L);

        // Pause and ensure tick does not advance
        sim.pause();
        Thread.sleep(50);
        long t2 = sim.getCurrentTick();
        Thread.sleep(100);
        long t3 = sim.getCurrentTick();
        assertThat(t2).isEqualTo(t3);

        // Resume and ensure tick advances again
        sim.resume();
        Thread.sleep(100);
        long t4 = sim.getCurrentTick();
        assertThat(t4).isGreaterThan(t3);

        // Shutdown
        sim.shutdown();
        // Allow shutdown to propagate
        Thread.sleep(50);
        assertThat(sim.isRunning()).isFalse();
    }
}


