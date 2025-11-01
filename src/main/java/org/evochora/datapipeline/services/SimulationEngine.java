package org.evochora.datapipeline.services;

import com.google.protobuf.ByteString;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigRenderOptions;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.datapipeline.api.contracts.*;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.IEnergyDistributionCreator;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Organism.ProcFrame;
import org.evochora.runtime.spi.IRandomProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

public class SimulationEngine extends AbstractService implements IMonitorable {
    // Pre-built empty message to isolate queue overhead from builder overhead
    private static final TickData EMPTY_TICK_DATA = TickData.newBuilder().build();

    private final IOutputQueueResource<TickData> tickDataOutput;
    private final IOutputQueueResource<SimulationMetadata> metadataOutput;
    private final int samplingInterval;
    private final int metricsWindowSeconds;
    private final List<Long> pauseTicks;
    private final String runId;
    private final Simulation simulation;
    private final IRandomProvider randomProvider;
    private final List<StrategyWithConfig> energyStrategies;
    private final long seed;
    private final long startTimeMs;
    private final AtomicLong currentTick = new AtomicLong(-1);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private long lastMetricTime = System.currentTimeMillis();
    private long lastTickCount = 0;
    private double ticksPerSecond = 0.0;

    private record StrategyWithConfig(IEnergyDistributionCreator strategy, Config config) {}

    public SimulationEngine(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.startTimeMs = System.currentTimeMillis();

        this.tickDataOutput = getRequiredResource("tickData", IOutputQueueResource.class);
        this.metadataOutput = getRequiredResource("metadataOutput", IOutputQueueResource.class);

        this.samplingInterval = options.hasPath("samplingInterval") ? options.getInt("samplingInterval") : 1;
        if (this.samplingInterval < 1) throw new IllegalArgumentException("samplingInterval must be >= 1");

        this.metricsWindowSeconds = options.hasPath("metricsWindowSeconds") ? options.getInt("metricsWindowSeconds") : 1;
        this.pauseTicks = options.hasPath("pauseTicks") ? options.getLongList("pauseTicks") : Collections.emptyList();
        this.seed = options.hasPath("seed") ? options.getLong("seed") : System.currentTimeMillis();

        List<? extends Config> organismConfigs = options.getConfigList("organisms");
        if (organismConfigs.isEmpty()) throw new IllegalArgumentException("At least one organism must be configured.");

        // Initialize instruction set before compiling programs
        org.evochora.runtime.isa.Instruction.init();

        Map<String, ProgramArtifact> compiledPrograms = new HashMap<>();
        Compiler compiler = new Compiler();

        boolean isToroidal = "TORUS".equalsIgnoreCase(options.getString("environment.topology"));
        EnvironmentProperties envProps = new EnvironmentProperties(options.getIntList("environment.shape").stream().mapToInt(i -> i).toArray(), isToroidal);

        for (Config orgConfig : organismConfigs) {
            String programPath = orgConfig.getString("program");
            if (!compiledPrograms.containsKey(programPath)) {
                try {
                    String source = Files.readString(Paths.get(programPath));
                    compiledPrograms.put(programPath, compiler.compile(List.of(source.split("\n")), programPath, envProps));
                } catch (IOException | CompilationException e) {
                    throw new IllegalArgumentException("Failed to read or compile program file: " + programPath, e);
                }
            }
        }

        this.randomProvider = new SeededRandomProvider(seed);
        this.energyStrategies = initializeEnergyStrategies(options.getConfigList("energyStrategies"), this.randomProvider, envProps);

        Environment environment = new Environment(envProps);
        this.simulation = new Simulation(environment);
        this.simulation.setRandomProvider(this.randomProvider);
        this.simulation.setProgramArtifacts(compiledPrograms);

        // Validate organism placement coordinates match world dimensions
        int worldDimensions = envProps.getWorldShape().length;
        for (Config orgConfig : organismConfigs) {
            List<Integer> positions = orgConfig.getIntList("placement.positions");
            if (positions.size() != worldDimensions) {
                String worldShape = Arrays.toString(envProps.getWorldShape());
                throw new IllegalArgumentException(
                    "Organism placement coordinate mismatch: World has " + worldDimensions +
                    " dimensions " + worldShape + " but organism placement has " + positions.size() +
                    " coordinates " + positions + ". Update organism placement to match world dimensions."
                );
            }
        }

        for (Config orgConfig : organismConfigs) {
            ProgramArtifact artifact = compiledPrograms.get(orgConfig.getString("program"));
            int[] startPosition = orgConfig.getIntList("placement.positions").stream().mapToInt(i -> i).toArray();
            
            Organism organism = Organism.create(simulation, startPosition, orgConfig.getInt("initialEnergy"), log);
            organism.setProgramId(artifact.programId());
            this.simulation.addOrganism(organism);
            
            // Place code and initial world objects in environment
            placeOrganismCodeAndObjects(organism, artifact, startPosition);
        }
        // Generate run ID with timestamp prefix: YYYYMMDD-HHiissmm-UUID
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSS");
        String timestamp = now.format(formatter);
        this.runId = timestamp + "-" + UUID.randomUUID().toString();
    }

