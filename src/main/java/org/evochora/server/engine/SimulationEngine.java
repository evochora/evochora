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
import java.util.stream.Collectors;

public final class SimulationEngine implements IControllable, Runnable {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    private final ITickMessageQueue queue;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final boolean performanceMode;

    private long currentTick = 0L;
    private Simulation simulation;
    private java.util.List<ProgramArtifact> programArtifacts = java.util.Collections.emptyList();

    /**
     * NEUER KONSTRUKTOR: Standardmäßig wird der Debug-Modus verwendet.
     * Dies behebt die Kompilierungsfehler in den Tests.
     * @param queue Die Nachrichten-Warteschlange.
     */
    public SimulationEngine(ITickMessageQueue queue) {
        this(queue, false); // Standard ist Debug-Modus
    }

    public SimulationEngine(ITickMessageQueue queue, boolean performanceMode) {
        this.queue = queue;
        this.performanceMode = performanceMode;
        this.thread = new Thread(this, "SimulationEngine");
        this.thread.setDaemon(true);
    }

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
    public void pause() { paused.set(true); }

    @Override
    public void resume() { paused.set(false); }

    @Override
    public void shutdown() {
        running.set(false);
        thread.interrupt();
    }

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public boolean isPaused() { return paused.get(); }

    public long getCurrentTick() {
        return simulation != null ? simulation.getCurrentTick() : -1L;
    }

    public Simulation getSimulation() { return simulation; }

    /**
     * Performs one deterministic tick on the simulation thread-safely while allowing the engine
     * to stay paused. This method directly advances the underlying Simulation and enqueues the
     * resulting world state to the queue, so PersistenceService can persist it.
     */
    public void step() {
        if (simulation == null) return;
        // Advance one tick synchronously
        simulation.tick();
        StatusMetricsRegistry.onTick(simulation.getCurrentTick());
        try {
            queue.put(WorldStateAdapter.fromSimulation(simulation));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
            for (ProgramArtifact artifact : programArtifacts) {
                try {
                    queue.put(new ProgramArtifactMessage(artifact.programId(), artifact));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            var env = new org.evochora.runtime.model.Environment(Config.WORLD_SHAPE, Config.IS_TOROIDAL);
            simulation = new Simulation(env, performanceMode);

            if (programArtifacts != null && !programArtifacts.isEmpty()) {
                simulation.setProgramArtifacts(this.programArtifacts.stream()
                        .collect(Collectors.toMap(ProgramArtifact::programId, pa -> pa, (a, b) -> b)));
            }

            for (ProgramArtifact artifact : programArtifacts) {
                int dims = org.evochora.runtime.Config.WORLD_DIMENSIONS;
                int[] origin = UserLoadRegistry.getDesiredStart(artifact.programId());
                if (origin == null) origin = new int[dims];

                for (var e : artifact.machineCodeLayout().entrySet()) {
                    int[] rel = e.getKey();
                    int[] abs = new int[dims];
                    for (int i = 0; i < dims; i++) abs[i] = origin[i] + rel[i];
                    env.setMolecule(org.evochora.runtime.model.Molecule.fromInt(e.getValue()), abs);
                }
                for (var e : artifact.initialWorldObjects().entrySet()) {
                    int[] rel = e.getKey();
                    int[] abs = new int[dims];
                    for (int i = 0; i < dims; i++) abs[i] = origin[i] + rel[i];
                    var pm = e.getValue();
                    var mol = new org.evochora.runtime.model.Molecule(pm.type(), pm.value());
                    env.setMolecule(mol, abs);
                }
                Organism organism = Organism.create(simulation, origin,
                        org.evochora.runtime.Config.INITIAL_ORGANISM_ENERGY, simulation.getLogger());
                organism.setProgramId(artifact.programId());
                simulation.addOrganism(organism);
            }

            log.info("Saving initial state for tick 0.");
            StatusMetricsRegistry.onTick(simulation.getCurrentTick());
            IQueueMessage initialMsg = WorldStateAdapter.fromSimulation(simulation);
            queue.put(initialMsg);

            while (running.get()) {
                if (paused.get()) {
                    Thread.onSpinWait();
                    continue;
                }

                simulation.tick();
                StatusMetricsRegistry.onTick(simulation.getCurrentTick());
                IQueueMessage tickMsg = WorldStateAdapter.fromSimulation(simulation);
                queue.put(tickMsg);
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
