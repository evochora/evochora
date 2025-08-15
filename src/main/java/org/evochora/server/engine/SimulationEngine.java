package org.evochora.server.engine;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.server.IControllable;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.contracts.ProgramArtifactMessage;
import org.evochora.server.contracts.IQueueMessage;
import org.evochora.server.queue.ITickMessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.evochora.runtime.model.Organism;

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
    private Simulation simulation;
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

    public long getCurrentTick() {
        return simulation != null ? simulation.getCurrentTick() : -1L;
    }

    public int[] getOrganismCounts() {
        if (simulation == null) return new int[]{0,0};
        int living = 0, dead = 0;
        for (var o : simulation.getOrganisms()) {
            if (o.isDead()) dead++; else living++;
        }
        return new int[]{living, dead};
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
                            Config.WORLD_SHAPE,
                            Config.IS_TOROIDAL);
                    simulation = new Simulation(env);
                    // Seed initial world objects from program artifacts
                    for (ProgramArtifact artifact : programArtifacts) {
                        // Determine placement origin
                        int dims = org.evochora.runtime.Config.WORLD_DIMENSIONS;
                        int[] origin = UserLoadRegistry.getDesiredStart(artifact.programId());
                        if (origin == null) origin = new int[dims];

                        // Place machine code layout at origin offset
                        for (var e : artifact.machineCodeLayout().entrySet()) {
                            int[] rel = e.getKey();
                            int[] abs = new int[dims];
                            for (int i = 0; i < dims; i++) abs[i] = origin[i] + rel[i];
                            int value = e.getValue();
                            env.setMolecule(org.evochora.runtime.model.Molecule.fromInt(value), abs);
                        }
                        // Place additional world objects at origin offset
                        for (var e : artifact.initialWorldObjects().entrySet()) {
                            int[] rel = e.getKey();
                            int[] abs = new int[dims];
                            for (int i = 0; i < dims; i++) abs[i] = origin[i] + rel[i];
                            var pm = e.getValue();
                            var mol = new org.evochora.runtime.model.Molecule(pm.type(), pm.value());
                            env.setMolecule(mol, abs);
                        }
                        // Create organism for this programId starting at origin
                        int[] start = origin;
                        Organism organism = Organism.create(simulation, start,
                                org.evochora.runtime.Config.INITIAL_ORGANISM_ENERGY, simulation.getLogger());
                        organism.setProgramId(artifact.programId());
                        simulation.addOrganism(organism);
                    }
                }
                simulation.tick();
                StatusMetricsRegistry.onTick(simulation.getCurrentTick());
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


