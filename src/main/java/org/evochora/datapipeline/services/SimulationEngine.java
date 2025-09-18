package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.datapipeline.api.contracts.RawTickData;
import org.evochora.datapipeline.api.contracts.SimulationContext;
import org.evochora.datapipeline.api.contracts.EnvironmentProperties;
import org.evochora.datapipeline.api.contracts.WorldTopology;
import org.evochora.datapipeline.api.services.ChannelBindingStatus;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.evochora.datapipeline.api.services.State;
import org.evochora.datapipeline.api.services.Direction;
import org.evochora.datapipeline.api.services.BindingState;
import org.evochora.datapipeline.api.channels.IOutputChannel;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.IRandomProvider;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.worldgen.IEnergyDistributionCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The primary data producer in the Evochora Data Pipeline.
 * <p>
 * This service runs the core simulation and publishes its state through the pipeline.
 * It wraps the existing simulation logic into the IService pattern, making it a fully
 * compliant and configurable component of the new data pipeline architecture.
 * </p>
 * <p>
 * The service is configured via HOCON configuration and supports:
 * <ul>
 *   <li>Dynamic organism placement with assembly program compilation</li>
 *   <li>Configurable energy distribution strategies</li>
 *   <li>Checkpoint-based pausing at specific tick numbers</li>
 *   <li>Resilient error handling with graceful degradation</li>
 * </ul>
 * </p>
 */
public class SimulationEngine extends AbstractService {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    // Configuration
    private final Config options;
    private final Long seed;
    private final EnvironmentProperties environmentProperties;
    private final List<OrganismConfig> organismConfigs;
    private final List<EnergyStrategyConfig> energyStrategyConfigs;
    private final int[] pauseTicks;

    // Runtime state
    private Simulation simulation;
    private IRandomProvider randomProvider;
    private String simulationRunId;
    private final AtomicLong currentTick = new AtomicLong(0);
    private int nextPauseTickIndex = 0;
    
    // Channel storage for message publishing
    private final Map<String, IOutputChannel<?>> outputChannels = new HashMap<>();

    /**
     * Creates a new SimulationEngine with the specified configuration.
     *
     * @param options The HOCON configuration options for this service
     */
    public SimulationEngine(Config options) {
        this.options = options;
        
        // Parse configuration
        this.seed = options.hasPath("seed") ? options.getLong("seed") : null;
        this.environmentProperties = parseEnvironmentProperties(options.getConfig("environment"));
        this.organismConfigs = options.hasPath("organisms") ? 
            parseOrganismConfigs(options.getConfigList("organisms")) : new ArrayList<>();
        this.energyStrategyConfigs = options.hasPath("energyStrategies") ? 
            parseEnergyStrategyConfigs(options.getConfigList("energyStrategies")) : new ArrayList<>();
        this.pauseTicks = options.hasPath("pauseTicks") ? 
            options.getIntList("pauseTicks").stream().mapToInt(i -> i).toArray() : null;
        
        if (pauseTicks != null) {
            Arrays.sort(pauseTicks); // Ensure ticks are in ascending order
        }
        
        log.info("SimulationEngine initialized with {} organisms, {} energy strategies, {} pause ticks", 
            organismConfigs.size(), energyStrategyConfigs.size(), 
            pauseTicks != null ? pauseTicks.length : 0);
    }

    @Override
    public void addOutputChannel(String channelName, IOutputChannel<?> channel) {
        // Store channel for message publishing
        outputChannels.put(channelName, channel);
        
        // Create channel binding status for this output
        ChannelBindingStatus bindingStatus = new ChannelBindingStatus(
            channelName,
            Direction.OUTPUT,
            BindingState.ACTIVE,
            0.0 // messages per second - will be updated dynamically
        );
        channelBindings.add(bindingStatus);
    }

