package org.evochora.datapipeline.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.datapipeline.api.contracts.CallSiteBinding;
import org.evochora.datapipeline.api.contracts.ColumnTokenLookup;
import org.evochora.datapipeline.api.contracts.FileTokenLookup;
import org.evochora.datapipeline.api.contracts.InstructionMapping;
import org.evochora.datapipeline.api.contracts.LineTokenLookup;
import org.evochora.datapipeline.api.contracts.LinearAddressToCoord;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.PlacedMoleculeMapping;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.SourceInfo;
import org.evochora.datapipeline.api.contracts.SourceLines;
import org.evochora.datapipeline.api.contracts.SourceMapEntry;
import org.evochora.datapipeline.api.contracts.PluginState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TokenInfo;
import org.evochora.datapipeline.api.contracts.TokenMapEntry;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.memory.IMemoryEstimatable;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.resume.ResumeCheckpoint;
import org.evochora.datapipeline.resume.SimulationRestorer;
import org.evochora.datapipeline.resume.SnapshotLoader;
import org.evochora.datapipeline.utils.delta.DeltaCodec;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IDeathHandler;
import org.evochora.runtime.spi.IInstructionInterceptor;
import org.evochora.runtime.spi.ISimulationPlugin;
import org.evochora.runtime.spi.ITickPlugin;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.GenomeHasher;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Organism.ProcFrame;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;

import com.google.protobuf.ByteString;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigRenderOptions;

public class SimulationEngine extends AbstractService implements IMemoryEstimatable {

    private final IOutputQueueResource<TickDataChunk> tickDataOutput;
    private final IOutputQueueResource<SimulationMetadata> metadataOutput;
    private final int samplingInterval;
    private final int accumulatedDeltaInterval;
    private final int snapshotInterval;
    private final int chunkInterval;
    private final int metricsWindowSeconds;
    private final int yieldInterval;
    private final List<Long> pauseTicks;
    private final String runId;
    private final DeltaCodec.Encoder chunkEncoder;
    private final Simulation simulation;
    private final IRandomProvider randomProvider;
    private final List<PluginWithConfig> tickPlugins;
    private final List<InterceptorWithConfig> instructionInterceptors;
    private final List<DeathHandlerWithConfig> deathHandlers;
    private final List<BirthHandlerWithConfig> birthHandlers;
    private final long seed;
    private final long startTimeMs;
    private final boolean isResume;
    private final AtomicLong currentTick = new AtomicLong(-1);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private long lastMetricTime = System.currentTimeMillis();
    private long lastTickCount = 0;
    private double ticksPerSecond = 0.0;

    // Reusable Protobuf builders for organism state extraction (avoids allocations per organism)
    private final OrganismState.Builder organismStateBuilder = OrganismState.newBuilder();
    private final Vector.Builder vectorBuilder = Vector.newBuilder();
    private final org.evochora.datapipeline.api.contracts.RegisterValue.Builder registerValueBuilder =
            org.evochora.datapipeline.api.contracts.RegisterValue.newBuilder();
    private final org.evochora.datapipeline.api.contracts.ProcFrame.Builder procFrameBuilder =
            org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder();

    // Helper record bundling program path, ID, and artifact for initialization and metadata building
    private record ProgramInfo(String programPath, String programId, ProgramArtifact artifact) {}

    // Mapping from programPath (config key) to ProgramInfo for initialization and metadata building
    private final Map<String, ProgramInfo> programInfoByPath = new HashMap<>();

    private record PluginWithConfig(ITickPlugin plugin, Config config) {}
    private record InterceptorWithConfig(IInstructionInterceptor interceptor, Config config) {}
    private record DeathHandlerWithConfig(IDeathHandler handler, Config config) {}
    private record BirthHandlerWithConfig(IBirthHandler handler, Config config) {}

    /**
     * Holds the initialized state from either resume or new simulation mode.
     * This record allows both initialization paths to produce the same output structure.
     * Includes delta compression intervals to ensure resume uses original values from metadata.
     *
     * @param resumeSnapshot checkpoint snapshot for resume mode (null for new simulations).
     *                       When present, the encoder is initialized with this snapshot so
     *                       subsequent ticks are treated as deltas within the same chunk.
     */
    private record InitializedState(
        Simulation simulation,
        IRandomProvider randomProvider,
        List<PluginWithConfig> tickPlugins,
        List<InterceptorWithConfig> instructionInterceptors,
        List<DeathHandlerWithConfig> deathHandlers,
        List<BirthHandlerWithConfig> birthHandlers,
        Map<String, ProgramInfo> programInfo,
        String runId,
        long seed,
        long startTimeMs,
        long initialTick,
        int samplingInterval,
        int accumulatedDeltaInterval,
        int snapshotInterval,
        int chunkInterval,
        TickData resumeSnapshot
    ) {}

    public SimulationEngine(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);

        // Initialize instruction set (required for both modes)
        org.evochora.runtime.isa.Instruction.init();

        // Common resource initialization
        this.tickDataOutput = initializeTickQueue();
        this.metadataOutput = initializeMetadataQueue();

        // Common configuration (intervals come from InitializedState to support resume from metadata)
        this.metricsWindowSeconds = readInt(options, "metricsWindowSeconds", 1);
        this.yieldInterval = readInt(options, "yieldInterval", 1);
        this.pauseTicks = options.hasPath("pauseTicks") ? options.getLongList("pauseTicks") : Collections.emptyList();

        // Mode-specific initialization
        this.isResume = options.hasPath("resume.enabled") && options.getBoolean("resume.enabled");
        InitializedState state = this.isResume
            ? initializeFromCheckpoint(options)
            : initializeNewSimulation(options);

        // Apply initialized state
        this.simulation = state.simulation();
        this.randomProvider = state.randomProvider();
        this.tickPlugins = state.tickPlugins();
        this.instructionInterceptors = state.instructionInterceptors();
        this.deathHandlers = state.deathHandlers();
        this.birthHandlers = state.birthHandlers();
        this.runId = state.runId();
        this.seed = state.seed();
        this.startTimeMs = state.startTimeMs();
        this.currentTick.set(state.initialTick());
        state.programInfo().forEach(programInfoByPath::put);

