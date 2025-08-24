package org.evochora.server.engine;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.config.SimulationConfiguration;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.CallBindingRegistry;
import org.evochora.runtime.worldgen.IEnergyDistributionCreator;
import org.evochora.runtime.worldgen.EnergyStrategyFactory;
import org.evochora.runtime.model.Organism;
import org.evochora.server.IControllable;
import org.evochora.server.contracts.ProgramArtifactMessage;
import org.evochora.server.contracts.raw.RawCellState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.contracts.raw.SerializableProcFrame;
import org.evochora.server.queue.ITickMessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    private java.util.List<IEnergyDistributionCreator> energyStrategies = java.util.Collections.emptyList();
    private org.evochora.runtime.internal.services.IRandomProvider randomProvider;

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

    public void setEnergyStrategies(java.util.List<org.evochora.server.config.SimulationConfiguration.EnergyStrategyConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            this.energyStrategies = java.util.Collections.emptyList();
            return;
        }
        java.util.List<IEnergyDistributionCreator> built = new java.util.ArrayList<>();
        for (org.evochora.server.config.SimulationConfiguration.EnergyStrategyConfig cfg : configs) {
            if (cfg == null || cfg.type == null || cfg.type.isBlank()) continue;
            try {
                org.evochora.runtime.internal.services.IRandomProvider prov = this.randomProvider != null ? this.randomProvider : new org.evochora.runtime.internal.services.SeededRandomProvider(0L);
                built.add(EnergyStrategyFactory.create(cfg.type, cfg.params, prov));
            } catch (IllegalArgumentException ex) {
                log.warn("Ignoring unknown energy strategy type '{}': {}", cfg.type, ex.getMessage());
            } catch (Exception ex) {
                log.warn("Failed to build energy strategy '{}': {}", cfg.type, ex.getMessage());
            }
        }
        this.energyStrategies = java.util.Collections.unmodifiableList(built);
    }

    public void setSeed(java.lang.Long seed) {
        if (seed == null) {
            this.randomProvider = null;
        } else {
            this.randomProvider = new org.evochora.runtime.internal.services.SeededRandomProvider(seed);
        }
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
        if (energyStrategies != null && !energyStrategies.isEmpty()) {
            for (IEnergyDistributionCreator strategy : energyStrategies) {
                try {
                    strategy.distributeEnergy(simulation.getEnvironment(), simulation.getCurrentTick());
                } catch (Exception ex) {
                    log.warn("Energy strategy execution failed: {}", ex.getMessage());
                }
            }
        }
        StatusMetricsRegistry.onTick(simulation.getCurrentTick());
        try {
            // NEU: Erzeuge rohen Tick-Zustand
            RawTickState rts = toRawState(simulation);
            queue.put(rts);
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
            if (this.randomProvider == null) {
                this.randomProvider = new org.evochora.runtime.internal.services.SeededRandomProvider(0L);
            }
            simulation.setRandomProvider(this.randomProvider);

            if (organismDefinitions != null && organismDefinitions.length > 0) {
                Compiler compiler = new Compiler();
                java.util.Map<String, ProgramArtifact> artifactsById = new java.util.HashMap<>();
                int dims = this.worldShape.length;
                for (SimulationConfiguration.OrganismDefinition def : organismDefinitions) {
                    if (def == null || def.program == null) continue;
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
                        java.nio.file.Path fsPath = java.nio.file.Path.of(def.program);
                        lines = java.nio.file.Files.readAllLines(fsPath, java.nio.charset.StandardCharsets.UTF_8);
                        logicalName = fsPath.toAbsolutePath().toString();
                    }
                    ProgramArtifact artifact = compiler.compile(lines, logicalName, dims);
                    artifactsById.put(artifact.programId(), artifact);
                    if (!performanceMode) {
                        try { queue.put(new ProgramArtifactMessage(artifact.programId(), artifact)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    }

                    if (def.placement != null && def.placement.positions != null) {
                        for (int[] origin : def.placement.positions) {
                            int[] start = origin != null ? origin : new int[dims];
                            int energy = Math.max(0, def.initialEnergy);
                            Organism organism = Organism.create(simulation, start, energy, simulation.getLogger());
                            organism.setProgramId(artifact.programId());

                            for (var e : artifact.machineCodeLayout().entrySet()) {
                                int[] rel = e.getKey();
                                int[] abs = new int[dims];
                                for (int i = 0; i < dims; i++) abs[i] = start[i] + rel[i];
                                env.setMolecule(org.evochora.runtime.model.Molecule.fromInt(e.getValue()), organism.getId(), abs);
                            }
                            for (var e : artifact.initialWorldObjects().entrySet()) {
                                int[] rel = e.getKey();
                                int[] abs = new int[dims];
                                for (int i = 0; i < dims; i++) abs[i] = start[i] + rel[i];
                                var pm = e.getValue();
                                var mol = new org.evochora.runtime.model.Molecule(pm.type(), pm.value());
                                env.setMolecule(mol, organism.getId(), abs);
                            }

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
                            env.setOwnerId(organism.getId(), start);
                            simulation.addOrganism(organism);
                        }
                    }
                }
                simulation.setProgramArtifacts(artifactsById);
            } else if (programArtifacts != null && !programArtifacts.isEmpty()) {
                simulation.setProgramArtifacts(this.programArtifacts.stream()
                        .collect(Collectors.toMap(ProgramArtifact::programId, pa -> pa, (a, b) -> b)));
                int dims = this.worldShape.length;
                for (ProgramArtifact artifact : programArtifacts) {
                    int[] origin = UserLoadRegistry.getDesiredStart(artifact.programId());
                    if (origin == null) origin = new int[dims];
                    Organism organism = Organism.create(simulation, origin,
                            org.evochora.runtime.Config.ERROR_PENALTY_COST + 1000, simulation.getLogger());
                    organism.setProgramId(artifact.programId());
                    for (var e : artifact.machineCodeLayout().entrySet()) {
                        int[] rel = e.getKey();
                        int[] abs = new int[dims];
                        for (int i = 0; i < dims; i++) abs[i] = origin[i] + rel[i];
                        env.setMolecule(org.evochora.runtime.model.Molecule.fromInt(e.getValue()), organism.getId(), abs);
                    }
                    for (var e : artifact.initialWorldObjects().entrySet()) {
                        int[] rel = e.getKey();
                        int[] abs = new int[dims];
                        // BUGFIX: 'start' existiert hier nicht, es muss 'origin' sein.
                        for (int i = 0; i < dims; i++) abs[i] = origin[i] + rel[i];
                        var pm = e.getValue();
                        var mol = new org.evochora.runtime.model.Molecule(pm.type(), pm.value());
                        env.setMolecule(mol, organism.getId(), abs);
                    }

                    for (var e : artifact.machineCodeLayout().entrySet()) {
                        int[] rel = e.getKey();
                        int[] abs = new int[dims];
                        for (int i = 0; i < dims; i++) abs[i] = origin[i] + rel[i];
                        env.setOwnerId(organism.getId(), abs);
                    }
                    for (var e : artifact.initialWorldObjects().entrySet()) {
                        int[] rel = e.getKey();
                        int[] abs = new int[dims];
                        for (int i = 0; i < dims; i++) abs[i] = origin[i] + rel[i];
                        env.setOwnerId(organism.getId(), abs);
                    }
                    env.setOwnerId(organism.getId(), origin);
                    simulation.addOrganism(organism);
                }
            }

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
            RawTickState initialMsg = toRawState(simulation);
            queue.put(initialMsg);

            while (running.get()) {
                if (paused.get()) {
                    Thread.onSpinWait();
                    continue;
                }

                simulation.tick();
                if (energyStrategies != null && !energyStrategies.isEmpty()) {
                    for (IEnergyDistributionCreator strategy : energyStrategies) {
                        try {
                            strategy.distributeEnergy(simulation.getEnvironment(), simulation.getCurrentTick());
                        } catch (Exception ex) {
                            log.warn("Energy strategy execution failed: {}", ex.getMessage());
                        }
                    }
                }
                StatusMetricsRegistry.onTick(simulation.getCurrentTick());
                RawTickState tickMsg = toRawState(simulation);
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

    private RawTickState toRawState(Simulation simulation) {
        final var env = simulation.getEnvironment();
        final int[] shape = env.getShape();
        final int dims = shape.length;

        List<RawCellState> cells = new ArrayList<>();
        int[] coord = new int[dims];
        Arrays.fill(coord, 0);
        iterate(shape, 0, coord, () -> {
            var m = env.getMolecule(coord);
            int ownerId = env.getOwnerId(coord);
            if (m.toInt() != 0 || ownerId != 0) {
                cells.add(new RawCellState(coord.clone(), m.toInt(), ownerId));
            }
        });

        List<RawOrganismState> organisms = new ArrayList<>();
        for (Organism o : simulation.getOrganisms()) {
            java.util.Deque<SerializableProcFrame> serializableCallStack = o.getCallStack().stream()
                    .map(f -> new SerializableProcFrame(
                            f.procName, f.absoluteReturnIp, f.savedPrs, f.savedFprs, f.fprBindings))
                    .collect(Collectors.toCollection(java.util.ArrayDeque::new));

            organisms.add(new RawOrganismState(
                    o.getId(), o.getParentId(), o.getBirthTick(), o.getProgramId(), o.getInitialPosition(),
                    o.getIp(), o.getDv(), o.getDps(), o.getActiveDpIndex(), o.getEr(),
                    o.getDrs(), o.getPrs(), o.getFprs(), o.getLrs(),
                    o.getDataStack(), o.getLocationStack(), serializableCallStack,
                    o.isDead(), o.isInstructionFailed(), o.getFailureReason(),
                    o.shouldSkipIpAdvance(), o.getIpBeforeFetch(), o.getDvBeforeFetch()
            ));
        }

        return new RawTickState(simulation.getCurrentTick(), organisms, cells);
    }

    private static void iterate(int[] shape, int dim, int[] coord, Runnable visitor) {
        if (dim == shape.length) {
            visitor.run();
            return;
        }
        for (int i = 0; i < shape[dim]; i++) {
            coord[dim] = i;
            iterate(shape, dim + 1, coord, visitor);
        }
    }
}