    @Override
    public void run() {
        try {
            log.debug("Starting simulation initialization...");
            
            // Initialize random provider
            randomProvider = seed != null ? new SeededRandomProvider(seed) : new SeededRandomProvider(0L);
            
            // Create simulation environment
            org.evochora.runtime.model.EnvironmentProperties runtimeEnvProps = 
                new org.evochora.runtime.model.EnvironmentProperties(
                    environmentProperties.getWorldShape(), 
                    environmentProperties.getTopology() == WorldTopology.TORUS
                );
            Environment environment = new Environment(runtimeEnvProps);
            simulation = new Simulation(environment);
            simulation.setRandomProvider(randomProvider);
            
            // Generate unique simulation run ID
            simulationRunId = "sim_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
            
            // Initialize instruction set
            org.evochora.runtime.isa.Instruction.init();
            
            // Compile and place organisms
            List<ProgramArtifact> programArtifacts = compileAndPlaceOrganisms();
            
            // Initialize energy strategies
            List<IEnergyDistributionCreator> energyStrategies = initializeEnergyStrategies();
            
            // Publish initial context
            publishSimulationContext(programArtifacts);
            
            log.debug("Simulation initialized successfully. Starting main loop...");
            
            // Set initial state to RUNNING - simulation starts immediately
            currentState.set(State.RUNNING);
            log.debug("Simulation initialized and running at tick {}", currentTick.get());
            
            // Main simulation loop - runs continuously until service is stopped
            while (currentState.get() != State.STOPPED) {
                // Wait in paused state until resumed
                if (currentState.get() == State.PAUSED) {
                    synchronized (pauseLock) {
                        while (currentState.get() == State.PAUSED) {
                            try {
                                pauseLock.wait(); // Wait until resume() calls notifyAll()
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                log.info("Simulation interrupted during pause");
                                return;
                            }
                        }
                    }
                    log.debug("Simulation resumed at tick {}", currentTick.get());
                }
                
                // Run simulation loop when not paused
                runSimulationLoop(energyStrategies);
            }
            
        } catch (Exception e) {
            log.error("Critical error during simulation initialization", e);
            throw new RuntimeException("Simulation cannot start", e);
        } finally {
            // Service lifecycle logging is handled by ServiceManager
            // Only log simulation-specific events on DEBUG level
            State finalState = currentState.get();
            if (finalState == State.STOPPED) {
                log.debug("Simulation completed after {} ticks", currentTick.get());
            } else if (finalState == State.PAUSED) {
                log.debug("Simulation paused after {} ticks", currentTick.get());
            } else {
                log.debug("Simulation ended after {} ticks (state: {})", currentTick.get(), finalState);
            }
        }
    }

    @Override
    public ServiceStatus getServiceStatus() {
        return new ServiceStatus(currentState.get(), new ArrayList<>(channelBindings));
    }
    
    /**
     * Returns the current tick number of the simulation.
     * This method is used by the ServiceManager to display activity information.
     * 
     * @return The current tick number
     */
    public long getCurrentTick() {
        return currentTick.get();
    }
    
    @Override
    public String getActivityInfo() {
        return String.format("Tick: %d", currentTick.get());
    }

    // Private helper methods will be implemented in the next steps...
    
    private EnvironmentProperties parseEnvironmentProperties(Config envConfig) {
        List<Integer> shape = envConfig.getIntList("shape");
        String topology = envConfig.getString("topology");
        
        int[] worldShape = shape.stream().mapToInt(i -> i).toArray();
        boolean toroidal = "TORUS".equalsIgnoreCase(topology);
        
        return new EnvironmentProperties(worldShape, toroidal ? WorldTopology.TORUS : WorldTopology.BOUNDED);
    }
    
    private List<OrganismConfig> parseOrganismConfigs(List<? extends Config> organismConfigs) {
        List<OrganismConfig> configs = new ArrayList<>();
        for (Config config : organismConfigs) {
            configs.add(new OrganismConfig(config));
        }
        return configs;
    }
    
