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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.datapipeline.api.contracts.CallSiteBinding;
import org.evochora.datapipeline.api.contracts.ColumnTokenLookup;
import org.evochora.datapipeline.api.contracts.TickPluginConfig;
import org.evochora.datapipeline.api.contracts.EnvironmentConfig;
import org.evochora.datapipeline.api.contracts.FileTokenLookup;
import org.evochora.datapipeline.api.contracts.InitialOrganismSetup;
import org.evochora.datapipeline.api.contracts.InstructionMapping;
import org.evochora.datapipeline.api.contracts.LineTokenLookup;
import org.evochora.datapipeline.api.contracts.LinearAddressToCoord;
import org.evochora.datapipeline.api.contracts.OrganismConfig;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.PlacedMoleculeMapping;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.SourceInfo;
import org.evochora.datapipeline.api.contracts.SourceLines;
import org.evochora.datapipeline.api.contracts.SourceMapEntry;
import org.evochora.datapipeline.api.contracts.PluginState;
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
import org.evochora.runtime.spi.ITickPlugin;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
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
    private final List<Long> pauseTicks;
    private final String runId;
    private final DeltaCodec.Encoder chunkEncoder;
    private final Simulation simulation;
    private final IRandomProvider randomProvider;
    private final List<PluginWithConfig> tickPlugins;
    private final long seed;
    private final long startTimeMs;
    private final boolean isResume;
    private final AtomicLong currentTick = new AtomicLong(-1);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private long lastMetricTime = System.currentTimeMillis();
    private long lastTickCount = 0;
    private double ticksPerSecond = 0.0;

    // Helper record bundling program path, ID, and artifact for initialization and metadata building
    private record ProgramInfo(String programPath, String programId, ProgramArtifact artifact) {}

    // Mapping from programPath (config key) to ProgramInfo for initialization and metadata building
    private final Map<String, ProgramInfo> programInfoByPath = new HashMap<>();

    private record PluginWithConfig(ITickPlugin plugin, Config config) {}

    /**
     * Holds the initialized state from either resume or new simulation mode.
     * This record allows both initialization paths to produce the same output structure.
     */
    private record InitializedState(
        Simulation simulation,
        IRandomProvider randomProvider,
        List<PluginWithConfig> tickPlugins,
        Map<String, ProgramInfo> programInfo,
        String runId,
        long seed,
        long startTimeMs,
        long initialTick
    ) {}

    public SimulationEngine(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);

        // Initialize instruction set (required for both modes)
        org.evochora.runtime.isa.Instruction.init();

        // Common resource initialization
        this.tickDataOutput = initializeTickQueue();
        this.metadataOutput = initializeMetadataQueue();

        // Common configuration
        this.samplingInterval = readPositiveInt(options, "samplingInterval", 1);
        this.accumulatedDeltaInterval = readInt(options, "accumulatedDeltaInterval", SimulationParameters.DEFAULT_ACCUMULATED_DELTA_INTERVAL);
        this.snapshotInterval = readInt(options, "snapshotInterval", SimulationParameters.DEFAULT_SNAPSHOT_INTERVAL);
        this.chunkInterval = readInt(options, "chunkInterval", SimulationParameters.DEFAULT_CHUNK_INTERVAL);
        this.metricsWindowSeconds = readInt(options, "metricsWindowSeconds", 1);
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
        this.runId = state.runId();
        this.seed = state.seed();
        this.startTimeMs = state.startTimeMs();
        this.currentTick.set(state.initialTick());
        state.programInfo().forEach(programInfoByPath::put);

        // Common finalization
        this.chunkEncoder = createChunkEncoder();
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

    private DeltaCodec.Encoder createChunkEncoder() {
        long totalCellsLong = this.simulation.getEnvironment().getTotalCells();
        if (totalCellsLong > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                "World too large for simulation: " + totalCellsLong + " cells exceeds Integer.MAX_VALUE. " +
                "Reduce environment dimensions.");
        }
        return new DeltaCodec.Encoder(
            this.runId, (int) totalCellsLong,
            this.accumulatedDeltaInterval, this.snapshotInterval, this.chunkInterval);
    }

    /**
     * Initializes simulation state from a checkpoint for resume mode.
     */
    private InitializedState initializeFromCheckpoint(Config options) {
        log.debug("Initializing from checkpoint (resume mode)");

        if (!options.hasPath("resume.runId")) {
            throw new IllegalStateException(
                "Configuration 'resume.runId' is required for resume mode. " +
                "Set pipeline.runId via config (e.g., -Dpipeline.runId=<RUN_ID>)");
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

            // Restore state
            long seed = checkpoint.metadata().getInitialSeed();
            IRandomProvider randomProvider = new SeededRandomProvider(seed);
            SimulationRestorer.RestoredState restored = SimulationRestorer.restore(checkpoint, randomProvider);

            // Convert to internal format
            List<PluginWithConfig> plugins = restored.tickPlugins().stream()
                .map(p -> new PluginWithConfig(p.plugin(), p.config()))
                .toList();

            Map<String, ProgramInfo> programInfo = new HashMap<>();
            restored.programArtifacts().forEach((id, artifact) ->
                programInfo.put(id, new ProgramInfo(id, id, artifact)));

            log.debug("Restored {} organisms from checkpoint", restored.simulation().getOrganisms().size());

            return new InitializedState(
                restored.simulation(),
                randomProvider,
                plugins,
                programInfo,
                runId,
                seed,
                checkpoint.metadata().getStartTimeMs(),
                checkpoint.getResumeFromTick() - 1
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load checkpoint: " + e.getMessage(), e);
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
        List<PluginWithConfig> plugins = initializeTickPlugins(options.getConfigList("tickPlugins"), randomProvider);

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

        // Register tick plugins
        for (PluginWithConfig pwc : plugins) {
            simulation.addTickPlugin(pwc.plugin());
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
        }

        // Generate run ID
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSS"))
            + "-" + UUID.randomUUID().toString();

        return new InitializedState(simulation, randomProvider, plugins, programInfo, runId, seed, startTimeMs, -1);
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

            if (tick % samplingInterval == 0) {
                try {
                    // Use DeltaCodec.Encoder for delta compression
                    List<OrganismState> organismStates = extractOrganismStates();
                    List<PluginState> pluginStates = extractPluginStates();
                    ByteString rngState = ByteString.copyFrom(randomProvider.saveState());
                    
                    java.util.Optional<TickDataChunk> chunk = chunkEncoder.captureTick(
                            tick,
                            simulation.getEnvironment(),
                            organismStates,
                            simulation.getTotalOrganismsCreatedCount(),
                            rngState,
                            pluginStates);
                    
                    if (chunk.isPresent()) {
                        tickDataOutput.put(chunk.get());
                        messagesSent.incrementAndGet();
                    }
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
        
        // Flush any partial chunk on shutdown
        try {
            java.util.Optional<TickDataChunk> partialChunk = chunkEncoder.flushPartialChunk();
            if (partialChunk.isPresent()) {
                tickDataOutput.put(partialChunk.get());
                messagesSent.incrementAndGet();
                log.info("Flushed partial chunk with {} ticks on shutdown", partialChunk.get().getTickCount());
            }
        } catch (InterruptedException e) {
            log.debug("Interrupted while flushing partial chunk during shutdown");
            Thread.currentThread().interrupt();
        }
        
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

    private List<PluginWithConfig> initializeTickPlugins(List<? extends Config> configs, IRandomProvider random) {
        return configs.stream().map(config -> {
            try {
                ITickPlugin plugin = (ITickPlugin) Class.forName(config.getString("className"))
                        .getConstructor(IRandomProvider.class, com.typesafe.config.Config.class)
                        .newInstance(random, config.getConfig("options"));
                return new PluginWithConfig(plugin, config.getConfig("options"));
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Failed to instantiate tick plugin: " + config.getString("className"), e);
            }
        }).toList();
    }

    private SimulationMetadata buildMetadataMessage() {
        SimulationMetadata.Builder builder = SimulationMetadata.newBuilder();
        builder.setSimulationRunId(this.runId);
        builder.setStartTimeMs(this.startTimeMs);
        builder.setInitialSeed(this.seed);
        builder.setSamplingInterval(this.samplingInterval);

        EnvironmentProperties envProps = this.simulation.getEnvironment().getProperties();
        EnvironmentConfig.Builder envConfigBuilder = EnvironmentConfig.newBuilder();
        envConfigBuilder.setDimensions(envProps.getWorldShape().length);
        for (int dim : envProps.getWorldShape()) {
            envConfigBuilder.addShape(dim);
        }
        for (int i = 0; i < envProps.getWorldShape().length; i++) {
            envConfigBuilder.addToroidal(envProps.isToroidal());
        }
        builder.setEnvironment(envConfigBuilder.build());

        tickPlugins.forEach(pluginWithConfig -> {
            TickPluginConfig.Builder pluginBuilder = TickPluginConfig.newBuilder();
            pluginBuilder.setPluginClass(pluginWithConfig.plugin().getClass().getName());
            pluginBuilder.setConfigJson(pluginWithConfig.config().root().render(ConfigRenderOptions.concise()));
            builder.addTickPlugins(pluginBuilder.build());
        });

        simulation.getProgramArtifacts().values().forEach(artifact -> builder.addPrograms(convertProgramArtifact(artifact)));

        options.getConfigList("organisms").forEach(orgConfig -> {
            InitialOrganismSetup.Builder orgSetupBuilder = InitialOrganismSetup.newBuilder();
            String programPath = orgConfig.getString("program");
            ProgramInfo programInfo = programInfoByPath.get(programPath);
            if (programInfo != null) {
                orgSetupBuilder.setProgramId(programInfo.programId());
            }
            if (orgConfig.hasPath("id")) {
                orgSetupBuilder.setOrganismId(orgConfig.getInt("id"));
            }
            orgSetupBuilder.setPosition(convertVector(orgConfig.getIntList("placement.positions").stream().mapToInt(i->i).toArray()));
            orgSetupBuilder.setInitialEnergy(orgConfig.getInt("initialEnergy"));
            builder.addInitialOrganisms(orgSetupBuilder.build());
        });

        if (options.hasPath("metadata")) {
            options.getConfig("metadata").entrySet().forEach(entry -> {
                builder.putUserMetadata(entry.getKey(), entry.getValue().unwrapped().toString());
            });
        }

        builder.setResolvedConfigJson(options.root().render(ConfigRenderOptions.concise()));

        // Add organism configuration from runtime.organism
        Config organismConfig = this.simulation.getOrganismConfig();
        if (organismConfig != null) {
            OrganismConfig.Builder orgConfigBuilder = OrganismConfig.newBuilder();
            orgConfigBuilder.setMaxEnergy(organismConfig.getInt("max-energy"));
            orgConfigBuilder.setMaxEntropy(organismConfig.getInt("max-entropy"));
            orgConfigBuilder.setErrorPenaltyCost(organismConfig.getInt("error-penalty-cost"));
            builder.setOrganismConfig(orgConfigBuilder.build());
        }

        return builder.build();
    }

    private OrganismState extractOrganismState(Organism o) {
        OrganismState.Builder builder = OrganismState.newBuilder();
        Vector.Builder vectorBuilder = Vector.newBuilder();
        org.evochora.datapipeline.api.contracts.RegisterValue.Builder registerBuilder =
                org.evochora.datapipeline.api.contracts.RegisterValue.newBuilder();

        builder.setOrganismId(o.getId());
        if (o.getParentId() != null) builder.setParentId(o.getParentId());
        builder.setBirthTick(o.getBirthTick());
        builder.setProgramId(o.getProgramId());
        builder.setEnergy(o.getEr());

        builder.setIp(convertVectorReuse(o.getIp(), vectorBuilder));
        builder.setInitialPosition(convertVectorReuse(o.getInitialPosition(), vectorBuilder));
        builder.setDv(convertVectorReuse(o.getDv(), vectorBuilder));

        for (int[] dp : o.getDps()) {
            builder.addDataPointers(convertVectorReuse(dp, vectorBuilder));
        }
        builder.setActiveDpIndex(o.getActiveDpIndex());

        for (Object rv : o.getDrs()) {
            builder.addDataRegisters(convertRegisterValueReuse(rv, registerBuilder, vectorBuilder));
        }
        for (Object rv : o.getPrs()) {
            builder.addProcedureRegisters(convertRegisterValueReuse(rv, registerBuilder, vectorBuilder));
        }
        for (Object rv : o.getFprs()) {
            builder.addFormalParamRegisters(convertRegisterValueReuse(rv, registerBuilder, vectorBuilder));
        }
        for (Object loc : o.getLrs()) {
            builder.addLocationRegisters(convertVectorReuse((int[]) loc, vectorBuilder));
        }
        for (Object rv : o.getDataStack()) {
            builder.addDataStack(convertRegisterValueReuse(rv, registerBuilder, vectorBuilder));
        }
        for (int[] loc : o.getLocationStack()) {
            builder.addLocationStack(convertVectorReuse(loc, vectorBuilder));
        }
        for (ProcFrame frame : o.getCallStack()) {
            builder.addCallStack(convertProcFrameReuse(frame, vectorBuilder, registerBuilder));
        }

        builder.setIsDead(o.isDead());
        builder.setInstructionFailed(o.isInstructionFailed());
        if (o.getFailureReason() != null) builder.setFailureReason(o.getFailureReason());
        if (o.getFailureCallStack() != null) {
            for (ProcFrame frame : o.getFailureCallStack()) {
                builder.addFailureCallStack(convertProcFrameReuse(frame, vectorBuilder, registerBuilder));
            }
        }

        // Instruction execution data
        Organism.InstructionExecutionData executionData = o.getLastInstructionExecution();
        if (executionData != null) {
            builder.setInstructionOpcodeId(executionData.opcodeId());
            for (Integer arg : executionData.rawArguments()) {
                builder.addInstructionRawArguments(arg);
            }
            builder.setInstructionEnergyCost(executionData.energyCost());
            builder.setInstructionEntropyDelta(executionData.entropyDelta());
            
            // Register values before execution (for annotation display)
            if (executionData.registerValuesBefore() != null && !executionData.registerValuesBefore().isEmpty()) {
                for (java.util.Map.Entry<Integer, Object> entry : executionData.registerValuesBefore().entrySet()) {
                    int registerId = entry.getKey();
                    Object registerValue = entry.getValue();
                    org.evochora.datapipeline.api.contracts.RegisterValue protoValue = 
                        convertRegisterValueReuse(registerValue, registerBuilder, vectorBuilder);
                    builder.putInstructionRegisterValuesBefore(registerId, protoValue);
                }
            }
        }

        // IP and DV before fetch
        builder.setIpBeforeFetch(convertVectorReuse(o.getIpBeforeFetch(), vectorBuilder));
        builder.setDvBeforeFetch(convertVectorReuse(o.getDvBeforeFetch(), vectorBuilder));

        // Special registers
        builder.setEntropyRegister(o.getSr());
        builder.setMoleculeMarkerRegister(o.getMr());

        return builder.build();
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
     * Extracts plugin states for all tick plugins.
     * Used by DeltaCodec.Encoder for delta compression.
     */
    private List<PluginState> extractPluginStates() {
        return tickPlugins.stream()
                .map(p -> PluginState.newBuilder()
                        .setPluginClass(p.plugin().getClass().getName())
                        .setStateBlob(ByteString.copyFrom(p.plugin().saveState()))
                        .build())
                .toList();
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

    private static org.evochora.datapipeline.api.contracts.ProcFrame convertProcFrameReuse(
            ProcFrame frame, Vector.Builder vectorBuilder, org.evochora.datapipeline.api.contracts.RegisterValue.Builder registerBuilder) {
        org.evochora.datapipeline.api.contracts.ProcFrame.Builder builder =
                org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder()
                        .setProcName(frame.procName)
                        .setAbsoluteReturnIp(convertVectorReuse(frame.absoluteReturnIp, vectorBuilder))
                        .setAbsoluteCallIp(convertVectorReuse(frame.absoluteCallIp, vectorBuilder))
                        .putAllFprBindings(frame.fprBindings);

        if (frame.savedPrs != null) {
            for (Object rv : frame.savedPrs) {
                builder.addSavedPrs(convertRegisterValueReuse(rv, registerBuilder, vectorBuilder));
            }
        }

        if (frame.savedFprs != null) {
            for (Object rv : frame.savedFprs) {
                builder.addSavedFprs(convertRegisterValueReuse(rv, registerBuilder, vectorBuilder));
            }
        }

        return builder.build();
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