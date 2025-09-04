package org.evochora.server.engine;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.IRandomProvider;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.worldgen.IEnergyDistributionCreator;
import org.evochora.runtime.worldgen.EnergyStrategyFactory;
import org.evochora.server.IControllable;
import org.evochora.server.contracts.ProgramArtifactMessage;
import org.evochora.server.contracts.raw.RawCellState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.contracts.raw.SerializableProcFrame;
import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.queue.ITickMessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class SimulationEngine implements IControllable, Runnable {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    private final ITickMessageQueue queue;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean autoPaused = new AtomicBoolean(false);
    private final org.evochora.runtime.model.EnvironmentProperties environmentProperties;
    private final Logger logger;
    private Simulation simulation;
    private List<OrganismPlacement> organismPlacements;
    private List<IEnergyDistributionCreator> energyStrategies;
    private IRandomProvider randomProvider;
    private Long seed = null;
    private long startTime = 0;
    private int[] autoPauseTicks = null; // Configuration for auto-pause ticks
    private int nextAutoPauseIndex = 0; // Index of next auto-pause tick to check
    private Long maxTicks = null; // Maximum number of ticks to run before stopping (null = no limit)
    
    // TEST FLAG: ProgramArtifact-Funktionalität ein-/ausschalten
    // true = normal (mit ProgramArtifact), false = nur Code platzieren (ohne ProgramArtifact)
    private boolean enableProgramArtifactFeatures = true; // Default-Wert

    /**
     * Creates a new SimulationEngine with the specified configuration.
     * 
     * @param queue The message queue for communication with other services
     * @param environmentProperties The environment configuration (world shape, toroidal setting)
     * @param organismPlacements The list of organisms to place in the simulation
     * @param energyStrategies The energy distribution strategies
     * @param skipProgramArtefact Whether to skip ProgramArtifact features (default: false)
     */
    public SimulationEngine(ITickMessageQueue queue, 
                           org.evochora.runtime.model.EnvironmentProperties environmentProperties,
                           List<OrganismPlacement> organismPlacements, 
                           List<IEnergyDistributionCreator> energyStrategies, 
                           boolean skipProgramArtefact) {
        this.queue = queue;
        this.environmentProperties = environmentProperties;
        this.organismPlacements = organismPlacements != null ? new java.util.ArrayList<>(organismPlacements) : new java.util.ArrayList<>();
        this.energyStrategies = energyStrategies != null ? new java.util.ArrayList<>(energyStrategies) : new java.util.ArrayList<>();
        this.randomProvider = null;
        this.thread = new Thread(this, "SimulationEngine");
        this.thread.setDaemon(true);
        this.logger = LoggerFactory.getLogger(SimulationEngine.class);
        
        // Setze ProgramArtifact-Konfiguration direkt
        this.enableProgramArtifactFeatures = !skipProgramArtefact;
        log.info("ProgramArtifact features: {} (skipProgramArtefact={})", 
                this.enableProgramArtifactFeatures ? "enabled" : "disabled", skipProgramArtefact);
    }
    
    /**
     * Creates a new SimulationEngine with ProgramArtifact features enabled by default.
     */
    public SimulationEngine(ITickMessageQueue queue, 
                           org.evochora.runtime.model.EnvironmentProperties environmentProperties,
                           List<OrganismPlacement> organismPlacements, 
                           List<IEnergyDistributionCreator> energyStrategies) {
        this(queue, environmentProperties, organismPlacements, energyStrategies, false);
    }
    
    /**
     * Simple constructor for tests with default environment.
     */
    public SimulationEngine(ITickMessageQueue queue) {
        this(queue, new org.evochora.runtime.model.EnvironmentProperties(new int[]{120, 80}, true), 
             new java.util.ArrayList<>(), new java.util.ArrayList<>());
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
        this.seed = seed;
        if (seed == null) {
            this.randomProvider = null;
        } else {
            this.randomProvider = new org.evochora.runtime.internal.services.SeededRandomProvider(seed);
        }
    }

    /**
     * Sets the auto-pause ticks configuration.
     * @param autoPauseTicks Array of tick values where simulation should auto-pause, or null to disable
     */
    public void setAutoPauseTicks(int[] autoPauseTicks) {
        this.autoPauseTicks = autoPauseTicks != null ? java.util.Arrays.copyOf(autoPauseTicks, autoPauseTicks.length) : null;
        this.nextAutoPauseIndex = 0;
        if (this.autoPauseTicks != null) {
            java.util.Arrays.sort(this.autoPauseTicks); // Ensure ticks are in ascending order
            log.info("Auto-pause ticks configured: {}", java.util.Arrays.toString(this.autoPauseTicks));
        } else {
            log.info("Auto-pause ticks disabled");
        }
    }

    /**
     * Sets the maximum number of ticks to run before stopping.
     * @param maxTicks Maximum number of ticks, or null to disable limit (run indefinitely)
     */
    public void setMaxTicks(Long maxTicks) {
        this.maxTicks = maxTicks;
        if (maxTicks != null) {
            log.info("Maximum ticks configured: {}", maxTicks);
        } else {
            log.info("Maximum ticks disabled - simulation will run indefinitely");
        }
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            startTime = System.currentTimeMillis();
            Environment env = new Environment(environmentProperties);
            simulation = new Simulation(env);
            simulation.setRandomProvider(randomProvider);
            
            String seedInfo = seed != null ? String.valueOf(seed) : "default";
            String envInfo = environmentProperties != null ? 
                String.format("[%d, %d]", environmentProperties.getWorldShape()[0], environmentProperties.getWorldShape()[1]) : "null";
            log.info("SimulationEngine: Environment {} toroidal:{} seed:{}", 
                    envInfo,
                    environmentProperties.isToroidal(),
                    seedInfo);
            
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
        autoPaused.set(false);
    }

    @Override
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            long currentTick = getCurrentTick();
            double tps = calculateTPS();
            // Only log if called from the service thread
            if (Thread.currentThread().getName().equals("SimulationEngine")) {
                log.info("SimulationEngine: graceful termination tick:{} TPS:{}", currentTick, String.format("%.2f", tps));
            }
            thread.interrupt();
        }
    }
    
    public void forceShutdown() {
        if (running.get()) {
            running.set(false);
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public boolean isPaused() { return paused.get(); }
    
    @Override
    public boolean isAutoPaused() { return autoPaused.get(); }

    private double calculateTPS() {
        long currentTime = System.currentTimeMillis();
        long currentTick = getCurrentTick();
        
        if (currentTick <= 0) {
            return 0.0;
        }
        
        // Calculate TPS since tick 0
        long timeDiff = currentTime - startTime;
        if (timeDiff > 0) {
            return (double) currentTick / (timeDiff / 1000.0);
        }
        
        return 0.0;
    }
    
    public long getCurrentTick() {
        return simulation != null ? simulation.getCurrentTick() : 0L;
    }

    public Simulation getSimulation() { return simulation; }

    /**
     * Checks if the simulation should auto-pause at the current tick.
     * @return true if auto-pause should occur, false otherwise
     */
    private boolean shouldAutoPause() {
        if (autoPauseTicks == null || autoPauseTicks.length == 0 || nextAutoPauseIndex >= autoPauseTicks.length) {
            return false;
        }
        
        long currentTick = simulation.getCurrentTick();
        if (currentTick == autoPauseTicks[nextAutoPauseIndex]) {
            nextAutoPauseIndex++;
            return true;
        }
        
        return false;
    }

    public int[] getOrganismCounts() {
        if (simulation == null) return new int[]{0, 0};
        int living = 0, dead = 0;
        for (var o : simulation.getOrganisms()) {
            if (o.isDead()) dead++; else living++;
        }
        return new int[]{living, dead};
    }

    public String getStatus() {
        if (simulation == null) {
            return "NOT_STARTED";
        }
        
        long currentTick = simulation.getCurrentTick();
        int[] counts = getOrganismCounts();
        int queueSize = queue.size();
        boolean isRunning = running.get();
        boolean isPaused = paused.get();
        double tps = calculateTPS();
        
        if (isPaused) {
            String pauseType = autoPaused.get() ? "auto-paused" : "paused";
            return String.format("%s tick:%d organisms:[%d,%d] queue:%d", 
                    pauseType, currentTick, counts[0], counts[1], queueSize);
        }
        
        return String.format("%s tick:%d organisms:[%d,%d] queue:%d TPS:%.2f", 
                isRunning ? "started" : "stopped",
                currentTick, counts[0], counts[1], queueSize, tps);
    }

    @Override
    public void run() {
        try {
            var env = new org.evochora.runtime.model.Environment(this.environmentProperties);
            simulation = new Simulation(env); // Always run in debug mode
            if (this.randomProvider == null) {
                this.randomProvider = new org.evochora.runtime.internal.services.SeededRandomProvider(0L);
            }
            simulation.setRandomProvider(this.randomProvider);

            // Place organisms from OrganismPlacement list
            if (organismPlacements != null && !organismPlacements.isEmpty()) {
                java.util.Map<String, ProgramArtifact> artifactsById = new java.util.HashMap<>();
                
                for (OrganismPlacement placement : organismPlacements) {
                    try {
                        ProgramArtifact artifact = placement.programArtifact();
                        artifactsById.put(artifact.programId(), artifact);
                        
                        // Create organism
                        org.evochora.runtime.model.Organism organism = org.evochora.runtime.model.Organism.create(
                            simulation, 
                            placement.startPosition(), 
                            placement.initialEnergy(), 
                            simulation.getLogger()
                        );
                        
                        if (enableProgramArtifactFeatures) {
                            organism.setProgramId(artifact.programId());
                        }
                        
                        simulation.addOrganism(organism);
                        
                        // Place code in environment with organism ID as owner
                        for (Map.Entry<int[], Integer> e : artifact.machineCodeLayout().entrySet()) {
                            int[] rel = e.getKey();
                            int[] abs = new int[placement.startPosition().length];
                            for (int i = 0; i < placement.startPosition().length; i++) {
                                abs[i] = placement.startPosition()[i] + rel[i];
                            }
                            simulation.getEnvironment().setMolecule(
                                org.evochora.runtime.model.Molecule.fromInt(e.getValue()), 
                                organism.getId(), 
                                abs
                            );
                        }
                        
                        // Place initial world objects with organism ID as owner
                        for (Map.Entry<int[], org.evochora.compiler.api.PlacedMolecule> e : artifact.initialWorldObjects().entrySet()) {
                            int[] rel = e.getKey();
                            int[] abs = new int[placement.startPosition().length];
                            for (int i = 0; i < placement.startPosition().length; i++) {
                                abs[i] = placement.startPosition()[i] + rel[i];
                            }
                            org.evochora.compiler.api.PlacedMolecule pm = e.getValue();
                            simulation.getEnvironment().setMolecule(
                                new org.evochora.runtime.model.Molecule(pm.type(), pm.value()), 
                                organism.getId(), 
                                abs
                            );
                        }
                        
                        // Register call-site bindings in CallBindingRegistry
                        if (enableProgramArtifactFeatures) {
                            org.evochora.runtime.internal.services.CallBindingRegistry registry = 
                                org.evochora.runtime.internal.services.CallBindingRegistry.getInstance();
                            for (Map.Entry<Integer, int[]> binding : artifact.callSiteBindings().entrySet()) {
                                int linearAddress = binding.getKey();
                                int[] relativeCoord = artifact.linearAddressToCoord().get(linearAddress);
                                if (relativeCoord != null) {
                                    int[] absoluteCoord = new int[placement.startPosition().length];
                                    for (int i = 0; i < placement.startPosition().length; i++) {
                                        absoluteCoord[i] = placement.startPosition()[i] + relativeCoord[i];
                                    }
                                    registry.registerBindingForAbsoluteCoord(absoluteCoord, binding.getValue());
                                }
                            }
                        }
                        
                        log.info("Created organism {} at position {} with program {}", 
                            organism.getId(), 
                            java.util.Arrays.toString(placement.startPosition()), 
                            artifact.programId());
                            
                    } catch (Exception e) {
                        log.warn("Failed to create organism at position {}: {}", 
                            java.util.Arrays.toString(placement.startPosition()), e.getMessage());
                    }
                }
                
                // Set program artifacts in simulation
                if (enableProgramArtifactFeatures) {
                    simulation.setProgramArtifacts(artifactsById);
                }
                
                // Send ProgramArtifacts to persistence service
                if (enableProgramArtifactFeatures) {
                    for (Map.Entry<String, ProgramArtifact> entry : artifactsById.entrySet()) {
                        try {
                            ProgramArtifactMessage artifactMsg = new ProgramArtifactMessage(entry.getKey(), entry.getValue());
                            queue.put(artifactMsg);
                            log.debug("Sent ProgramArtifact {} to persistence queue", entry.getKey());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (Exception e) {
                            log.warn("Failed to send ProgramArtifact {} to persistence queue: {}", entry.getKey(), e.getMessage());
                        }
                    }
                }
            }

            // Send initial tick state (tick 0) before starting the simulation loop
            try {
                RawTickState initialTickMsg = toRawState(simulation);
                queue.put(initialTickMsg);
                log.debug("Sent initial tick state (tick 0) to queue");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            
            // Start simulation loop
            while (running.get()) {
                if (paused.get()) {
                    // Use a short sleep instead of busy-wait to avoid consuming CPU
                    try {
                        Thread.sleep(10); // 10ms sleep is much more efficient than spin-wait
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                try {
                    // Check if we should auto-pause at this tick
                    if (shouldAutoPause()) {
                        log.info("Auto-pausing simulation at tick {}", simulation.getCurrentTick());
                        autoPaused.set(true);
                        paused.set(true);
                        continue;
                    }

                    simulation.tick();
                    
                    // Check if we've reached the maximum tick limit BEFORE writing the tick
                    if (maxTicks != null && simulation.getCurrentTick() >= maxTicks) {
                        log.info("Reached maximum tick limit ({}), stopping simulation", maxTicks);
                        running.set(false);
                        break;
                    }
                    
                    // Apply energy distribution strategies AFTER the tick
                    if (energyStrategies != null && !energyStrategies.isEmpty()) {
                        for (IEnergyDistributionCreator strategy : energyStrategies) {
                            try {
                                strategy.distributeEnergy(simulation.getEnvironment(), simulation.getCurrentTick());
                            } catch (Exception ex) {
                                log.warn("Energy strategy execution failed: {}", ex.getMessage());
                            }
                        }
                    }
                    
                    // Always create raw tick state, but throttle simulation if queue is too full
                    RawTickState tickMsg = toRawState(simulation);
                    queue.put(tickMsg);
                    
                    // Throttle simulation if persistence is falling behind
                    if (queue.size() > 1000) {
                        // Slow down simulation to let persistence catch up
                        Thread.sleep(1); // 1ms delay to reduce pressure
                        log.debug("Throttling simulation - queue size: {}", queue.size());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // InterruptedException is expected during shutdown, not an error
                    break;
                } catch (Exception e) {
                    if (running.get()) {
                        log.error("Simulation tick failed, terminating service: {}", e.getMessage());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("SimulationEngine fatal error, terminating service: {}", e.getMessage());
            }
        } finally {
            // Log graceful termination from the service thread
            if (Thread.currentThread().getName().equals("SimulationEngine")) {
                long currentTick = getCurrentTick();
                double tps = calculateTPS();
                log.info("SimulationEngine: graceful termination tick:{} TPS:{}", currentTick, String.format("%.2f", tps));
            }
        }
    }

    private RawTickState toRawState(Simulation simulation) {
        final var env = simulation.getEnvironment();
        final int[] shape = env.getShape();
        final int dims = shape.length;

        // Optimize cell iteration - only process non-empty cells
        List<RawCellState> cells = new ArrayList<>();
        int[] coord = new int[dims];
        Arrays.fill(coord, 0);
        
        // Only iterate through cells that might have content
        iterateOptimized(shape, 0, coord, () -> {
            var m = env.getMolecule(coord);
            int ownerId = env.getOwnerId(coord);
            if (m.toInt() != 0 || ownerId != 0) {
                cells.add(new RawCellState(coord.clone(), m.toInt(), ownerId));
            }
        });

        // Optimize organism processing - only process living organisms
        List<RawOrganismState> organisms = new ArrayList<>();
        for (Organism o : simulation.getOrganisms()) {
            if (o.isDead()) continue; // Skip dead organisms for performance
            
            // Minimize defensive copying - only copy essential data
            java.util.Deque<SerializableProcFrame> serializableCallStack = o.getCallStack().stream()
                    .map(f -> new SerializableProcFrame(
                            f.procName, f.absoluteReturnIp, f.savedPrs, f.savedFprs, f.fprBindings))
                    .collect(Collectors.toCollection(java.util.ArrayDeque::new));

            // Only copy stacks if they're not empty
            java.util.Deque<Object> dataStackCopy = o.getDataStack().isEmpty() ? 
                new java.util.ArrayDeque<>() : new java.util.ArrayDeque<>(o.getDataStack());
            java.util.Deque<int[]> locationStackCopy = o.getLocationStack().isEmpty() ? 
                new java.util.ArrayDeque<>() : new java.util.ArrayDeque<>(o.getLocationStack());

            organisms.add(new RawOrganismState(
                    o.getId(), o.getParentId(), o.getBirthTick(), o.getProgramId(), o.getInitialPosition(),
                    o.getIp(), o.getDv(), o.getDps(), o.getActiveDpIndex(), o.getEr(),
                    o.getDrs(), o.getPrs(), o.getFprs(), o.getLrs(),
                    dataStackCopy, locationStackCopy, serializableCallStack,
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

    private static void iterateOptimized(int[] shape, int dim, int[] coord, Runnable visitor) {
        if (dim == shape.length) {
            visitor.run();
            return;
        }
        for (int i = 0; i < shape[dim]; i++) {
            coord[dim] = i;
            iterateOptimized(shape, dim + 1, coord, visitor);
        }
    }
}