    private List<EnergyStrategyConfig> parseEnergyStrategyConfigs(List<? extends Config> strategyConfigs) {
        List<EnergyStrategyConfig> configs = new ArrayList<>();
        for (Config config : strategyConfigs) {
            // Skip empty configurations (no className field)
            if (config.hasPath("className")) {
                configs.add(new EnergyStrategyConfig(config));
            }
        }
        return configs;
    }
    
    private List<ProgramArtifact> compileAndPlaceOrganisms() {
        List<ProgramArtifact> artifacts = new ArrayList<>();
        List<CompilationError> errors = new ArrayList<>();
        
        for (OrganismConfig organismConfig : organismConfigs) {
            try {
                ProgramArtifact artifact = compileOrganismProgram(organismConfig);
                artifacts.add(artifact);
                
                // Place organism in simulation
                placeOrganism(organismConfig, artifact);
                
                log.debug("Successfully compiled and placed organism: {}", organismConfig.program);
                
            } catch (Exception e) {
                CompilationError error = new CompilationError(organismConfig.program, e.getMessage());
                errors.add(error);
                log.warn("Failed to compile organism program: {} - {}", organismConfig.program, e.getMessage());
            }
        }
        
        // Check if we have any successful compilations
        if (artifacts.isEmpty()) {
            log.error("No organisms could be compiled successfully - simulation cannot start");
            throw new RuntimeException("No organisms available for simulation");
        }
        
        if (!errors.isEmpty()) {
            log.warn("Skipping {} organisms due to compilation errors", errors.size());
        }
        
        return artifacts;
    }
    
    private ProgramArtifact compileOrganismProgram(OrganismConfig organismConfig) throws Exception {
        // Resolve program file path
        File programFile = resolveProgramFile(organismConfig.program);
        if (!programFile.exists()) {
            throw new FileNotFoundException("Assembly program not found: " + organismConfig.program);
        }
        
        // Read source code
        List<String> sourceLines = Files.readAllLines(programFile.toPath());
        
        // Compile
        org.evochora.compiler.Compiler compiler = new org.evochora.compiler.Compiler();
        org.evochora.runtime.model.EnvironmentProperties runtimeEnvProps = 
            new org.evochora.runtime.model.EnvironmentProperties(
                environmentProperties.getWorldShape(), 
                environmentProperties.getTopology() == WorldTopology.TORUS
            );
        return compiler.compile(sourceLines, programFile.getAbsolutePath(), runtimeEnvProps);
    }
    
    private File resolveProgramFile(String programPath) {
        if (Paths.get(programPath).isAbsolute()) {
            return new File(programPath);
        }
        
        // Try relative to current working directory first
        File file = new File(programPath);
        if (file.exists()) {
            return file;
        }
        
        // Try relative to project root
        file = new File("src/main/resources", programPath);
        if (file.exists()) {
            return file;
        }
        
        // Try test resources for unit tests
        file = new File("src/test/resources", programPath);
        if (file.exists()) {
            return file;
        }
        
        return new File(programPath); // Return original path for error reporting
    }
    
    private void placeOrganism(OrganismConfig organismConfig, ProgramArtifact artifact) {
        // Create organism
        Organism organism = Organism.create(
            simulation,
            organismConfig.startPosition,
            organismConfig.initialEnergy,
            simulation.getLogger()
        );
        
        organism.setProgramId(artifact.programId());
        simulation.addOrganism(organism);
        
        // Place code in environment
        for (Map.Entry<int[], Integer> entry : artifact.machineCodeLayout().entrySet()) {
            int[] relativePos = entry.getKey();
            int[] absolutePos = new int[organismConfig.startPosition.length];
            for (int i = 0; i < organismConfig.startPosition.length; i++) {
                absolutePos[i] = organismConfig.startPosition[i] + relativePos[i];
            }
            
            simulation.getEnvironment().setMolecule(
                org.evochora.runtime.model.Molecule.fromInt(entry.getValue()),
                organism.getId(),
                absolutePos
            );
        }
        
        // Place initial world objects
        for (Map.Entry<int[], org.evochora.compiler.api.PlacedMolecule> entry : artifact.initialWorldObjects().entrySet()) {
            int[] relativePos = entry.getKey();
            int[] absolutePos = new int[organismConfig.startPosition.length];
            for (int i = 0; i < organismConfig.startPosition.length; i++) {
                absolutePos[i] = organismConfig.startPosition[i] + relativePos[i];
            }
            
            org.evochora.compiler.api.PlacedMolecule pm = entry.getValue();
            simulation.getEnvironment().setMolecule(
                new org.evochora.runtime.model.Molecule(pm.type(), pm.value()),
                organism.getId(),
                absolutePos
            );
        }
    }
    
