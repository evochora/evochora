package org.evochora.server.engine;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.config.SimulationConfiguration;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.CallBindingRegistry;
import org.evochora.runtime.worldgen.IEnergyDistributionStrategy;
import org.evochora.runtime.worldgen.EnergyStrategyFactory;
import org.evochora.runtime.model.Organism;
import org.evochora.server.IControllable;
import org.evochora.server.contracts.IQueueMessage;
import org.evochora.server.contracts.ProgramArtifactMessage;
import org.evochora.server.queue.ITickMessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public final class SimulationEngine implements IControllable, Runnable {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    private final ITickMessageQueue queue;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final boolean performanceMode;
    private final int[] worldShape;
    private final boolean isToroidal;

    private Simulation simulation;
    private java.util.List<ProgramArtifact> programArtifacts = java.util.Collections.emptyList();
    private SimulationConfiguration.OrganismDefinition[] organismDefinitions = new SimulationConfiguration.OrganismDefinition[0];
    private java.util.List<IEnergyDistributionStrategy> energyStrategies = java.util.Collections.emptyList();

    public SimulationEngine(ITickMessageQueue queue) {
        this(queue, false, new int[]{120,80}, true);
    }

    public SimulationEngine(ITickMessageQueue queue, boolean performanceMode) {
        this(queue, performanceMode, new int[]{120,80}, true);
    }

    public SimulationEngine(ITickMessageQueue queue, boolean performanceMode, int[] worldShape, boolean isToroidal) {
        this.queue = queue;
        this.performanceMode = performanceMode;
        this.worldShape = java.util.Arrays.copyOf(worldShape, worldShape.length);
        this.isToroidal = isToroidal;
        this.thread = new Thread(this, "SimulationEngine");
        this.thread.setDaemon(true);
    }

    public void setProgramArtifacts(java.util.List<ProgramArtifact> artifacts) {
        this.programArtifacts = artifacts == null ? java.util.Collections.emptyList() : artifacts;
    }

    public void setOrganismDefinitions(SimulationConfiguration.OrganismDefinition[] defs) {
        this.organismDefinitions = defs != null ? defs : new SimulationConfiguration.OrganismDefinition[0];
    }

    /**
     * Builds and installs energy distribution strategies from configuration entries.
     */
    public void setEnergyStrategies(java.util.List<org.evochora.server.config.SimulationConfiguration.EnergyStrategyConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            this.energyStrategies = java.util.Collections.emptyList();
            return;
        }
        java.util.List<IEnergyDistributionStrategy> built = new java.util.ArrayList<>();
        for (org.evochora.server.config.SimulationConfiguration.EnergyStrategyConfig cfg : configs) {
            if (cfg == null || cfg.type == null || cfg.type.isBlank()) continue;
            try {
                built.add(EnergyStrategyFactory.create(cfg.type, cfg.params));
            } catch (IllegalArgumentException ex) {
                log.warn("Ignoring unknown energy strategy type '{}': {}", cfg.type, ex.getMessage());
            } catch (Exception ex) {
                log.warn("Failed to build energy strategy '{}': {}", cfg.type, ex.getMessage());
            }
        }
        this.energyStrategies = java.util.Collections.unmodifiableList(built);
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

    public void step() {
        if (simulation == null) return;
        simulation.tick();
        // Apply energy distribution after organisms executed for this tick
        if (energyStrategies != null && !energyStrategies.isEmpty()) {
            for (IEnergyDistributionStrategy strategy : energyStrategies) {
                try {
                    strategy.distributeEnergy(simulation.getEnvironment(), simulation.getCurrentTick());
                } catch (Exception ex) {
                    log.warn("Energy strategy execution failed: {}", ex.getMessage());
                }
            }
        }
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
            if (!performanceMode) {
                for (ProgramArtifact artifact : programArtifacts) {
                    try {
                        queue.put(new ProgramArtifactMessage(artifact.programId(), artifact));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            var env = new org.evochora.runtime.model.Environment(this.worldShape, this.isToroidal);
            simulation = new Simulation(env, performanceMode);

            // New path: configure organisms from JSON if present; otherwise fallback to already-loaded artifacts path
            if (organismDefinitions != null && organismDefinitions.length > 0) {
                // Compile and place each organism set
                Compiler compiler = new Compiler();
                java.util.Map<String, ProgramArtifact> artifactsById = new java.util.HashMap<>();
                int dims = this.worldShape.length;
                for (SimulationConfiguration.OrganismDefinition def : organismDefinitions) {
                    if (def == null || def.program == null) continue;
                    // Load program from resources under org/evochora/organism/
                    String resBase = "org/evochora/organism/";
                    String resPath = resBase + def.program;
                    java.io.InputStream is = SimulationEngine.class.getClassLoader().getResourceAsStream(resPath);
                    java.util.List<String> lines;
                    String logicalName = resPath;
                    if (is != null) {
                        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                            lines = br.lines().toList();
                        }
                    } else {
                        // try filesystem path as fallback
                        java.nio.file.Path fsPath = java.nio.file.Path.of(def.program);
                        lines = java.nio.file.Files.readAllLines(fsPath, java.nio.charset.StandardCharsets.UTF_8);
                        logicalName = fsPath.toAbsolutePath().toString();
                    }
                    ProgramArtifact artifact = compiler.compile(lines, logicalName, dims);
                    artifactsById.put(artifact.programId(), artifact);
                    if (!performanceMode) {
                        try { queue.put(new ProgramArtifactMessage(artifact.programId(), artifact)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    }

                    // Placement: only fixed strategy supported now
                    if (def.placement != null && def.placement.positions != null) {
                        for (int[] origin : def.placement.positions) {
                            int[] start = origin != null ? origin : new int[dims];
                            // Place machine code and initial world objects relative to origin, also set owner for set cells
                            for (var e : artifact.machineCodeLayout().entrySet()) {
                                int[] rel = e.getKey();
                                int[] abs = new int[dims];
                                for (int i = 0; i < dims; i++) abs[i] = start[i] + rel[i];
                                env.setMolecule(org.evochora.runtime.model.Molecule.fromInt(e.getValue()), 0, abs);
                            }
                            for (var e : artifact.initialWorldObjects().entrySet()) {
                                int[] rel = e.getKey();
                                int[] abs = new int[dims];
                                for (int i = 0; i < dims; i++) abs[i] = start[i] + rel[i];
                                var pm = e.getValue();
                                var mol = new org.evochora.runtime.model.Molecule(pm.type(), pm.value());
                                env.setMolecule(mol, 0, abs);
                            }
                            // Register CALL-site bindings for this placement (absolute coordinates)
                            if (!performanceMode && artifact.callSiteBindings() != null && artifact.linearAddressToCoord() != null) {
                                org.evochora.runtime.internal.services.CallBindingRegistry registry = org.evochora.runtime.internal.services.CallBindingRegistry.getInstance();
                                for (var b : artifact.callSiteBindings().entrySet()) {
                                    int linearAddr = b.getKey();
                                    int[] rel = artifact.linearAddressToCoord().get(linearAddr);
                                    if (rel != null) {
                                        int[] abs = new int[dims];
                                        for (int i = 0; i < dims; i++) abs[i] = start[i] + rel[i];
                                        int[] drIds = b.getValue();
                                        if (drIds != null) registry.registerBindingForAbsoluteCoord(abs, drIds);
                                    }
                                }
                            }
                            // Create organism with configured energy and own placed cells
                            int energy = Math.max(0, def.initialEnergy);
                            Organism organism = Organism.create(simulation, start, energy, simulation.getLogger());
                            organism.setProgramId(artifact.programId());
                            // Set owner for all machine code cells to organism id
                            for (var e : artifact.machineCodeLayout().entrySet()) {
                                int[] rel = e.getKey();
                                int[] abs = new int[dims];
                                for (int i = 0; i < dims; i++) abs[i] = start[i] + rel[i];
                                env.setOwnerId(organism.getId(), abs);
                            }
                            for (var e : artifact.initialWorldObjects().entrySet()) {
                                int[] rel = e.getKey();
                                int[] abs = new int[dims];
                                for (int i = 0; i < dims; i++) abs[i] = start[i] + rel[i];
                                env.setOwnerId(organism.getId(), abs);
                            }
                            simulation.addOrganism(organism);
                        }
                    }
                }
                simulation.setProgramArtifacts(artifactsById);
            } else if (programArtifacts != null && !programArtifacts.isEmpty()) {
                simulation.setProgramArtifacts(this.programArtifacts.stream()
                        .collect(Collectors.toMap(ProgramArtifact::programId, pa -> pa, (a, b) -> b)));
                // legacy path: seed artifacts and create a single organism per artifact at (0,0,...)
                int dims = this.worldShape.length;
                for (ProgramArtifact artifact : programArtifacts) {
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
                            org.evochora.runtime.Config.ERROR_PENALTY_COST + 1000, simulation.getLogger());
                    organism.setProgramId(artifact.programId());
                    simulation.addOrganism(organism);
                }
            }

            // HOLEN DER REGISTRY-INSTANZ
            CallBindingRegistry bindingRegistry = CallBindingRegistry.getInstance();
            if (programArtifacts != null && !programArtifacts.isEmpty()) {
                for (ProgramArtifact artifact : programArtifacts) {
                    int dims = this.worldShape.length;
                    int[] origin = UserLoadRegistry.getDesiredStart(artifact.programId());
                    if (origin == null) origin = new int[dims];
                    if (!performanceMode) {
                        for (var binding : artifact.callSiteBindings().entrySet()) {
                            int linearAddress = binding.getKey();
                            int[] relativeCoord = artifact.linearAddressToCoord().get(linearAddress);
                            if (relativeCoord != null) {
                                int[] absoluteCoord = new int[dims];
                                for (int i = 0; i < dims; i++) {
                                    absoluteCoord[i] = origin[i] + relativeCoord[i];
                                }
                                bindingRegistry.registerBindingForAbsoluteCoord(absoluteCoord, binding.getValue());
                            }
                        }
                    }
                }
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
                // Apply configured energy strategies each tick
                if (energyStrategies != null && !energyStrategies.isEmpty()) {
                    for (IEnergyDistributionStrategy strategy : energyStrategies) {
                        try {
                            strategy.distributeEnergy(simulation.getEnvironment(), simulation.getCurrentTick());
                        } catch (Exception ex) {
                            log.warn("Energy strategy execution failed: {}", ex.getMessage());
                        }
                    }
                }
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