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
import org.evochora.datapipeline.api.resources.wrappers.queues.IOutputQueueResource;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.IEnergyDistributionCreator;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Organism.ProcFrame;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.ISerializable;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
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
    private final IOutputQueueResource<TickData> tickDataOutput;
    private final IOutputQueueResource<SimulationMetadata> metadataOutput;
    private final int samplingInterval;
    private final List<Long> pauseTicks;
    private final String runId;
    private final Simulation simulation;
    private final IRandomProvider randomProvider;
    private final List<StrategyWithConfig> energyStrategies;
    private final long seed;
    private final long startTimeMs;
    private final AtomicLong currentTick = new AtomicLong(0);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
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

        for (Config orgConfig : organismConfigs) {
            ProgramArtifact artifact = compiledPrograms.get(orgConfig.getString("program"));
            Organism org = Organism.create(simulation,
                    orgConfig.getIntList("placement.positions").stream().mapToInt(i -> i).toArray(),
                    orgConfig.getInt("initialEnergy"), log);
            org.setProgramId(artifact.programId());
            this.simulation.addOrganism(org);
        }
        this.runId = UUID.randomUUID().toString();
    }

    @Override
    protected void run() throws InterruptedException {
        try {
            metadataOutput.put(buildMetadataMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while sending metadata, service will not start.", e);
            // We were interrupted before the loop even started, so we exit.
            // The service state will be handled by the AbstractService's runService method.
            return;
        }

        // Build informative startup log message
        EnvironmentProperties envProps = simulation.getEnvironment().getProperties();
        String worldDims = String.join("×", Arrays.stream(envProps.getWorldShape()).mapToObj(String::valueOf).toArray(String[]::new));
        String topology = envProps.isToroidal() ? "TORUS" : "BOUNDED";
        String strategyNames = energyStrategies.stream()
                .map(s -> s.strategy().getClass().getSimpleName())
                .collect(java.util.stream.Collectors.joining(", "));

        log.info("SimulationEngine started: world=[{}, {}], organisms={}, energyStrategies={} ({}), seed={}, samplingInterval={}",
                worldDims, topology, simulation.getOrganisms().size(), energyStrategies.size(), strategyNames, seed, samplingInterval);
        while (getCurrentState() == State.RUNNING || getCurrentState() == State.PAUSED) {
            checkPause();
            if (shouldAutoPause(currentTick.get())) {
                log.info("{} auto-paused at tick {} due to pauseTicks configuration", getClass().getSimpleName(), currentTick.get());
                pause();
                continue;
            }

            simulation.tick();
            long tick = currentTick.incrementAndGet();

            // Apply energy distribution strategies after the tick
            if (!energyStrategies.isEmpty()) {
                for (StrategyWithConfig strategyWithConfig : energyStrategies) {
                    try {
                        strategyWithConfig.strategy().distributeEnergy(simulation.getEnvironment(), tick);
                    } catch (Exception ex) {
                        log.warn("Energy strategy '{}' execution failed: {}",
                                strategyWithConfig.strategy().getClass().getSimpleName(), ex.getMessage());
                    }
                }
            }

            if (tick % samplingInterval == 0) {
                try {
                    tickDataOutput.put(captureTickData(tick));
                    messagesSent.incrementAndGet();
                } catch (Exception e) {
                    log.error("Failed to capture or send tick data for tick {}", tick, e);
                    errors.add(new OperationalError(Instant.now(), "SEND_ERROR", "Failed to send tick data", e.getMessage()));
                }
            }
        }
        log.info("Simulation loop finished.");
    }

    @Override
    public Map<String, Number> getMetrics() {
        Map<String, Number> metrics = new HashMap<>();
        long now = System.currentTimeMillis();
        if (now - lastMetricTime > 1000) {
            ticksPerSecond = (double) (currentTick.get() - lastTickCount) * 1000.0 / (now - lastMetricTime);
            lastMetricTime = now;
            lastTickCount = currentTick.get();
        }
        metrics.put("current_tick", currentTick.get());
        metrics.put("organisms_alive", simulation.getOrganisms().stream().filter(o -> !o.isDead()).count());
        metrics.put("organisms_total", (long) simulation.getOrganisms().size());
        metrics.put("messages_sent", messagesSent.get());
        metrics.put("sampling_interval", samplingInterval);
        metrics.put("ticks_per_second", ticksPerSecond);
        metrics.put("error_count", errors.size());
        return metrics;
    }

    @Override
    public List<OperationalError> getErrors() { return new ArrayList<>(errors); }
    @Override
    public void clearErrors() { errors.clear(); }
    @Override
    public boolean isHealthy() { return getCurrentState() != State.ERROR; }

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
        builder.addAllCells(extractCellStates(simulation.getEnvironment()));
        builder.setRngState(ByteString.copyFrom(randomProvider.saveState()));
        energyStrategies.forEach(s -> builder.addStrategyStates(StrategyState.newBuilder()
                .setStrategyType(s.strategy().getClass().getName())
                .setStateBlob(ByteString.copyFrom(s.strategy().saveState()))
                .build()));
        return builder.build();
    }

    private OrganismState extractOrganismState(Organism o) {
        OrganismState.Builder builder = OrganismState.newBuilder();
        builder.setOrganismId(o.getId());
        if (o.getParentId() != null) builder.setParentId(o.getParentId());
        builder.setBirthTick(o.getBirthTick());
        builder.setProgramId(o.getProgramId());
        builder.setEnergy(o.getEr());
        builder.setIp(convertVector(o.getIp()));
        builder.setInitialPosition(convertVector(o.getInitialPosition()));
        builder.setDv(convertVector(o.getDv()));
        o.getDps().forEach(dp -> builder.addDataPointers(convertVector(dp)));
        builder.setActiveDpIndex(o.getActiveDpIndex());
        o.getDrs().forEach(rv -> builder.addDataRegisters(convertRegisterValue(rv)));
        o.getPrs().forEach(rv -> builder.addProcedureRegisters(convertRegisterValue(rv)));
        o.getFprs().forEach(rv -> builder.addFormalParamRegisters(convertRegisterValue(rv)));
        o.getLrs().forEach(loc -> builder.addLocationRegisters(convertVector((int[]) loc)));
        o.getDataStack().forEach(rv -> builder.addDataStack(convertRegisterValue(rv)));
        o.getLocationStack().forEach(loc -> builder.addLocationStack(convertVector(loc)));
        o.getCallStack().forEach(frame -> builder.addCallStack(convertProcFrame(frame)));
        builder.setIsDead(o.isDead());
        builder.setInstructionFailed(o.isInstructionFailed());
        if (o.getFailureReason() != null) builder.setFailureReason(o.getFailureReason());
        if (o.getFailureCallStack() != null) o.getFailureCallStack().forEach(frame -> builder.addFailureCallStack(convertProcFrame(frame)));
        return builder.build();
    }

    private List<CellState> extractCellStates(Environment env) {
        List<CellState> cells = new ArrayList<>();
        Vector.Builder vectorBuilder = Vector.newBuilder();

        env.forEachOccupiedCell((coord, moleculeInt, ownerId) -> {
            vectorBuilder.clear();
            for (int c : coord) {
                vectorBuilder.addComponents(c);
            }

            cells.add(CellState.newBuilder()
                    .setCoordinate(vectorBuilder.build())
                    .setMoleculeType(moleculeInt & org.evochora.runtime.Config.TYPE_MASK)
                    .setMoleculeValue(extractSignedValue(moleculeInt))
                    .setOwnerId(ownerId)
                    .build());
        });

        return cells;
    }

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
}