    private List<IEnergyDistributionCreator> initializeEnergyStrategies() {
        List<IEnergyDistributionCreator> strategies = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        
        for (EnergyStrategyConfig strategyConfig : energyStrategyConfigs) {
            try {
                IEnergyDistributionCreator strategy = loadEnergyStrategy(strategyConfig);
                strategies.add(strategy);
                log.debug("Successfully loaded energy strategy: {}", strategyConfig.className);
                
            } catch (Exception e) {
                errors.add(strategyConfig.className + ": " + e.getMessage());
                log.warn("Failed to load energy strategy: {} - {}", strategyConfig.className, e.getMessage());
            }
        }
        
        if (!errors.isEmpty()) {
            log.warn("Skipping {} energy strategies due to loading errors", errors.size());
        }
        
        return strategies;
    }
    
    private IEnergyDistributionCreator loadEnergyStrategy(EnergyStrategyConfig strategyConfig) throws Exception {
        Class<?> clazz = Class.forName(strategyConfig.className);
        
        // Look for constructor with (IRandomProvider, Config) signature
        try {
            java.lang.reflect.Constructor<?> constructor = clazz.getConstructor(
                IRandomProvider.class, Config.class
            );
            return (IEnergyDistributionCreator) constructor.newInstance(randomProvider, strategyConfig.options);
        } catch (NoSuchMethodException e) {
            // Fallback to default constructor if Config-based constructor doesn't exist
            log.warn("Config-based constructor not found for {}, using default constructor", strategyConfig.className);
            java.lang.reflect.Constructor<?> constructor = clazz.getConstructor();
            return (IEnergyDistributionCreator) constructor.newInstance();
        }
    }
    
