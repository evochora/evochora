package org.evochora.datapipeline.services;

import com.google.protobuf.ByteString;
import com.typesafe.config.Config;
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
    private final List<IEnergyDistributionCreator> energyStrategies;
    private final AtomicLong currentTick = new AtomicLong(0);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final ConcurrentLinkedDeque<OperationalError> errors = new ConcurrentLinkedDeque<>();
    private long lastMetricTime = System.currentTimeMillis();
    private long lastTickCount = 0;
    private double ticksPerSecond = 0.0;

    public SimulationEngine(String name, Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);

        this.tickDataOutput = getRequiredResource("tickData", IOutputQueueResource.class);
        this.metadataOutput = getRequiredResource("metadataOutput", IOutputQueueResource.class);

        this.samplingInterval = options.hasPath("samplingInterval") ? options.getInt("samplingInterval") : 1;
        if (this.samplingInterval < 1) throw new IllegalArgumentException("samplingInterval must be >= 1");

        this.pauseTicks = options.hasPath("pauseTicks") ? options.getLongList("pauseTicks") : Collections.emptyList();
        long seed = options.hasPath("seed") ? options.getLong("seed") : System.currentTimeMillis();

        List<? extends Config> organismConfigs = options.getConfigList("organisms");
        if (organismConfigs.isEmpty()) throw new IllegalArgumentException("At least one organism must be configured.");

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
        log.info("Simulation loop started. Capturing data every {} ticks.", samplingInterval);
        metadataOutput.put(buildMetadataMessage());

        while (getCurrentState() == State.RUNNING || getCurrentState() == State.PAUSED) {
            checkPause();
            if (shouldAutoPause(currentTick.get())) {
                log.info("{} auto-paused at tick {} due to pauseTicks configuration", getClass().getSimpleName(), currentTick.get());
                pause();
                continue;
            }

            simulation.tick();
            long tick = currentTick.incrementAndGet();

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

    private List<IEnergyDistributionCreator> initializeEnergyStrategies(List<? extends Config> configs, IRandomProvider random, EnvironmentProperties envProps) {
        return configs.stream().map(config -> {
            try {
                return (IEnergyDistributionCreator) Class.forName(config.getString("className"))
                        .getConstructor(Config.class, IRandomProvider.class, EnvironmentProperties.class)
                        .newInstance(config.getConfig("options"), random, envProps);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Failed to instantiate energy strategy", e);
            }
        }).toList();
    }

    private SimulationMetadata buildMetadataMessage() {
        SimulationMetadata.Builder builder = SimulationMetadata.newBuilder();
        builder.setSimulationRunId(this.runId);
        simulation.getProgramArtifacts().values().forEach(artifact -> builder.addPrograms(convertProgramArtifact(artifact)));
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
                .setStrategyType(s.getClass().getName())
                .setStateBlob(ByteString.copyFrom(s.saveState()))
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
        for (int[] coord : iterateCoordinates(env.getProperties().getWorldShape())) {
            Molecule molecule = env.getMolecule(coord);
            if (!molecule.isEmpty()) {
                cells.add(CellState.newBuilder()
                        .setCoordinate(convertVector(coord))
                        .setMoleculeType(molecule.type())
                        .setMoleculeValue(molecule.value())
                        .setOwnerId(env.getOwnerId(coord))
                        .build());
            }
        }
        return cells;
    }

    private static org.evochora.datapipeline.api.contracts.ProgramArtifact convertProgramArtifact(ProgramArtifact artifact) {
        org.evochora.datapipeline.api.contracts.ProgramArtifact.Builder builder = org.evochora.datapipeline.api.contracts.ProgramArtifact.newBuilder();
        builder.setProgramId(artifact.programId());
        artifact.sources().forEach((fileName, lines) -> builder.putSources(fileName, SourceLines.newBuilder().addAllLines(lines).build()));
        artifact.procNameToParamNames().forEach((procName, params) -> builder.putProcNameToParamNames(procName, ParameterNames.newBuilder().addAllNames(params).build()));
        // Other conversions from ProgramArtifact to protobuf would go here, if they existed in the protobuf definition.
        return builder.build();
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
        return org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder()
                .setProcName(frame.procName)
                .setAbsoluteReturnIp(convertVector(frame.absoluteReturnIp))
                .putAllFprBindings(frame.fprBindings)
                .build();
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