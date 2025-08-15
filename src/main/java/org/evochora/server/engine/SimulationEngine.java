package org.evochora.server.engine;

import org.evochora.server.IControllable;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.contracts.ProgramArtifactMessage;
import org.evochora.server.contracts.IQueueMessage;
import org.evochora.server.contracts.WorldStateMessage;
import org.evochora.server.queue.ITickMessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Computes world states and publishes them to the queue at maximum speed,
 * while allowing pause/resume for CLI responsiveness.
 */
public final class SimulationEngine implements IControllable, Runnable {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    private final ITickMessageQueue queue;

    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    private long currentTick = 0L;
    private org.evochora.app.Simulation simulation;
    private java.util.List<ProgramArtifact> programArtifacts = java.util.Collections.emptyList();

    public SimulationEngine(ITickMessageQueue queue) {
        this.queue = queue;
        this.thread = new Thread(this, "SimulationEngine");
        this.thread.setDaemon(true);
    }

    /**
     * Provide compiled program artifacts before starting the engine.
     */
    public void setProgramArtifacts(java.util.List<ProgramArtifact> artifacts) {
        this.programArtifacts = artifacts == null ? java.util.Collections.emptyList() : artifacts;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            thread.start();
        }
    }

    @Override
    public void pause() {
        paused.set(true);
    }

    @Override
    public void resume() {
        paused.set(false);
    }

    @Override
    public void shutdown() {
        running.set(false);
        thread.interrupt();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isPaused() {
        return paused.get();
    }

    @Override
    public void run() {
        log.info("SimulationEngine started");
        try {
            // First, publish program artifacts once
            for (ProgramArtifact artifact : programArtifacts) {
                try {
                    queue.put(new ProgramArtifactMessage(artifact.programId(), artifact));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            while (running.get()) {
                if (paused.get()) {
                    Thread.onSpinWait();
                    continue;
                }

                if (simulation == null) {
                    // Lazy bootstrap minimal simulation
                    var env = new org.evochora.runtime.model.Environment(
                            org.evochora.app.setup.Config.WORLD_SHAPE,
                            org.evochora.app.setup.Config.IS_TOROIDAL);
                    simulation = new org.evochora.app.Simulation(env);
                    // Seed initial world objects from program artifacts
                    for (ProgramArtifact artifact : programArtifacts) {
                        // Place machine code layout
                        for (var e : artifact.machineCodeLayout().entrySet()) {
                            int[] rel = e.getKey();
                            int value = e.getValue();
                            env.setMolecule(org.evochora.runtime.model.Molecule.fromInt(value), rel);
                        }
                        // Place additional world objects
                        for (var e : artifact.initialWorldObjects().entrySet()) {
                            int[] rel = e.getKey();
                            var pm = e.getValue();
                            var mol = new org.evochora.runtime.model.Molecule(pm.type(), pm.value());
                            env.setMolecule(mol, rel);
                        }
                    }
                }
                simulation.tick();
                IQueueMessage msg = WorldStateAdapter.fromSimulation(simulation);
                queue.put(msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("SimulationEngine error", e);
        } finally {
            log.info("SimulationEngine stopped");
        }
    }
}