    private void publishSimulationContext(List<ProgramArtifact> programArtifacts) {
        SimulationContext context = new SimulationContext();
        context.setSimulationRunId(simulationRunId);
        context.setEnvironment(environmentProperties);
        // Convert compiler ProgramArtifacts to API ProgramArtifacts (OPTIMIZED - direct compatibility!)
        List<org.evochora.datapipeline.api.contracts.ProgramArtifact> apiArtifacts = new ArrayList<>();
        for (ProgramArtifact artifact : programArtifacts) {
            org.evochora.datapipeline.api.contracts.ProgramArtifact apiArtifact = 
                new org.evochora.datapipeline.api.contracts.ProgramArtifact();
            
            // Direct field assignment - NO CONVERSION NEEDED!
            apiArtifact.setProgramId(artifact.programId());
            apiArtifact.setSources(artifact.sources());
            
            // Direct Map assignment - machineCodeLayout is already Map<int[], Integer>!
            apiArtifact.setMachineCodeLayout(artifact.machineCodeLayout());
            
            // Convert PlacedMolecule to SerializablePlacedMolecule (direct conversion)
            Map<int[], org.evochora.datapipeline.api.contracts.SerializablePlacedMolecule> serializableMolecules = new HashMap<>();
            for (Map.Entry<int[], PlacedMolecule> entry : artifact.initialWorldObjects().entrySet()) {
                PlacedMolecule molecule = entry.getValue();
                // Use type() and value() directly - they are already correctly extracted by the compiler
                org.evochora.datapipeline.api.contracts.SerializablePlacedMolecule serializableMolecule = 
                    new org.evochora.datapipeline.api.contracts.SerializablePlacedMolecule(
                        molecule.type(), 
                        molecule.value()
                    );
                serializableMolecules.put(entry.getKey(), serializableMolecule);
            }
            apiArtifact.setInitialWorldObjects(serializableMolecules);
            
            // Convert SourceInfo to SerializableSourceInfo (minimal conversion)
            Map<Integer, org.evochora.datapipeline.api.contracts.SerializableSourceInfo> serializableSourceMap = new HashMap<>();
            for (Map.Entry<Integer, SourceInfo> entry : artifact.sourceMap().entrySet()) {
                org.evochora.datapipeline.api.contracts.SerializableSourceInfo serializableSourceInfo = 
                    new org.evochora.datapipeline.api.contracts.SerializableSourceInfo(
                        entry.getValue().fileName(), 
                        entry.getValue().lineNumber(), 
                        entry.getValue().columnNumber()
                    );
                serializableSourceMap.put(entry.getKey(), serializableSourceInfo);
            }
            apiArtifact.setSourceMap(serializableSourceMap);
            
            // Direct Map assignment - labelAddressToName is already Map<Integer, String>!
            apiArtifact.setLabelAddressToName(artifact.labelAddressToName());
            
            apiArtifacts.add(apiArtifact);
        }
        context.setArtifacts(apiArtifacts);
        
        // Send to all output channels
        for (ChannelBindingStatus binding : channelBindings) {
            if (binding.direction() == Direction.OUTPUT) {
                try {
                    IOutputChannel<?> channel = outputChannels.get(binding.channelName());
                    if (channel != null) {
                        ((IOutputChannel<SimulationContext>) channel).write(context);
                        log.debug("Published SimulationContext to channel: {}", binding.channelName());
                    } else {
                        log.warn("Output channel not found: {}", binding.channelName());
                    }
                } catch (InterruptedException e) {
                    // Normal shutdown - queue is full or service is stopping
                    Thread.currentThread().interrupt();
                    break; // Exit the loop, service is shutting down
                } catch (Exception e) {
                    log.error("Failed to publish SimulationContext to channel: {}", binding.channelName(), e);
                }
            }
        }
    }
    
    private void runSimulationLoop(List<IEnergyDistributionCreator> energyStrategies) {
        while (currentState.get() == State.RUNNING) {
            try {
                // Check if we should pause at this tick
                if (shouldPauseAtCurrentTick()) {
                    logPauseTickInfo();
                    pause();
                    return; // Exit the loop, pause handling is done in run()
                }
                
                // Execute simulation tick
                simulation.tick();
                currentTick.incrementAndGet();
                
                // Apply energy strategies
                for (IEnergyDistributionCreator strategy : energyStrategies) {
                    strategy.distributeEnergy(simulation.getEnvironment(), currentTick.get());
                }
                
                // Publish tick data
                publishTickData();
                
                // Debug log every 100 ticks to see if simulation is running
                if (currentTick.get() % 100 == 0) {
                    log.debug("SimulationEngine running at tick {}", currentTick.get());
                }
                
            } catch (Exception e) {
                log.error("Error during simulation tick {}", currentTick.get(), e);
                // Continue simulation despite individual tick errors
            }
        }
    }
    
    private boolean shouldPauseAtCurrentTick() {
        if (pauseTicks == null || nextPauseTickIndex >= pauseTicks.length) {
            return false;
        }
        
        long tick = currentTick.get();
        if (tick >= pauseTicks[nextPauseTickIndex]) {
            nextPauseTickIndex++;
            return true;
        }
        
        return false;
    }
    
    private void logPauseTickInfo() {
        long currentTickValue = currentTick.get();
        
        if (nextPauseTickIndex < pauseTicks.length) {
            // There are more pause ticks coming
            StringBuilder remainingTicks = new StringBuilder();
            for (int i = nextPauseTickIndex; i < pauseTicks.length; i++) {
                if (remainingTicks.length() > 0) {
                    remainingTicks.append(", ");
                }
                remainingTicks.append(pauseTicks[i]);
            }
            
            log.info("Pausing simulation at tick {} (remaining pause ticks: {})", 
                currentTickValue, remainingTicks.toString());
        } else {
            // This was the last pause tick
            log.info("Pausing simulation at tick {} (final pause tick)", currentTickValue);
        }
    }
    