    @Override
    protected void logStarted() {
        EnvironmentProperties envProps = simulation.getEnvironment().getProperties();
        String worldDims = String.join("×", Arrays.stream(envProps.getWorldShape()).mapToObj(String::valueOf).toArray(String[]::new));
        String topology = envProps.isToroidal() ? "TORUS" : "BOUNDED";
        String strategyNames = energyStrategies.stream()
                .map(s -> s.strategy().getClass().getSimpleName())
                .collect(java.util.stream.Collectors.joining(", "));

        log.info("SimulationEngine started: world=[{}, {}], organisms={}, energyStrategies={} ({}), seed={}, samplingInterval={}, runId={}",
                worldDims, topology, simulation.getOrganisms().size(), energyStrategies.size(), strategyNames, seed, samplingInterval, runId);
    }

    @Override
    protected void run() throws InterruptedException {
        try {
            metadataOutput.put(buildMetadataMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while sending initial metadata during shutdown");
            throw e; // Let AbstractService handle it as normal shutdown
        }

        while ((getCurrentState() == State.RUNNING || getCurrentState() == State.PAUSED)
                && !Thread.currentThread().isInterrupted()) {
            checkPause();

            simulation.tick();
            long tick = currentTick.incrementAndGet();

            // Apply energy distribution strategies after the tick
            if (!energyStrategies.isEmpty()) {
                for (StrategyWithConfig strategyWithConfig : energyStrategies) {
                    try {
                        strategyWithConfig.strategy().distributeEnergy(simulation.getEnvironment(), tick);
                    } catch (Exception ex) {
                        log.warn("Energy strategy '{}' failed at tick {}", 
                                strategyWithConfig.strategy().getClass().getSimpleName(), tick);
                        recordError(
                            "ENERGY_STRATEGY_FAILED",
                            "Energy distribution strategy failed",
                            String.format("Strategy: %s, Tick: %d", 
                                strategyWithConfig.strategy().getClass().getSimpleName(), tick)
                        );
                    }
                }
            }

            if (tick % samplingInterval == 0) {
                try {
                    tickDataOutput.put(captureTickData(tick));
                    messagesSent.incrementAndGet();
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

    private List<StrategyWithConfig> initializeEnergyStrategies(List<? extends Config> configs, IRandomProvider random, EnvironmentProperties envProps) {
        return configs.stream().map(config -> {
            try {
                IEnergyDistributionCreator strategy = (IEnergyDistributionCreator) Class.forName(config.getString("className"))
                        .getConstructor(IRandomProvider.class, com.typesafe.config.Config.class)
                        .newInstance(random, config.getConfig("options"));
                return new StrategyWithConfig(strategy, config.getConfig("options"));
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Failed to instantiate energy strategy: " + config.getString("className"), e);
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

        energyStrategies.forEach(strategyWithConfig -> {
            EnergyStrategyConfig.Builder strategyBuilder = EnergyStrategyConfig.newBuilder();
            strategyBuilder.setStrategyType(strategyWithConfig.strategy().getClass().getName());
            strategyBuilder.setConfigJson(strategyWithConfig.config().root().render(ConfigRenderOptions.concise()));
            builder.addEnergyStrategies(strategyBuilder.build());
        });

        simulation.getProgramArtifacts().values().forEach(artifact -> builder.addPrograms(convertProgramArtifact(artifact)));

        options.getConfigList("organisms").forEach(orgConfig -> {
            InitialOrganismSetup.Builder orgSetupBuilder = InitialOrganismSetup.newBuilder();
            ProgramArtifact artifact = simulation.getProgramArtifacts().get(orgConfig.getString("program"));
            if (artifact != null) {
                orgSetupBuilder.setProgramId(artifact.programId());
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

        return builder.build();
    }

    private TickData captureTickData(long tick) {
        TickData.Builder builder = TickData.newBuilder();
        builder.setSimulationRunId(runId);
        builder.setTickNumber(tick);
        builder.setCaptureTimeMs(System.currentTimeMillis());
        simulation.getOrganisms().stream().filter(o -> !o.isDead()).forEach(o -> builder.addOrganisms(extractOrganismState(o)));
        extractCellStates(simulation.getEnvironment(), builder);
        builder.setRngState(ByteString.copyFrom(randomProvider.saveState()));
        energyStrategies.forEach(s -> builder.addStrategyStates(StrategyState.newBuilder()
                .setStrategyType(s.strategy().getClass().getName())
                .setStateBlob(ByteString.copyFrom(s.strategy().saveState()))
                .build()));
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
        return builder.build();
    }

    private void extractCellStates(Environment env, TickData.Builder tickBuilder) {
        CellState.Builder cellBuilder = CellState.newBuilder();

        // FLAT_INDEX OPTIMIZATION: Use flat_index directly in protobuf
        // - Data size reduction: 80% (20 bytes → 4 bytes per cell coordinate)
        // - CPU performance gain: 16% (eliminates getCoordinateFromIndex + Vector building)
        // - Trade-off: Consumers must convert flat_index to coordinates using Environment.shape
        //
        // Required Environment methods for this approach:
        // - forEachOccupiedIndex(IntConsumer) - provides flat_index iteration
        // - getMoleculeInt(int flatIndex) - direct access by flat_index
        // - getOwnerIdByIndex(int flatIndex) - direct access by flat_index
        //
        // If reverting to coordinate version, these methods can be removed from Environment
        env.forEachOccupiedIndex(flatIndex -> {
            // Get molecule and owner directly using flat index
            int moleculeInt = env.getMoleculeInt(flatIndex);
            int ownerId = env.getOwnerIdByIndex(flatIndex);

            // Reuse cell builder with flat_index (no coordinate conversion needed!)
            cellBuilder.clear();
            cellBuilder.setFlatIndex(flatIndex)
                    .setMoleculeType(moleculeInt & org.evochora.runtime.Config.TYPE_MASK)
                    .setMoleculeValue(extractSignedValue(moleculeInt))
                    .setOwnerId(ownerId);

            tickBuilder.addCells(cellBuilder.build());
        });
    }

    // COORDINATE VERSION (COMMENTED OUT): Original approach without flat_index exposure
    // Use this version if flat_index trade-off becomes unfavorable
    // Also uncomment Vector coordinate field in CellState protobuf message
    //
    // This version does NOT require flat_index exposure from Environment.
    // The following methods can be removed from Environment if reverting:
    // - forEachOccupiedIndex(IntConsumer)
    // - getMoleculeInt(int flatIndex)
    // - getOwnerIdByIndex(int flatIndex)
    // - getCoordinateFromIndex(int flatIndex)
    //
    // private void extractCellStates(Environment env, TickData.Builder tickBuilder) {
    //     Vector.Builder vectorBuilder = Vector.newBuilder();
    //     CellState.Builder cellBuilder = CellState.newBuilder();
    //
    //     // Original approach: Environment provides coordinates directly
    //     env.forEachOccupiedCell((coord, moleculeInt, ownerId) -> {
    //         // Build coordinate vector for protobuf
    //         vectorBuilder.clear();
    //         for (int c : coord) {
    //             vectorBuilder.addComponents(c);
    //         }
    //
    //         // Reuse cell builder with coordinate
    //         cellBuilder.clear();
    //         cellBuilder.setCoordinate(vectorBuilder.build())
    //                 .setMoleculeType(moleculeInt & org.evochora.runtime.Config.TYPE_MASK)
    //                 .setMoleculeValue(extractSignedValue(moleculeInt))
    //                 .setOwnerId(ownerId);
    //
    //         tickBuilder.addCells(cellBuilder.build());
    //     });
    // }

    private static int extractSignedValue(int moleculeInt) {
        int rawValue = moleculeInt & org.evochora.runtime.Config.VALUE_MASK;
        if ((rawValue & (1 << (org.evochora.runtime.Config.VALUE_BITS - 1))) != 0) {
            rawValue |= ~((1 << org.evochora.runtime.Config.VALUE_BITS) - 1);
        }
        return rawValue;
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

        artifact.callSiteBindings().forEach((address, target) ->
                builder.addCallSiteBindings(CallSiteBinding.newBuilder()
                        .setLinearAddress(address)
                        .setTargetCoord(convertVector(target))));

        builder.putAllRelativeCoordToLinearAddress(artifact.relativeCoordToLinearAddress());

        artifact.linearAddressToCoord().forEach((address, coord) ->
                builder.addLinearAddressToCoord(LinearAddressToCoord.newBuilder()
                        .setLinearAddress(address)
                        .setCoord(convertVector(coord))));

        artifact.labelAddressToName().forEach((address, name) ->
                builder.addLabelAddressToName(LabelMapping.newBuilder()
                        .setLinearAddress(address)
                        .setLabelName(name)));

        builder.putAllRegisterAliasMap(artifact.registerAliasMap());

        artifact.procNameToParamNames().forEach((procName, params) ->
                builder.putProcNameToParamNames(procName, ParameterNames.newBuilder().addAllNames(params).build()));

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

    private static org.evochora.datapipeline.api.contracts.RegisterValue convertRegisterValue(Object rv) {
        org.evochora.datapipeline.api.contracts.RegisterValue.Builder builder = org.evochora.datapipeline.api.contracts.RegisterValue.newBuilder();
        if (rv instanceof Integer) builder.setScalar((Integer) rv);
        else if (rv instanceof int[]) builder.setVector(convertVector((int[]) rv));
        return builder.build();
    }

    private static org.evochora.datapipeline.api.contracts.ProcFrame convertProcFrame(ProcFrame frame) {
        org.evochora.datapipeline.api.contracts.ProcFrame.Builder builder =
                org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder()
                        .setProcName(frame.procName)
                        .setAbsoluteReturnIp(convertVector(frame.absoluteReturnIp))
                        .putAllFprBindings(frame.fprBindings);

        if (frame.savedPrs != null) {
            for (Object rv : frame.savedPrs) {
                builder.addSavedPrs(convertRegisterValue(rv));
            }
        }

        if (frame.savedFprs != null) {
            for (Object rv : frame.savedFprs) {
                builder.addSavedFprs(convertRegisterValue(rv));
            }
        }

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
        }
        return registerBuilder.build();
    }

    private static org.evochora.datapipeline.api.contracts.ProcFrame convertProcFrameReuse(
            ProcFrame frame, Vector.Builder vectorBuilder, org.evochora.datapipeline.api.contracts.RegisterValue.Builder registerBuilder) {
        org.evochora.datapipeline.api.contracts.ProcFrame.Builder builder =
                org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder()
                        .setProcName(frame.procName)
                        .setAbsoluteReturnIp(convertVectorReuse(frame.absoluteReturnIp, vectorBuilder))
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

    private static Iterable<int[]> iterateCoordinates(final int[] shape) {
        return () -> new Iterator<>() {
            private final int[] current = new int[shape.length];
            private boolean first = true;
            @Override
            public boolean hasNext() {
                if (first) return true;
                for (int i = shape.length - 1; i >= 0; i--) if (current[i] < shape[i] - 1) return true;
                return false;
            }
            @Override
            public int[] next() {
                if (first) {
                    first = false;
                    Arrays.fill(current, 0);
                    return Arrays.copyOf(current, current.length);
                }
                for (int i = shape.length - 1; i >= 0; i--) {
                    if (current[i] < shape[i] - 1) {
                        current[i]++;
                        return Arrays.copyOf(current, current.length);
                    }
                    current[i] = 0;
                }
                throw new NoSuchElementException();
            }
        };
    }
    
    private void placeOrganismCodeAndObjects(Organism organism, ProgramArtifact artifact, int[] startPosition) {
        // Place code in environment
        // ProgramArtifact guarantees deterministic iteration order (sorted by coordinate in Emitter)
        for (Map.Entry<int[], Integer> entry : artifact.machineCodeLayout().entrySet()) {
            int[] relativePos = entry.getKey();
            int[] absolutePos = new int[startPosition.length];
            for (int i = 0; i < startPosition.length; i++) {
                absolutePos[i] = startPosition[i] + relativePos[i];
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
            int[] absolutePos = new int[startPosition.length];
            for (int i = 0; i < startPosition.length; i++) {
                absolutePos[i] = startPosition[i] + relativePos[i];
            }

            org.evochora.compiler.api.PlacedMolecule pm = entry.getValue();
            simulation.getEnvironment().setMolecule(
                new org.evochora.runtime.model.Molecule(pm.type(), pm.value()),
                organism.getId(),
                absolutePos
            );
        }
    }
}