        // Intervals from state (config for new simulation, metadata for resume)
        this.samplingInterval = state.samplingInterval();
        this.accumulatedDeltaInterval = state.accumulatedDeltaInterval();
        this.snapshotInterval = state.snapshotInterval();
        this.chunkInterval = state.chunkInterval();

        // Common finalization - pass resume snapshot for proper encoder initialization
        this.chunkEncoder = createChunkEncoder(state.resumeSnapshot());
    }

    @SuppressWarnings("unchecked")
    private IOutputQueueResource<TickDataChunk> initializeTickQueue() {
        return (IOutputQueueResource<TickDataChunk>) getRequiredResource("tickData", IOutputQueueResource.class);
    }

    @SuppressWarnings("unchecked")
    private IOutputQueueResource<SimulationMetadata> initializeMetadataQueue() {
        return (IOutputQueueResource<SimulationMetadata>) getRequiredResource("metadataOutput", IOutputQueueResource.class);
    }

    private int readInt(Config options, String path, int defaultValue) {
        return options.hasPath(path) ? options.getInt(path) : defaultValue;
    }

    private int readPositiveInt(Config options, String path, int defaultValue) {
        int value = readInt(options, path, defaultValue);
        if (value < 1) throw new IllegalArgumentException(path + " must be >= 1");
        return value;
    }

    private DeltaCodec.Encoder createChunkEncoder(TickData resumeSnapshot) {
        long totalCellsLong = this.simulation.getEnvironment().getTotalCells();
        if (totalCellsLong > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                "World too large for simulation: " + totalCellsLong + " cells exceeds Integer.MAX_VALUE. " +
                "Reduce environment dimensions.");
        }
        int totalCells = (int) totalCellsLong;

        if (resumeSnapshot != null) {
            log.debug("Creating encoder with checkpoint snapshot at tick {}", resumeSnapshot.getTickNumber());
            return DeltaCodec.Encoder.forResume(
                resumeSnapshot, this.runId, totalCells,
                this.accumulatedDeltaInterval, this.snapshotInterval, this.chunkInterval);
        }
        return new DeltaCodec.Encoder(
            this.runId, totalCells,
            this.accumulatedDeltaInterval, this.snapshotInterval, this.chunkInterval);
    }

    /**
     * Initializes simulation state from a checkpoint for resume mode.
     * <p>
     * All simulation-affecting configuration is read from the metadata's resolvedConfigJson
     * to ensure deterministic continuation of the original simulation.
     */
    private InitializedState initializeFromCheckpoint(Config options) {
        log.debug("Initializing from checkpoint (resume mode)");

        if (!options.hasPath("resume.runId")) {
            throw new IllegalArgumentException(
                "resume.runId is required when resume.enabled=true");
        }
        String runId = options.getString("resume.runId");

        // Get storage resource (read-only, no write needed since we don't truncate)
        IBatchStorageRead storageRead = (IBatchStorageRead) getRequiredResource("resumeStorage", IBatchStorageRead.class);

        try {
            // Load checkpoint (always from last complete chunk's snapshot)
            SnapshotLoader snapshotLoader = new SnapshotLoader(storageRead);
            ResumeCheckpoint checkpoint = snapshotLoader.loadLatestCheckpoint(runId);
            log.debug("Loaded checkpoint at tick {}, will resume from tick {}",
                checkpoint.getCheckpointTick(), checkpoint.getResumeFromTick());

            // Parse original config from metadata
            SimulationMetadata metadata = checkpoint.metadata();
            Config originalConfig = com.typesafe.config.ConfigFactory.parseString(
                metadata.getResolvedConfigJson());

            // Restore state using original config
            long seed = metadata.getInitialSeed();
            IRandomProvider randomProvider = new SeededRandomProvider(seed);
            SimulationRestorer.RestoredState restored = SimulationRestorer.restore(checkpoint, randomProvider);

            // Convert to internal format (SimulationRestorer already separates by type)
            List<PluginWithConfig> plugins = restored.tickPlugins().stream()
                .map(p -> new PluginWithConfig(p.plugin(), p.config()))
                .toList();

            List<InterceptorWithConfig> interceptors = restored.instructionInterceptors().stream()
                .map(i -> new InterceptorWithConfig(i.interceptor(), i.config()))
                .toList();

            List<DeathHandlerWithConfig> deathHandlersList = restored.deathHandlers().stream()
                .map(d -> new DeathHandlerWithConfig(d.handler(), d.config()))
                .toList();

            List<BirthHandlerWithConfig> birthHandlersList = restored.birthHandlers().stream()
                .map(b -> new BirthHandlerWithConfig(b.handler(), b.config()))
                .toList();

            Map<String, ProgramInfo> programInfo = new HashMap<>();
            restored.programArtifacts().forEach((id, artifact) ->
                programInfo.put(id, new ProgramInfo(id, id, artifact)));

            log.debug("Restored {} organisms from checkpoint", restored.simulation().getOrganisms().size());

            // Read intervals from original config (must match original simulation!)
            return new InitializedState(
                restored.simulation(),
                randomProvider,
                plugins,
                interceptors,
                deathHandlersList,
                birthHandlersList,
                programInfo,
                runId,
                seed,
                metadata.getStartTimeMs(),
                checkpoint.getResumeFromTick() - 1,
                readPositiveInt(originalConfig, "samplingInterval", 1),
                readInt(originalConfig, "accumulatedDeltaInterval", SimulationParameters.DEFAULT_ACCUMULATED_DELTA_INTERVAL),
                readInt(originalConfig, "snapshotInterval", SimulationParameters.DEFAULT_SNAPSHOT_INTERVAL),
                readInt(originalConfig, "chunkInterval", SimulationParameters.DEFAULT_CHUNK_INTERVAL),
                checkpoint.snapshot()  // Pass snapshot to prime the encoder
            );
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to load checkpoint: " + e.getMessage(), e);
        }
    }

    /**
     * Initializes a new simulation from configuration.
     */
    private InitializedState initializeNewSimulation(Config options) {
        long startTimeMs = System.currentTimeMillis();
        long seed = options.hasPath("seed") ? options.getLong("seed") : System.currentTimeMillis();

        List<? extends Config> organismConfigs = options.getConfigList("organisms");
        if (organismConfigs.isEmpty()) {
            throw new IllegalArgumentException("At least one organism must be configured.");
        }

        // Compile programs
        Map<String, ProgramArtifact> compiledPrograms = new HashMap<>();
        Map<String, ProgramInfo> programInfo = new HashMap<>();
        Compiler compiler = new Compiler();

        boolean isToroidal = "TORUS".equalsIgnoreCase(options.getString("environment.topology"));
        EnvironmentProperties envProps = new EnvironmentProperties(
            options.getIntList("environment.shape").stream().mapToInt(i -> i).toArray(), isToroidal);

        for (Config orgConfig : organismConfigs) {
            String programPath = orgConfig.getString("program");
            if (!programInfo.containsKey(programPath)) {
                try {
                    String source = Files.readString(Paths.get(programPath));
                    ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), programPath, envProps);
                    programInfo.put(programPath, new ProgramInfo(programPath, artifact.programId(), artifact));
                    compiledPrograms.put(artifact.programId(), artifact);
                } catch (CompilationException e) {
                    throw new IllegalArgumentException("Failed to compile program: " + programPath, e);
                } catch (IOException e) {
                    throw new IllegalArgumentException("Failed to read program file: " + programPath, e);
                }
            }
        }

        // Create runtime components
        IRandomProvider randomProvider = new SeededRandomProvider(seed);

        // Initialize all plugins with automatic type detection
        List<PluginWithConfig> tickPluginsList = new ArrayList<>();
        List<InterceptorWithConfig> interceptorsList = new ArrayList<>();
        List<DeathHandlerWithConfig> deathHandlersList = new ArrayList<>();
        List<BirthHandlerWithConfig> birthHandlersList = new ArrayList<>();
        initializePlugins(options.getConfigList("plugins"), randomProvider, tickPluginsList, interceptorsList, deathHandlersList, birthHandlersList);

        Config runtimeConfig = options.hasPath("runtime") ? options.getConfig("runtime") : com.typesafe.config.ConfigFactory.empty();
        ThermodynamicPolicyManager policyManager = new ThermodynamicPolicyManager(
            runtimeConfig.hasPath("thermodynamics") ? runtimeConfig.getConfig("thermodynamics") : com.typesafe.config.ConfigFactory.empty());
        Config organismConfig = runtimeConfig.hasPath("organism") ? runtimeConfig.getConfig("organism") : com.typesafe.config.ConfigFactory.empty();

        org.evochora.runtime.label.ILabelMatchingStrategy labelMatchingStrategy =
            Environment.createLabelMatchingStrategy(
                runtimeConfig.hasPath("label-matching") ? runtimeConfig.getConfig("label-matching") : null);

        Environment environment = new Environment(envProps, labelMatchingStrategy);
        Simulation simulation = new Simulation(environment, policyManager, organismConfig);
        simulation.setRandomProvider(randomProvider);
        simulation.setProgramArtifacts(compiledPrograms);

        // Register tick plugins with simulation
        for (PluginWithConfig pwc : tickPluginsList) {
            simulation.addTickPlugin(pwc.plugin());
        }

        // Register instruction interceptors with simulation
        for (InterceptorWithConfig iwc : interceptorsList) {
            simulation.addInstructionInterceptor(iwc.interceptor());
        }

        // Register death handlers with simulation
        for (DeathHandlerWithConfig dhc : deathHandlersList) {
            simulation.addDeathHandler(dhc.handler());
        }

        // Register birth handlers with simulation
        for (BirthHandlerWithConfig bhc : birthHandlersList) {
            simulation.addBirthHandler(bhc.handler());
        }

        // Create and place organisms
        int worldDimensions = envProps.getWorldShape().length;
        for (Config orgConfig : organismConfigs) {
            List<Integer> positions = orgConfig.getIntList("placement.positions");
            if (positions.size() != worldDimensions) {
                throw new IllegalArgumentException(
                    "Organism placement mismatch: World has " + worldDimensions +
                    " dimensions but placement has " + positions.size() + " coordinates");
            }

            String programPath = orgConfig.getString("program");
            ProgramInfo info = programInfo.get(programPath);
            int[] startPosition = positions.stream().mapToInt(i -> i).toArray();

            Organism organism = Organism.create(simulation, startPosition, orgConfig.getInt("initialEnergy"), log);
            organism.setProgramId(info.programId());
            simulation.addOrganism(organism);
            placeOrganismCodeAndObjects(simulation, organism, info.artifact(), startPosition);

            // Compute genome hash for initial organism after code placement
            long genomeHash = GenomeHasher.computeGenomeHash(
                simulation.getEnvironment(),
                organism.getId(),
                organism.getInitialPosition()
            );
            organism.setGenomeHash(genomeHash);
            simulation.registerGenomeHash(genomeHash);
        }

        // Generate run ID
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSS"))
            + "-" + UUID.randomUUID().toString();

        return new InitializedState(
            simulation, randomProvider, tickPluginsList, interceptorsList, deathHandlersList, birthHandlersList, programInfo, runId, seed, startTimeMs, -1,
            readPositiveInt(options, "samplingInterval", 1),
            readInt(options, "accumulatedDeltaInterval", SimulationParameters.DEFAULT_ACCUMULATED_DELTA_INTERVAL),
            readInt(options, "snapshotInterval", SimulationParameters.DEFAULT_SNAPSHOT_INTERVAL),
            readInt(options, "chunkInterval", SimulationParameters.DEFAULT_CHUNK_INTERVAL),
            null  // No resume snapshot for new simulations
        );
    }

    @Override
    protected void logStarted() {
        EnvironmentProperties envProps = simulation.getEnvironment().getProperties();
        String worldDims = String.join("×", Arrays.stream(envProps.getWorldShape()).mapToObj(String::valueOf).toArray(String[]::new));
        String topology = envProps.isToroidal() ? "TORUS" : "BOUNDED";
        String pluginNames = tickPlugins.stream()
                .map(p -> p.plugin().getClass().getSimpleName())
                .collect(java.util.stream.Collectors.joining(", "));

        int ticksPerChunk = chunkEncoder.getSamplesPerChunk();
        if (isResume) {
            log.info("SimulationEngine RESUMED: world=[{}, {}], organisms={}, tickPlugins={} ({}), seed={}, sampling={}, ticksPerChunk={}, runId={}, resumeFromTick={}",
                    worldDims, topology, simulation.getOrganisms().size(), tickPlugins.size(), pluginNames, seed, samplingInterval, ticksPerChunk, runId, currentTick.get() + 1);
        } else {
            log.info("SimulationEngine started: world=[{}, {}], organisms={}, tickPlugins={} ({}), seed={}, sampling={}, ticksPerChunk={}, runId={}",
                    worldDims, topology, simulation.getOrganisms().size(), tickPlugins.size(), pluginNames, seed, samplingInterval, ticksPerChunk, runId);
        }
    }

    @Override
    protected void run() throws InterruptedException {
        // Only send metadata for fresh runs, not for resume (metadata already exists)
        if (!isResume) {
            try {
                metadataOutput.put(buildMetadataMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Interrupted while sending initial metadata during shutdown");
                throw e; // Let AbstractService handle it as normal shutdown
            }
        } else {
            log.debug("Resume mode: skipping metadata send (already exists for run {})", runId);
        }

        // Check isStopRequested() for graceful shutdown (in addition to state and interrupt)
        while ((getCurrentState() == State.RUNNING || getCurrentState() == State.PAUSED)
                && !isStopRequested() && !Thread.currentThread().isInterrupted()) {
            checkPause();

            simulation.tick();
            long tick = currentTick.incrementAndGet();

            // Yield to other threads/processes to prevent system freezing
            if (yieldInterval > 0 && tick % yieldInterval == 0) {
                Thread.yield();
            }

            if (tick % samplingInterval == 0) {
                try {
                    captureSampledTick(tick);
                } catch (InterruptedException e) {
                    // Shutdown signal received while sending tick data - this is expected
                    log.debug("Interrupted while sending tick data for tick {} during shutdown", tick);
                    throw e; // Re-throw to exit cleanly
                } catch (Exception e) {
                    log.warn("Failed to capture or send tick data for tick {}", tick);
                    recordError("SEND_ERROR", "Failed to send tick data", String.format("Tick: %d", tick));
                }
            }

            if (shouldAutoPause(tick)) {
                log.info("{} auto-paused at tick {} due to pauseTicks configuration", getClass().getSimpleName(), tick);
                pause();
                continue;
            }
        }

        // Note: No flushPartialChunk() - partial chunks cause duplicate/shifted boundaries on resume.
        // Only complete chunks are persisted; partial data is discarded and regenerated on resume.

        log.info("Simulation loop finished.");
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        super.addCustomMetrics(metrics);
        
        long now = System.currentTimeMillis();
        long windowMs = metricsWindowSeconds * 1000L;
        if (now - lastMetricTime > windowMs) {
            ticksPerSecond = (double) (currentTick.get() - lastTickCount) * 1000.0 / (now - lastMetricTime);
            lastMetricTime = now;
            lastTickCount = currentTick.get();
        }

        // Take snapshot to avoid ConcurrentModificationException when simulation thread modifies list
        List<Organism> organismsSnapshot = new ArrayList<>(simulation.getOrganisms());

        // Add SimulationEngine-specific metrics
        metrics.put("current_tick", currentTick.get());
        metrics.put("organisms_alive", organismsSnapshot.stream().filter(o -> !o.isDead()).count());
        metrics.put("organisms_total", (long) organismsSnapshot.size());
        metrics.put("messages_sent", messagesSent.get());
        metrics.put("sampling_interval", samplingInterval);
        metrics.put("ticks_per_second", ticksPerSecond);
    }

    private boolean shouldAutoPause(long tick) { return pauseTicks.contains(tick); }

    /**
     * Captures the current simulation state for a sampled tick.
     * <p>
     * This method extracts all organism states, plugin states, and RNG state,
     * then passes them to the DeltaCodec.Encoder. If a complete chunk is produced,
     * it is sent to the tick data output queue.
     *
     * @param tick the tick number to capture
     * @return true if a complete chunk was produced and sent, false otherwise
     * @throws InterruptedException if interrupted while sending to queue
     */
    private boolean captureSampledTick(long tick) throws InterruptedException {
        List<OrganismState> organismStates = extractOrganismStates();
        List<PluginState> pluginStates = extractPluginStates();
        ByteString rngState = ByteString.copyFrom(randomProvider.saveState());

        java.util.Optional<TickDataChunk> chunk = chunkEncoder.captureTick(
                tick,
                simulation.getEnvironment(),
                organismStates,
                simulation.getTotalOrganismsCreatedCount(),
                simulation.getTotalUniqueGenomesCount(),
                simulation.getAllGenomesEverSeen(),
                rngState,
                pluginStates);

        if (chunk.isPresent()) {
            tickDataOutput.put(chunk.get());
            messagesSent.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * Initializes plugins from configuration with automatic type detection.
     * <p>
     * Each plugin is instantiated and then classified based on which interfaces it implements:
     * <ul>
     *   <li>ITickPlugin - added to tickPlugins list, executed each tick for environment manipulation</li>
     *   <li>IInstructionInterceptor - added to interceptors list, intercepts planned instructions</li>
     * </ul>
     * A plugin can implement multiple interfaces and will be registered in all applicable lists.
     *
     * @param configs the plugin configurations from "plugins" config key
     * @param random the random provider for deterministic plugin behavior
     * @param tickPlugins output list for ITickPlugin instances
     * @param interceptors output list for IInstructionInterceptor instances
     * @param deathHandlers output list for IDeathHandler instances
     * @param birthHandlers output list for IBirthHandler instances
     */
    private void initializePlugins(
            List<? extends Config> configs,
            IRandomProvider random,
            List<PluginWithConfig> tickPlugins,
            List<InterceptorWithConfig> interceptors,
            List<DeathHandlerWithConfig> deathHandlers,
            List<BirthHandlerWithConfig> birthHandlers) {

        for (Config config : configs) {
            try {
                String className = config.getString("className");
                Config pluginOptions = config.getConfig("options");

                Object plugin = Class.forName(className)
                        .getConstructor(IRandomProvider.class, com.typesafe.config.Config.class)
                        .newInstance(random, pluginOptions);

                // A plugin can implement multiple interfaces
                if (plugin instanceof ITickPlugin tickPlugin) {
                    tickPlugins.add(new PluginWithConfig(tickPlugin, pluginOptions));
                }
                if (plugin instanceof IInstructionInterceptor interceptor) {
                    interceptors.add(new InterceptorWithConfig(interceptor, pluginOptions));
                }
                if (plugin instanceof IDeathHandler deathHandler) {
                    deathHandlers.add(new DeathHandlerWithConfig(deathHandler, pluginOptions));
                }
                if (plugin instanceof IBirthHandler birthHandler) {
                    birthHandlers.add(new BirthHandlerWithConfig(birthHandler, pluginOptions));
                }

                // Warn if plugin implements no known interface
                if (!(plugin instanceof ITickPlugin) && !(plugin instanceof IInstructionInterceptor)
                        && !(plugin instanceof IDeathHandler) && !(plugin instanceof IBirthHandler)) {
                    log.warn("Plugin {} does not implement ITickPlugin, IInstructionInterceptor, IDeathHandler, or IBirthHandler", className);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(
                    "Failed to instantiate plugin: " + config.getString("className"), e);
            }
        }
    }

    /**
     * Builds the metadata message for a new simulation.
     * <p>
     * Only stores data that cannot be derived from config:
     * <ul>
     *   <li>runId, startTimeMs - runtime-generated identifiers</li>
     *   <li>seed - the actual seed used (important when auto-generated)</li>
     *   <li>programs - compiled artifacts with machine code</li>
     *   <li>resolvedConfigJson - all other configuration</li>
     * </ul>
     */
    private SimulationMetadata buildMetadataMessage() {
        SimulationMetadata.Builder builder = SimulationMetadata.newBuilder();
        builder.setSimulationRunId(this.runId);
        builder.setStartTimeMs(this.startTimeMs);
        builder.setInitialSeed(this.seed);

        // Add compiled programs (cannot be derived from config)
        simulation.getProgramArtifacts().values().forEach(artifact ->
            builder.addPrograms(convertProgramArtifact(artifact)));

        // Store complete config - all other values are read from here during resume
        builder.setResolvedConfigJson(options.root().render(ConfigRenderOptions.concise()));

        return builder.build();
    }

    private OrganismState extractOrganismState(Organism o) {
        organismStateBuilder.clear();
        vectorBuilder.clear();
        registerValueBuilder.clear();

        organismStateBuilder.setOrganismId(o.getId());
        if (o.getParentId() != null) organismStateBuilder.setParentId(o.getParentId());
        organismStateBuilder.setBirthTick(o.getBirthTick());
        organismStateBuilder.setProgramId(o.getProgramId());
        organismStateBuilder.setEnergy(o.getEr());

        organismStateBuilder.setIp(convertVectorReuse(o.getIp(), vectorBuilder));
        organismStateBuilder.setInitialPosition(convertVectorReuse(o.getInitialPosition(), vectorBuilder));
        organismStateBuilder.setDv(convertVectorReuse(o.getDv(), vectorBuilder));

        for (int[] dp : o.getDps()) {
            organismStateBuilder.addDataPointers(convertVectorReuse(dp, vectorBuilder));
        }
        organismStateBuilder.setActiveDpIndex(o.getActiveDpIndex());

        for (Object rv : o.getDrs()) {
            organismStateBuilder.addDataRegisters(convertRegisterValueReuse(rv, registerValueBuilder, vectorBuilder));
        }
        for (Object rv : o.getPrs()) {
            organismStateBuilder.addProcedureRegisters(convertRegisterValueReuse(rv, registerValueBuilder, vectorBuilder));
        }
        for (Object rv : o.getFprs()) {
            organismStateBuilder.addFormalParamRegisters(convertRegisterValueReuse(rv, registerValueBuilder, vectorBuilder));
        }
        for (Object loc : o.getLrs()) {
            organismStateBuilder.addLocationRegisters(convertVectorReuse((int[]) loc, vectorBuilder));
        }
        for (Object rv : o.getDataStack()) {
            organismStateBuilder.addDataStack(convertRegisterValueReuse(rv, registerValueBuilder, vectorBuilder));
        }
        for (int[] loc : o.getLocationStack()) {
            organismStateBuilder.addLocationStack(convertVectorReuse(loc, vectorBuilder));
        }
        for (ProcFrame frame : o.getCallStack()) {
            organismStateBuilder.addCallStack(convertProcFrameReuse(frame));
        }

        organismStateBuilder.setIsDead(o.isDead());
        organismStateBuilder.setInstructionFailed(o.isInstructionFailed());
        if (o.getFailureReason() != null) organismStateBuilder.setFailureReason(o.getFailureReason());
        if (o.getFailureCallStack() != null) {
            for (ProcFrame frame : o.getFailureCallStack()) {
                organismStateBuilder.addFailureCallStack(convertProcFrameReuse(frame));
            }
        }

        // Instruction execution data
        Organism.InstructionExecutionData executionData = o.getLastInstructionExecution();
        if (executionData != null) {
            organismStateBuilder.setInstructionOpcodeId(executionData.opcodeId());
            for (Integer arg : executionData.rawArguments()) {
                organismStateBuilder.addInstructionRawArguments(arg);
            }
            organismStateBuilder.setInstructionEnergyCost(executionData.energyCost());
            organismStateBuilder.setInstructionEntropyDelta(executionData.entropyDelta());

            // Register values before execution (for annotation display)
            if (executionData.registerValuesBefore() != null && !executionData.registerValuesBefore().isEmpty()) {
                for (java.util.Map.Entry<Integer, Object> entry : executionData.registerValuesBefore().entrySet()) {
                    int registerId = entry.getKey();
                    Object registerValue = entry.getValue();
                    org.evochora.datapipeline.api.contracts.RegisterValue protoValue =
                        convertRegisterValueReuse(registerValue, registerValueBuilder, vectorBuilder);
                    organismStateBuilder.putInstructionRegisterValuesBefore(registerId, protoValue);
                }
            }
        }

        // IP and DV before fetch
        organismStateBuilder.setIpBeforeFetch(convertVectorReuse(o.getIpBeforeFetch(), vectorBuilder));
        organismStateBuilder.setDvBeforeFetch(convertVectorReuse(o.getDvBeforeFetch(), vectorBuilder));

        // Special registers
        organismStateBuilder.setEntropyRegister(o.getSr());
        organismStateBuilder.setMoleculeMarkerRegister(o.getMr());
        organismStateBuilder.setGenomeHash(o.getGenomeHash());

        return organismStateBuilder.build();
    }
    
    /**
     * Extracts organism states for all living organisms.
     * Used by DeltaCodec.Encoder for delta compression.
     */
    private List<OrganismState> extractOrganismStates() {
        return simulation.getOrganisms().stream()
                .filter(o -> !o.isDead())
                .map(this::extractOrganismState)
                .toList();
    }
    
    /**
     * Extracts plugin states for all plugins (tick plugins, interceptors, death handlers, and birth handlers).
     * Used by DeltaCodec.Encoder for delta compression.
     * <p>
     * Each plugin instance is serialized exactly once, even if it implements
     * multiple plugin interfaces.
     */
    private List<PluginState> extractPluginStates() {
        // Collect unique plugin instances (a plugin may be in multiple lists if it implements multiple interfaces)
        Set<ISimulationPlugin> uniquePlugins = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PluginWithConfig p : tickPlugins) {
            uniquePlugins.add(p.plugin());
        }
        for (InterceptorWithConfig i : instructionInterceptors) {
            uniquePlugins.add(i.interceptor());
        }
        for (DeathHandlerWithConfig d : deathHandlers) {
            uniquePlugins.add(d.handler());
        }
        for (BirthHandlerWithConfig b : birthHandlers) {
            uniquePlugins.add(b.handler());
        }

        // Serialize each unique plugin exactly once
        List<PluginState> states = new ArrayList<>();
        for (ISimulationPlugin plugin : uniquePlugins) {
            states.add(PluginState.newBuilder()
                    .setPluginClass(plugin.getClass().getName())
                    .setStateBlob(ByteString.copyFrom(plugin.saveState()))
                    .build());
        }

        return states;
    }

    private static org.evochora.datapipeline.api.contracts.ProgramArtifact convertProgramArtifact(ProgramArtifact artifact) {
        org.evochora.datapipeline.api.contracts.ProgramArtifact.Builder builder =
                org.evochora.datapipeline.api.contracts.ProgramArtifact.newBuilder();

        builder.setProgramId(artifact.programId());
        artifact.sources().forEach((fileName, lines) ->
                builder.putSources(fileName, SourceLines.newBuilder().addAllLines(lines).build()));

        artifact.machineCodeLayout().forEach((pos, instruction) ->
                builder.addMachineCodeLayout(InstructionMapping.newBuilder()
                        .setPosition(convertVector(pos))
                        .setInstruction(instruction)));

        artifact.initialWorldObjects().forEach((pos, molecule) ->
                builder.addInitialWorldObjects(PlacedMoleculeMapping.newBuilder()
                        .setPosition(convertVector(pos))
                        .setMolecule(org.evochora.datapipeline.api.contracts.PlacedMolecule.newBuilder()
                                .setType(molecule.type())
                                .setValue(molecule.value()))));

        artifact.sourceMap().forEach((address, sourceInfo) ->
                builder.addSourceMap(SourceMapEntry.newBuilder()
                        .setLinearAddress(address)
                        .setSourceInfo(convertSourceInfo(sourceInfo))));

        artifact.callSiteBindings().forEach((address, registerIds) ->
                builder.addCallSiteBindings(CallSiteBinding.newBuilder()
                        .setLinearAddress(address)
                        .addAllRegisterIds(java.util.Arrays.stream(registerIds)
                                .boxed()
                                .collect(java.util.stream.Collectors.toList()))));

        builder.putAllRelativeCoordToLinearAddress(artifact.relativeCoordToLinearAddress());

        artifact.linearAddressToCoord().forEach((address, coord) ->
                builder.addLinearAddressToCoord(LinearAddressToCoord.newBuilder()
                        .setLinearAddress(address)
                        .setCoord(convertVector(coord))));

        builder.putAllRegisterAliasMap(artifact.registerAliasMap());

        artifact.procNameToParamNames().forEach((procName, params) -> {
            org.evochora.datapipeline.api.contracts.ParameterNames.Builder paramsBuilder = 
                    org.evochora.datapipeline.api.contracts.ParameterNames.newBuilder();
            for (org.evochora.compiler.api.ParamInfo param : params) {
                // Always explicitly set type, even for default values (0 = PARAM_TYPE_REF),
                // to ensure the field is serialized in JSON output
                org.evochora.datapipeline.api.contracts.ParamInfo.Builder paramBuilder = 
                        org.evochora.datapipeline.api.contracts.ParamInfo.newBuilder()
                        .setName(param.name())
                        .setType(param.type().toProtobuf());
                paramsBuilder.addParams(paramBuilder.build());
            }
            builder.putProcNameToParamNames(procName, paramsBuilder.build());
        });

        artifact.tokenMap().forEach((sourceInfo, tokenInfo) ->
                builder.addTokenMap(TokenMapEntry.newBuilder()
                        .setSourceInfo(convertSourceInfo(sourceInfo))
                        .setTokenInfo(convertTokenInfo(tokenInfo))));

        artifact.tokenLookup().forEach((fileName, lineMap) ->
                builder.addTokenLookup(FileTokenLookup.newBuilder()
                        .setFileName(fileName)
                        .addAllLines(lineMap.entrySet().stream().map(lineEntry ->
                                LineTokenLookup.newBuilder()
                                        .setLineNumber(lineEntry.getKey())
                                        .addAllColumns(lineEntry.getValue().entrySet().stream().map(colEntry ->
                                                ColumnTokenLookup.newBuilder()
                                                        .setColumnNumber(colEntry.getKey())
                                                        .addAllTokens(colEntry.getValue().stream().map(SimulationEngine::convertTokenInfo).toList())
                                                        .build()
                                        ).toList())
                                        .build()
                        ).toList())));

        artifact.sourceLineToInstructions().forEach((sourceLineKey, instructions) -> {
            org.evochora.datapipeline.api.contracts.MachineInstructionInfoList.Builder listBuilder =
                    org.evochora.datapipeline.api.contracts.MachineInstructionInfoList.newBuilder();
            for (org.evochora.compiler.api.MachineInstructionInfo info : instructions) {
                listBuilder.addInstructions(org.evochora.datapipeline.api.contracts.MachineInstructionInfo.newBuilder()
                        .setLinearAddress(info.linearAddress())
                        .setOpcode(info.opcode())
                        .setOperandsAsString(info.operandsAsString() != null ? info.operandsAsString() : "")
                        .setSynthetic(info.synthetic())
                        .build());
            }
            builder.putSourceLineToInstructions(sourceLineKey, listBuilder.build());
        });

        // Label hash value mappings for fuzzy jump display
        builder.putAllLabelValueToName(artifact.labelValueToName());
        builder.putAllLabelNameToValue(artifact.labelNameToValue());

        return builder.build();
    }

    private static SourceInfo convertSourceInfo(org.evochora.compiler.api.SourceInfo sourceInfo) {
        return SourceInfo.newBuilder()
                .setFileName(sourceInfo.fileName())
                .setLineNumber(sourceInfo.lineNumber())
                .setColumnNumber(sourceInfo.columnNumber())
                .build();
    }

    private static TokenInfo convertTokenInfo(org.evochora.compiler.api.TokenInfo tokenInfo) {
        return TokenInfo.newBuilder()
                .setTokenText(tokenInfo.tokenText())
                .setTokenType(tokenInfo.tokenType().name())
                .setScope(tokenInfo.scope())
                .build();
    }

    private static Vector convertVector(int[] components) {
        Vector.Builder builder = Vector.newBuilder();
        if (components != null) Arrays.stream(components).forEach(builder::addComponents);
        return builder.build();
    }

    private static Vector convertVectorReuse(int[] components, Vector.Builder builder) {
        builder.clear();
        if (components != null) {
            for (int c : components) {
                builder.addComponents(c);
            }
        }
        return builder.build();
    }

    private static org.evochora.datapipeline.api.contracts.RegisterValue convertRegisterValueReuse(
            Object rv, org.evochora.datapipeline.api.contracts.RegisterValue.Builder registerBuilder, Vector.Builder vectorBuilder) {
        registerBuilder.clear();
        if (rv instanceof Integer) {
            registerBuilder.setScalar((Integer) rv);
        } else if (rv instanceof int[]) {
            registerBuilder.setVector(convertVectorReuse((int[]) rv, vectorBuilder));
        } else {
            throw new IllegalStateException(
                "RegisterValue must be Integer or int[], but got: " +
                (rv == null ? "null" : rv.getClass().getName()));
        }
        return registerBuilder.build();
    }

    private org.evochora.datapipeline.api.contracts.ProcFrame convertProcFrameReuse(ProcFrame frame) {
        procFrameBuilder.clear();
        procFrameBuilder
                .setProcName(frame.procName)
                .setAbsoluteReturnIp(convertVectorReuse(frame.absoluteReturnIp, vectorBuilder))
                .setAbsoluteCallIp(convertVectorReuse(frame.absoluteCallIp, vectorBuilder))
                .putAllFprBindings(frame.fprBindings);

        if (frame.savedPrs != null) {
            for (Object rv : frame.savedPrs) {
                procFrameBuilder.addSavedPrs(convertRegisterValueReuse(rv, registerValueBuilder, vectorBuilder));
            }
        }

        if (frame.savedFprs != null) {
            for (Object rv : frame.savedFprs) {
                procFrameBuilder.addSavedFprs(convertRegisterValueReuse(rv, registerValueBuilder, vectorBuilder));
            }
        }

        return procFrameBuilder.build();
    }

    private void placeOrganismCodeAndObjects(Simulation sim, Organism organism, ProgramArtifact artifact, int[] startPosition) {
        // Place code in environment
        // ProgramArtifact guarantees deterministic iteration order (sorted by coordinate in Emitter)
        for (Map.Entry<int[], Integer> entry : artifact.machineCodeLayout().entrySet()) {
            int[] relativePos = entry.getKey();
            int[] absolutePos = new int[startPosition.length];
            for (int i = 0; i < startPosition.length; i++) {
                absolutePos[i] = startPosition[i] + relativePos[i];
            }

            org.evochora.runtime.model.Molecule molecule = org.evochora.runtime.model.Molecule.fromInt(entry.getValue());
            // CODE:0 should always have owner=0 (represents empty cell)
            int ownerId = (molecule.type() == org.evochora.runtime.Config.TYPE_CODE && molecule.toScalarValue() == 0) ? 0 : organism.getId();
            sim.getEnvironment().setMolecule(molecule, ownerId, absolutePos);
        }

        // Place initial world objects
        for (Map.Entry<int[], org.evochora.compiler.api.PlacedMolecule> entry : artifact.initialWorldObjects().entrySet()) {
            int[] relativePos = entry.getKey();
            int[] absolutePos = new int[startPosition.length];
            for (int i = 0; i < startPosition.length; i++) {
                absolutePos[i] = startPosition[i] + relativePos[i];
            }

            org.evochora.compiler.api.PlacedMolecule pm = entry.getValue();
            // CODE:0 should always have owner=0 (represents empty cell)
            int ownerId = (pm.type() == org.evochora.runtime.Config.TYPE_CODE && pm.value() == 0) ? 0 : organism.getId();
            sim.getEnvironment().setMolecule(
                new org.evochora.runtime.model.Molecule(pm.type(), pm.value()),
                ownerId,
                absolutePos
            );
        }
    }
    
    // ==================== IMemoryEstimatable ====================
    
    /**
     * {@inheritDoc}
     * <p>
     * Estimates memory for the SimulationEngine which holds the entire
     * environment and all organisms in RAM, plus the Encoder state.
     * <p>
     * <strong>Major components:</strong>
     * <ul>
     *   <li>Environment cells array: totalCells × 8 bytes (int molecule + int ownerId)</li>
     *   <li>Organisms: maxOrganisms × ~2KB (registers, stacks, code reference)</li>
     *   <li>Compiled programs: cached ProgramArtifacts</li>
     *   <li>Encoder: current snapshot + accumulated deltas + change tracking BitSet</li>
     * </ul>
     * <p>
     * This is typically the largest memory consumer in the pipeline.
     */
    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        List<MemoryEstimate> estimates = new ArrayList<>();
        
        // 1. Environment core arrays - grid[] + ownerGrid[] = 8 bytes/cell
        long coreArrayBytes = (long) params.totalCells() * 8;
        estimates.add(new MemoryEstimate(
            serviceName + " (Environment arrays)",
            coreArrayBytes,
            String.format("%d cells × 8 bytes (grid + ownerGrid)", params.totalCells()),
            MemoryEstimate.Category.SERVICE_BATCH
        ));
        
        // 2. Environment sparse tracking structures (occupiedIndices, cellsByOwner)
        // occupiedIndices: IntOpenHashSet - worst case ~24 bytes per occupied cell
        // cellsByOwner: Int2ObjectOpenHashMap<IntOpenHashSet> - variable per organism
        // changedSinceLastReset: BitSet - totalCells / 8 bytes
        // Estimate: 50% cell occupancy × 24 bytes + BitSet + overhead
        long sparseTrackingBytes = (params.totalCells() / 2) * 24  // occupiedIndices at 50% occupancy
                                 + (params.totalCells() + 7) / 8   // changedSinceLastReset BitSet
                                 + (long) params.maxOrganisms() * 200;  // cellsByOwner (avg cells per organism)
        estimates.add(new MemoryEstimate(
            serviceName + " (Environment tracking)",
            sparseTrackingBytes,
            String.format("occupiedIndices + cellsByOwner (%d orgs) + BitSet", params.maxOrganisms()),
            MemoryEstimate.Category.SERVICE_BATCH
        ));
        
        // 3. Organisms in memory - realistic worst-case estimate
        // Per organism breakdown:
        //   - Base object + fields + int[] arrays: ~1 KB
        //   - dataStack (128 max × 16 bytes boxed): ~2 KB
        //   - locationStack (64 max × 28 bytes int[]): ~2 KB
        //   - callStack (realistic ~20 ProcFrames × 600 bytes): ~12 KB
        // Total: ~17 KB per organism (conservative for typical deep recursion)
        // Note: Worst-case with full 128-frame call stack would be ~82 KB
        long bytesPerOrganism = 17 * 1024; // ~17KB per organism
        long organismsBytes = (long) params.maxOrganisms() * bytesPerOrganism;
        estimates.add(new MemoryEstimate(
            serviceName + " (Organisms)",
            organismsBytes,
            String.format("%d max organisms × ~17 KB (registers, stacks incl. ~20 call frames)", params.maxOrganisms()),
            MemoryEstimate.Category.SERVICE_BATCH
        ));
        
        // 3. Compiled programs cache - estimate ~100KB per unique program
        long compiledProgramsBytes = (long) programInfoByPath.size() * 100 * 1024;
        if (compiledProgramsBytes > 0) {
            estimates.add(new MemoryEstimate(
                serviceName + " (Compiled Programs)",
                compiledProgramsBytes,
                String.format("%d programs × ~100 KB", programInfoByPath.size()),
                MemoryEstimate.Category.SERVICE_BATCH
            ));
        }
        
        // 4. Encoder state for delta compression
        // - currentSnapshot: 1 full TickData (bytesPerTick)
        // - currentDeltas: up to (samplesPerChunk - 1) deltas
        // - accumulatedSinceSnapshot: BitSet for change tracking (totalCells / 8 bytes)
        long chunkBuilderBytes = 0;
        
        // Current snapshot in memory
        chunkBuilderBytes += params.estimateBytesPerTick();
        
        // Accumulated deltas (worst case: all samples before chunk completion)
        int maxDeltas = params.ticksPerChunk() - 1;
        chunkBuilderBytes += (long) maxDeltas * params.estimateBytesPerDelta();
        
        // BitSet for change tracking: totalCells bits = totalCells / 8 bytes
        long bitSetBytes = (params.totalCells() + 7) / 8;
        chunkBuilderBytes += bitSetBytes;
        
        estimates.add(new MemoryEstimate(
            serviceName + " (Encoder)",
            chunkBuilderBytes,
            String.format("1 snapshot + %d deltas + BitSet (%d cells), %d ticks/chunk",
                maxDeltas, params.totalCells(), params.ticksPerChunk()),
            MemoryEstimate.Category.SERVICE_BATCH
        ));
        
        return estimates;
    }
}