    private void publishTickData() {
        RawTickData tickData = new RawTickData();
        tickData.setSimulationRunId(simulationRunId);
        tickData.setTickNumber(currentTick.get());
        
        // Populate with actual simulation data
        tickData.setCells(extractCellStates());
        tickData.setOrganisms(extractOrganismStates());
        
        // Send to all configured output channels
        for (ChannelBindingStatus binding : channelBindings) {
            if (binding.direction() == Direction.OUTPUT) {
                try {
                    IOutputChannel<?> channel = outputChannels.get(binding.channelName());
                    if (channel != null) {
                        ((IOutputChannel<RawTickData>) channel).write(tickData);
                        log.debug("Published RawTickData to channel: {}", binding.channelName());
                    } else {
                        log.warn("Output channel not found: {}", binding.channelName());
                    }
                } catch (InterruptedException e) {
                    // Normal shutdown - queue is full or service is stopping
                    Thread.currentThread().interrupt();
                    break; // Exit the loop, service is shutting down
                } catch (Exception e) {
                    log.error("Failed to publish RawTickData to channel: {}", binding.channelName(), e);
                }
            }
        }
    }
    
    private List<org.evochora.datapipeline.api.contracts.RawCellState> extractCellStates() {
        if (simulation == null || simulation.getEnvironment() == null) {
            return new ArrayList<>();
        }
        
        Environment env = simulation.getEnvironment();
        List<org.evochora.datapipeline.api.contracts.RawCellState> cells = new ArrayList<>();
        
        // HIGH PERFORMANCE: Use sparse cell tracking for large worlds (1M+ cells)
        if (org.evochora.runtime.Config.ENABLE_SPARSE_CELL_TRACKING) {
            // Sparse: Only get occupied cells (10,000x faster for large worlds!)
            List<org.evochora.server.contracts.raw.RawCellState> occupiedCells = env.getOccupiedCells();
            if (occupiedCells != null) {
                // Convert from server.contracts to datapipeline.api.contracts
                for (org.evochora.server.contracts.raw.RawCellState serverCell : occupiedCells) {
                    org.evochora.datapipeline.api.contracts.RawCellState apiCell = 
                        new org.evochora.datapipeline.api.contracts.RawCellState();
                    apiCell.setPosition(serverCell.pos());
                    apiCell.setValue(serverCell.molecule());
                    apiCell.setOwnerId(serverCell.ownerId());
                    // Extract type from molecule value using configurable bit layout
                    apiCell.setType((serverCell.molecule() >> org.evochora.runtime.Config.TYPE_SHIFT) & ((1 << org.evochora.runtime.Config.TYPE_BITS) - 1));
                    cells.add(apiCell);
                }
                log.debug("Extracted {} occupied cells using sparse tracking", cells.size());
            } else {
                log.warn("Sparse cell tracking enabled but getOccupiedCells() returned null - falling back to full iteration");
                return extractAllCellStates(env);
            }
        } else {
            // Full iteration (only for small worlds or when sparse tracking is disabled)
            return extractAllCellStates(env);
        }
        
        return cells;
    }
    
    /**
     * Fallback method for full cell iteration when sparse tracking is not available.
     * This is much slower for large worlds but ensures compatibility.
     */
    private List<org.evochora.datapipeline.api.contracts.RawCellState> extractAllCellStates(Environment env) {
        List<org.evochora.datapipeline.api.contracts.RawCellState> cells = new ArrayList<>();
        int[] shape = env.getShape();
        int dims = shape.length;
        
        // Optimized iteration over all cells
        int[] coord = new int[dims];
        Arrays.fill(coord, 0);
        
        iterateOptimized(shape, 0, coord, () -> {
            Molecule molecule = env.getMolecule(coord);
            int ownerId = env.getOwnerId(coord);
            
            // Only include non-empty cells (empty cells are implicitly CODE:0, owner:0)
            if (molecule.toInt() != 0 || ownerId != 0) {
                org.evochora.datapipeline.api.contracts.RawCellState cell = 
                    new org.evochora.datapipeline.api.contracts.RawCellState();
                cell.setPosition(coord.clone());
                cell.setValue(molecule.toInt());
                cell.setOwnerId(ownerId);
                // Extract type from molecule value using configurable bit layout
                cell.setType((molecule.toInt() >> org.evochora.runtime.Config.TYPE_SHIFT) & ((1 << org.evochora.runtime.Config.TYPE_BITS) - 1));
                cells.add(cell);
            }
        });
        
        log.debug("Extracted {} cells using full iteration", cells.size());
        return cells;
    }
    
    /**
     * Optimized multi-dimensional iteration to avoid nested loops.
     * This is significantly faster than nested for-loops for high-dimensional worlds.
     */
    private void iterateOptimized(int[] shape, int dim, int[] coord, Runnable action) {
        if (dim == shape.length) {
            action.run();
            return;
        }
        
        for (int i = 0; i < shape[dim]; i++) {
            coord[dim] = i;
            iterateOptimized(shape, dim + 1, coord, action);
        }
    }
    
    private List<org.evochora.datapipeline.api.contracts.RawOrganismState> extractOrganismStates() {
        List<org.evochora.datapipeline.api.contracts.RawOrganismState> organisms = new ArrayList<>();
        
        if (simulation != null) {
            for (Organism organism : simulation.getOrganisms()) {
                org.evochora.datapipeline.api.contracts.RawOrganismState organismState = 
                    new org.evochora.datapipeline.api.contracts.RawOrganismState();
                
                // Convert organism data to API format
                organismState.setOrganismId(organism.getId());
                organismState.setParentId(organism.getParentId());
                organismState.setProgramId(organism.getProgramId());
                organismState.setBirthTick(organism.getBirthTick());
                organismState.setEnergy(organism.getEr());
                organismState.setDead(organism.isDead());
                
                // Convert position data
                organismState.setPosition(organism.getIp());
                
                // Convert VM state
                organismState.setDv(organism.getDv());
                organismState.setActiveDp(organism.getActiveDpIndex());
                
                // Convert complex data structures
                organismState.setDp(organism.getDps());
                organismState.setDataRegisters(convertToIntArray(organism.getDrs()));
                organismState.setProcedureRegisters(convertToIntArray(organism.getPrs()));
                organismState.setFormalParamRegisters(convertToIntArray(organism.getFprs()));
                organismState.setLocationRegisters(convertLocationRegisters(organism.getLrs()));
                organismState.setDataStack(convertDataStack(organism.getDataStack()));
                organismState.setLocationStack(new ArrayList<>(organism.getLocationStack()));
                organismState.setCallStack(convertCallStack(organism.getCallStack()));
                
                // Convert error state if organism has failed
                if (organism.isInstructionFailed()) {
                    org.evochora.datapipeline.api.contracts.OrganismErrorState errorState = 
                        new org.evochora.datapipeline.api.contracts.OrganismErrorState();
                    errorState.setReason(organism.getFailureReason());
                    errorState.setCallStackAtFailure(convertCallStack(organism.getCallStack()));
                    organismState.setErrorState(errorState);
                }
                
                organisms.add(organismState);
            }
        }
        
        return organisms;
    }
    
    private int[] convertToIntArray(List<Object> objects) {
        int[] result = new int[objects.size()];
        for (int i = 0; i < objects.size(); i++) {
            Object obj = objects.get(i);
            if (obj instanceof Integer) {
                result[i] = (Integer) obj;
            } else if (obj instanceof int[]) {
                // For int[] objects, use the first element or 0
                int[] arr = (int[]) obj;
                result[i] = arr.length > 0 ? arr[0] : 0;
            } else {
                result[i] = 0; // Default value for unsupported types
            }
        }
        return result;
    }
    
    private List<org.evochora.datapipeline.api.contracts.StackValue> convertDataStack(Deque<Object> dataStack) {
        List<org.evochora.datapipeline.api.contracts.StackValue> result = new ArrayList<>();
        for (Object obj : dataStack) {
            org.evochora.datapipeline.api.contracts.StackValue stackValue = 
                new org.evochora.datapipeline.api.contracts.StackValue();
            
            if (obj instanceof Integer) {
                stackValue.setType(org.evochora.datapipeline.api.contracts.StackValueType.LITERAL);
                stackValue.setLiteralValue((Integer) obj);
            } else if (obj instanceof int[]) {
                stackValue.setType(org.evochora.datapipeline.api.contracts.StackValueType.POSITION);
                stackValue.setPositionValue((int[]) obj);
            } else {
                // Default to literal with value 0 for unsupported types
                stackValue.setType(org.evochora.datapipeline.api.contracts.StackValueType.LITERAL);
                stackValue.setLiteralValue(0);
            }
            
            result.add(stackValue);
        }
        return result;
    }
    
    private List<int[]> convertLocationRegisters(List<Object> locationRegisters) {
        List<int[]> result = new ArrayList<>();
        for (Object obj : locationRegisters) {
            if (obj instanceof int[]) {
                result.add((int[]) obj);
            } else {
                result.add(new int[0]); // Default empty array for unsupported types
            }
        }
        return result;
    }
    
    private List<org.evochora.datapipeline.api.contracts.SerializableProcFrame> convertCallStack(Deque<Organism.ProcFrame> callStack) {
        List<org.evochora.datapipeline.api.contracts.SerializableProcFrame> result = new ArrayList<>();
        for (Organism.ProcFrame frame : callStack) {
            org.evochora.datapipeline.api.contracts.SerializableProcFrame serializableFrame = 
                new org.evochora.datapipeline.api.contracts.SerializableProcFrame();
            
            // Convert ProcFrame data
            serializableFrame.setProcedureName(frame.procName);
            serializableFrame.setReturnAddress(frame.absoluteReturnIp);
            serializableFrame.setSavedProcedureRegisters(convertObjectArrayToIntArray(frame.savedPrs));
            serializableFrame.setSavedFormalParamRegisters(convertObjectArrayToIntArray(frame.savedFprs));
            
            result.add(serializableFrame);
        }
        return result;
    }
    
    private int[] convertObjectArrayToIntArray(Object[] objects) {
        int[] result = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            Object obj = objects[i];
            if (obj instanceof Integer) {
                result[i] = (Integer) obj;
            } else if (obj instanceof int[]) {
                // For int[] objects, use the first element or 0
                int[] arr = (int[]) obj;
                result[i] = arr.length > 0 ? arr[0] : 0;
            } else {
                result[i] = 0; // Default value for unsupported types
            }
        }
        return result;
    }
    
    // Configuration classes
    private static class OrganismConfig {
        final String program;
        final int initialEnergy;
        final int[] startPosition;
        
        OrganismConfig(Config config) {
            this.program = config.getString("program");
            this.initialEnergy = config.getInt("initialEnergy");
            
            // Parse placement positions
            List<Integer> positions = config.getConfig("placement").getIntList("positions");
            this.startPosition = positions.stream().mapToInt(i -> i).toArray();
        }
    }
    
    private static class EnergyStrategyConfig {
        final String className;
        final Config options;
        
        EnergyStrategyConfig(Config config) {
            if (!config.hasPath("className")) {
                throw new IllegalArgumentException("Energy strategy configuration must have a 'className' field");
            }
            this.className = config.getString("className");
            this.options = config.hasPath("options") ? config.getConfig("options") : ConfigFactory.empty();
        }
    }
    
    private static class CompilationError {
        final String program;
        final String error;
        
        CompilationError(String program, String error) {
            this.program = program;
            this.error = error;
        }
    }
}
