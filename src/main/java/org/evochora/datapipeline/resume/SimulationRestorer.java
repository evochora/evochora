package org.evochora.datapipeline.resume;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.evochora.compiler.api.MachineInstructionInfo;
import org.evochora.compiler.api.ParamInfo;
import org.evochora.compiler.api.ParamType;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.api.TokenKind;
import org.evochora.datapipeline.api.contracts.CallSiteBinding;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.ColumnTokenLookup;
import org.evochora.datapipeline.api.contracts.FileTokenLookup;
import org.evochora.datapipeline.api.contracts.InstructionMapping;
import org.evochora.datapipeline.api.contracts.LineTokenLookup;
import org.evochora.datapipeline.api.contracts.LinearAddressToCoord;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.PlacedMoleculeMapping;
import org.evochora.datapipeline.api.contracts.PluginState;
import org.evochora.datapipeline.api.contracts.ProcFrame;
import org.evochora.datapipeline.api.contracts.ProcedureRegisterSnapshot;
import org.evochora.datapipeline.api.contracts.RegisterValue;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.SourceMapEntry;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TokenMapEntry;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.label.ILabelMatchingStrategy;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IDeathHandler;
import org.evochora.runtime.spi.IInstructionInterceptor;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.ISimulationPlugin;
import org.evochora.runtime.spi.ITickPlugin;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Converts a ResumeCheckpoint into a running Simulation.
 * <p>
 * This class handles the conversion from Protobuf data structures to runtime objects:
 * <ul>
 *   <li>Creates Environment and populates cells from snapshot</li>
 *   <li>Creates Simulation using forResume() factory</li>
 *   <li>Restores RNG state for deterministic continuation</li>
 *   <li>Reconstructs ProgramArtifacts from metadata</li>
 *   <li>Rebuilds Organisms using RestoreBuilder</li>
 *   <li>Reinstantiates TickPlugins with saved state</li>
 * </ul>
 * <p>
 * The restored simulation is ready to run from the next tick after the checkpoint.
 * Since resume is always from a snapshot (chunk start), this ensures:
 * <ul>
 *   <li>Complete state including RNG is available</li>
 *   <li>No partial chunk handling needed</li>
 *   <li>Deterministic continuation guaranteed</li>
 * </ul>
 */
public class SimulationRestorer {

    private static final Logger log = LoggerFactory.getLogger(SimulationRestorer.class);

    /**
     * Bundles a tick plugin with its configuration.
     * Used for extracting plugin state during simulation.
     * <p>
     * A plugin class that implements several of the plugin interfaces is instantiated once and
     * bundled once per interface it implements, so the same instance appears in several of these
     * bundles, each time with the same configuration.
     *
     * @param plugin the plugin instance created from the checkpoint's configuration, with the
     *               state stored for its class already loaded into it
     * @param config the {@code options} block the instance was constructed with, taken from the
     *               plugin's entry in the configuration the checkpoint carries; empty if that entry
     *               declares no options
     */
    public record PluginWithConfig(ITickPlugin plugin, Config config) {}

    /**
     * Bundles an instruction interceptor with its configuration.
     * Used for extracting plugin state during simulation.
     * <p>
     * A plugin class that implements several of the plugin interfaces is instantiated once and
     * bundled once per interface it implements, so the same instance appears in several of these
     * bundles, each time with the same configuration.
     *
     * @param interceptor the plugin instance created from the checkpoint's configuration, with
     *                    the state stored for its class already loaded into it
     * @param config      the {@code options} block the instance was constructed with, taken from
     *                    the plugin's entry in the configuration the checkpoint carries; empty if
     *                    that entry declares no options
     */
    public record InterceptorWithConfig(IInstructionInterceptor interceptor, Config config) {}

    /**
     * Bundles a death handler with its configuration.
     * Used for extracting plugin state during simulation.
     * <p>
     * A plugin class that implements several of the plugin interfaces is instantiated once and
     * bundled once per interface it implements, so the same instance appears in several of these
     * bundles, each time with the same configuration.
     *
     * @param handler the plugin instance created from the checkpoint's configuration, with the
     *                state stored for its class already loaded into it
     * @param config  the {@code options} block the instance was constructed with, taken from the
     *                plugin's entry in the configuration the checkpoint carries; empty if that
     *                entry declares no options
     */
    public record DeathHandlerWithConfig(IDeathHandler handler, Config config) {}

    /**
     * Bundles a birth handler with its configuration.
     * Used for extracting plugin state during simulation.
     * <p>
     * A plugin class that implements several of the plugin interfaces is instantiated once and
     * bundled once per interface it implements, so the same instance appears in several of these
     * bundles, each time with the same configuration.
     *
     * @param handler the plugin instance created from the checkpoint's configuration, with the
     *                state stored for its class already loaded into it
     * @param config  the {@code options} block the instance was constructed with, taken from the
     *                plugin's entry in the configuration the checkpoint carries; empty if that
     *                entry declares no options
     */
    public record BirthHandlerWithConfig(IBirthHandler handler, Config config) {}

    /**
     * Contains all state needed to resume a simulation in SimulationEngine.
     * <p>
     * This record is produced by {@link #restore} and consumed by SimulationEngine.
     *
     * @param simulation The restored simulation ready to run
     * @param randomProvider The IRandomProvider with restored RNG state
     * @param tickPlugins The tick plugins with restored state and their configs
     * @param instructionInterceptors The instruction interceptors with restored state and their configs
     * @param deathHandlers The death handlers with restored state and their configs
     * @param birthHandlers The birth handlers with restored state and their configs
     * @param programArtifacts Map of programId to ProgramArtifact
     * @param runId The original simulation run ID
     * @param resumeFromTick The tick number to resume from (first tick to generate)
     * @param startTimeMs The original start time (from metadata)
     * @param seed The original seed (from metadata)
     */
    public record RestoredState(
        Simulation simulation,
        IRandomProvider randomProvider,
        List<PluginWithConfig> tickPlugins,
        List<InterceptorWithConfig> instructionInterceptors,
        List<DeathHandlerWithConfig> deathHandlers,
        List<BirthHandlerWithConfig> birthHandlers,
        Map<String, ProgramArtifact> programArtifacts,
        String runId,
        long resumeFromTick,
        long startTimeMs,
        long seed
    ) {}

    private SimulationRestorer() {
        // Static utility class
    }

    /**
     * Restores a complete simulation state from a checkpoint.
     * <p>
     * After calling this method, the returned state contains everything needed
     * to initialize SimulationEngine in resume mode:
     * <ul>
     *   <li>The restored Simulation with its Environment and Organisms</li>
     *   <li>The ProgramArtifact map, keyed by program ID</li>
     *   <li>The IRandomProvider with restored RNG state</li>
     *   <li>The instantiated TickPlugins with restored state</li>
     *   <li>The original runId and metadata for continuity</li>
     * </ul>
     *
     * @param checkpoint The loaded checkpoint data
     * @param randomProvider A fresh IRandomProvider instance (state will be loaded into it)
     * @param parallelism Thread parallelism for the Plan and Execute phases (deployment-specific, not from checkpoint).
     *                    0 = auto, 1 = sequential, N = explicit.
     * @return A RestoredState ready for SimulationEngine initialization
     * @throws ResumeException if restoration fails
     */
    public static RestoredState restore(ResumeCheckpoint checkpoint, IRandomProvider randomProvider, int parallelism) {
        SimulationMetadata metadata = checkpoint.metadata();
        TickData snapshot = checkpoint.snapshot();

        log.debug("Restoring simulation {} from tick {}",
            metadata.getSimulationRunId(), checkpoint.getCheckpointTick());

        // 1. Parse config from metadata - ALL simulation config comes from here
        Config resolvedConfig = ConfigFactory.parseString(metadata.getResolvedConfigJson());
        Config runtimeConfig = resolvedConfig.getConfig("runtime");
        Config organismConfig = runtimeConfig.getConfig("organism");
        Config thermoConfig = runtimeConfig.getConfig("thermodynamics");

        // 2. Create ThermodynamicPolicyManager
        ThermodynamicPolicyManager policyManager = new ThermodynamicPolicyManager(thermoConfig);

        // 3. Create label matching strategy from config (was previously using default!)
        ILabelMatchingStrategy labelMatchingStrategy = Environment.createLabelMatchingStrategy(
            runtimeConfig.hasPath("label-matching") ? runtimeConfig.getConfig("label-matching") : null);

        // 4. Create Environment from config
        int[] shape = resolvedConfig.getIntList("environment.shape").stream().mapToInt(i -> i).toArray();
        boolean toroidal = "TORUS".equalsIgnoreCase(resolvedConfig.getString("environment.topology"));
        EnvironmentProperties envProps = new EnvironmentProperties(shape, toroidal);
        Environment environment = new Environment(envProps, labelMatchingStrategy);

        // 5. Populate Environment cells from snapshot
        populateCells(environment, snapshot.getCellColumns());

        // What was laid down is the state as it was, not a change to it. Left marked as changed,
        // every restored cell would enter the first delta after the resume and, through the
        // accumulated deltas, every delta until the next snapshot - a far larger chunk carrying
        // the same state as an uninterrupted run.
        environment.resetChangeTracking();

        // 6. Extract state from snapshot (always complete since we resume from chunk start)
        // A snapshot labelled T holds the state after simulation tick T, at which point the
        // simulation's own counter already stands at T + 1; the restored simulation continues with
        // that tick. Every tick-derived quantity (organism randomness, plugin schedules, birth ticks)
        // depends on this counter, so an off-by-one here changes the run from the first tick on.
        long currentTick = checkpoint.getResumeFromTick();
        long totalOrganismsCreated = snapshot.getTotalOrganismsCreated();
        ByteString rngState = snapshot.getRngState();
        List<OrganismState> organismStates = snapshot.getOrganismsList();
        List<PluginState> pluginStates = snapshot.getPluginStatesList();

        // 7. Restore genome hash set from snapshot
        LongOpenHashSet allGenomesEverSeen = new LongOpenHashSet();
        for (long hash : snapshot.getAllGenomeHashesEverSeenList()) {
            allGenomesEverSeen.add(hash);
        }

        log.debug("Resume state: currentTick={}, totalOrganismsCreated={}, totalUniqueGenomes={}, organisms={}",
            currentTick, totalOrganismsCreated, allGenomesEverSeen.size(), organismStates.size());

        // 8. Create Simulation using forResume()
        Simulation simulation = Simulation.forResume(
            environment,
            currentTick,
            totalOrganismsCreated,
            allGenomesEverSeen,
            policyManager,
            organismConfig,
            parallelism
        );

        // 8. Restore RNG state
        if (rngState.isEmpty()) {
            throw new ResumeException("Checkpoint at tick " + checkpoint.getCheckpointTick()
                    + " carries no RNG state; the run cannot be continued deterministically");
        }
        try {
            randomProvider.loadState(rngState.toByteArray());
        } catch (RuntimeException e) {
            // A truncated or otherwise malformed state fails inside the provider, which knows nothing
            // about resuming; without this the failure would surface as a stack trace rather than as
            // the checkpoint problem it is.
            throw new ResumeException("Checkpoint at tick " + checkpoint.getCheckpointTick()
                    + " carries an unreadable RNG state", e);
        }
        log.debug("Loaded RNG state ({} bytes)", rngState.size());
        simulation.setRandomProvider(randomProvider);

        // 9. Restore ProgramArtifacts
        Map<String, ProgramArtifact> programs = restoreProgramArtifacts(metadata);
        log.debug("Restored {} program artifacts", programs.size());

        // 10. Restore Organisms (including dead organisms awaiting final serialization)
        int deadCount = 0;
        for (OrganismState state : organismStates) {
            Organism organism = restoreOrganism(state, simulation);
            simulation.addOrganism(organism);
            if (state.getIsDead()) {
                deadCount++;
            }
        }
        log.debug("Restored {} organisms ({} alive, {} dead awaiting serialization)",
                simulation.getOrganisms().size(),
                simulation.getOrganisms().size() - deadCount,
                deadCount);

        // 11. Restore plugins from config (with their configs for SimulationEngine)
        RestoredPlugins restoredPlugins = restorePlugins(
            resolvedConfig.getConfigList("plugins"),
            pluginStates,
            randomProvider
        );

        // Register tick plugins with simulation
        for (PluginWithConfig pwc : restoredPlugins.tickPlugins()) {
            simulation.addTickPlugin(pwc.plugin());
        }
        log.debug("Restored {} tick plugins", restoredPlugins.tickPlugins().size());

        // Register instruction interceptors with simulation
        for (InterceptorWithConfig iwc : restoredPlugins.interceptors()) {
            simulation.addInstructionInterceptor(iwc.interceptor());
        }
        log.debug("Restored {} instruction interceptors", restoredPlugins.interceptors().size());

        // Register death handlers with simulation
        for (DeathHandlerWithConfig dhc : restoredPlugins.deathHandlers()) {
            simulation.addDeathHandler(dhc.handler());
        }
        log.debug("Restored {} death handlers", restoredPlugins.deathHandlers().size());

        // Register birth handlers with simulation
        for (BirthHandlerWithConfig bhc : restoredPlugins.birthHandlers()) {
            simulation.addBirthHandler(bhc.handler());
        }
        log.debug("Restored {} birth handlers", restoredPlugins.birthHandlers().size());

        // 12. Build and return RestoredState
        return new RestoredState(
            simulation,
            randomProvider,
            restoredPlugins.tickPlugins(),
            restoredPlugins.interceptors(),
            restoredPlugins.deathHandlers(),
            restoredPlugins.birthHandlers(),
            programs,
            metadata.getSimulationRunId(),
            checkpoint.getResumeFromTick(),
            metadata.getStartTimeMs(),
            metadata.getInitialSeed()
        );
    }

    /**
     * Populates environment cells from columnar cell data.
     * <p>
     * The three columns are parallel: the i-th entries belong to one cell, so a length disagreement
     * means the columns describe different cells and no pairing can be trusted. A flat index outside
     * the environment belongs to a differently shaped world; converting it anyway would place the cell
     * at a wrong but valid position, which is indistinguishable from correct data afterwards.
     *
     * @param environment the environment to populate
     * @param cellData the columnar cell data from the snapshot
     * @throws ResumeException if the columns disagree in length or an index lies outside the environment
     */
    private static void populateCells(Environment environment, CellDataColumns cellData) {
        List<Integer> flatIndices = cellData.getFlatIndicesList();
        List<Integer> moleculeData = cellData.getMoleculeDataList();
        List<Integer> ownerIds = cellData.getOwnerIdsList();

        if (flatIndices.size() != moleculeData.size() || flatIndices.size() != ownerIds.size()) {
            throw new ResumeException("Cell columns disagree in length: "
                    + flatIndices.size() + " indices, " + moleculeData.size() + " molecules, "
                    + ownerIds.size() + " owners");
        }

        int totalCells = environment.getTotalCells();
        for (int i = 0; i < flatIndices.size(); i++) {
            int flatIndex = flatIndices.get(i);
            if (flatIndex < 0 || flatIndex >= totalCells) {
                throw new ResumeException("Cell index " + flatIndex
                        + " lies outside the environment's " + totalCells + " cells");
            }

            int[] coord = environment.getCoordinateFromIndex(flatIndex);
            org.evochora.runtime.model.Molecule molecule =
                org.evochora.runtime.model.Molecule.fromInt(moleculeData.get(i));
            environment.setMolecule(molecule, ownerIds.get(i), coord);
        }
    }

    /**
     * Restores ProgramArtifacts from metadata protobuf.
     */
    private static Map<String, ProgramArtifact> restoreProgramArtifacts(SimulationMetadata metadata) {
        Map<String, ProgramArtifact> programs = new HashMap<>();

        for (var proto : metadata.getProgramsList()) {
            ProgramArtifact artifact = convertProtoProgramArtifact(proto);
            programs.put(artifact.programId(), artifact);
        }

        return programs;
    }

    /**
     * Converts a protobuf ProgramArtifact to runtime ProgramArtifact.
     * This is the reverse of SimulationEngine.convertProgramArtifact().
     */
    private static ProgramArtifact convertProtoProgramArtifact(
            org.evochora.datapipeline.api.contracts.ProgramArtifact proto) {

        // Convert sources
        Map<String, List<String>> sources = new HashMap<>();
        proto.getSourcesMap().forEach((fileName, sourceLines) ->
            sources.put(fileName, new ArrayList<>(sourceLines.getLinesList())));

        // Convert machine code layout (repeated InstructionMapping → Map<int[], Integer>)
        Map<int[], Integer> machineCodeLayout = new HashMap<>();
        for (InstructionMapping mapping : proto.getMachineCodeLayoutList()) {
            machineCodeLayout.put(toIntArray(mapping.getPosition()), mapping.getInstruction());
        }

        // Convert initial world objects
        Map<int[], PlacedMolecule> initialWorldObjects = new HashMap<>();
        for (PlacedMoleculeMapping mapping : proto.getInitialWorldObjectsList()) {
            initialWorldObjects.put(
                toIntArray(mapping.getPosition()),
                new PlacedMolecule(mapping.getMolecule().getType(), mapping.getMolecule().getValue())
            );
        }

        // Convert source map
        Map<Integer, SourceInfo> sourceMap = new HashMap<>();
        for (SourceMapEntry entry : proto.getSourceMapList()) {
            sourceMap.put(entry.getLinearAddress(), convertProtoSourceInfo(entry.getSourceInfo()));
        }

        // Convert call site bindings (formal register ID → source register ID)
        Map<Integer, Map<Integer, Integer>> callSiteBindings = new HashMap<>();
        for (CallSiteBinding binding : proto.getCallSiteBindingsList()) {
            callSiteBindings.put(
                binding.getLinearAddress(),
                new HashMap<>(binding.getBindingsMap())
            );
        }

        // Direct copy of relativeCoordToLinearAddress
        Map<String, Integer> relativeCoordToLinearAddress = new HashMap<>(proto.getRelativeCoordToLinearAddressMap());

        // Convert linearAddressToCoord
        Map<Integer, int[]> linearAddressToCoord = new HashMap<>();
        for (LinearAddressToCoord entry : proto.getLinearAddressToCoordList()) {
            linearAddressToCoord.put(entry.getLinearAddress(), toIntArray(entry.getCoord()));
        }

        // Direct copy of registerAliasMap
        Map<String, Integer> registerAliasMap = new HashMap<>(proto.getRegisterAliasMapMap());

        // Convert procNameToParamNames
        Map<String, List<ParamInfo>> procNameToParamNames = new HashMap<>();
        proto.getProcNameToParamNamesMap().forEach((procName, paramNames) -> {
            List<ParamInfo> params = new ArrayList<>();
            for (var param : paramNames.getParamsList()) {
                params.add(new ParamInfo(param.getName(), convertProtoParamType(param.getType())));
            }
            procNameToParamNames.put(procName, params);
        });

        // Convert tokenMap
        Map<SourceInfo, TokenInfo> tokenMap = new HashMap<>();
        for (TokenMapEntry entry : proto.getTokenMapList()) {
            tokenMap.put(
                convertProtoSourceInfo(entry.getSourceInfo()),
                convertProtoTokenInfo(entry.getTokenInfo())
            );
        }

        // Convert tokenLookup (complex nested structure)
        Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>> tokenLookup = new HashMap<>();
        for (FileTokenLookup fileEntry : proto.getTokenLookupList()) {
            Map<Integer, Map<Integer, List<TokenInfo>>> lineMap = new HashMap<>();
            for (LineTokenLookup lineEntry : fileEntry.getLinesList()) {
                Map<Integer, List<TokenInfo>> columnMap = new HashMap<>();
                for (ColumnTokenLookup colEntry : lineEntry.getColumnsList()) {
                    List<TokenInfo> tokens = colEntry.getTokensList().stream()
                        .map(SimulationRestorer::convertProtoTokenInfo)
                        .collect(Collectors.toList());
                    columnMap.put(colEntry.getColumnNumber(), tokens);
                }
                lineMap.put(lineEntry.getLineNumber(), columnMap);
            }
            tokenLookup.put(fileEntry.getFileName(), lineMap);
        }

        // Convert sourceLineToInstructions
        Map<String, List<MachineInstructionInfo>> sourceLineToInstructions = new HashMap<>();
        proto.getSourceLineToInstructionsMap().forEach((key, list) -> {
            List<MachineInstructionInfo> instructions = list.getInstructionsList().stream()
                .map(i -> new MachineInstructionInfo(
                    i.getLinearAddress(),
                    i.getOpcode(),
                    i.getOperandsAsString(),
                    i.getSynthetic()
                ))
                .collect(Collectors.toList());
            sourceLineToInstructions.put(key, instructions);
        });

        // Direct copy of label maps
        Map<Integer, String> labelValueToName = new HashMap<>(proto.getLabelValueToNameMap());
        Map<String, Integer> labelNameToValue = new HashMap<>(proto.getLabelNameToValueMap());

        return new ProgramArtifact(
            proto.getProgramId(),
            sources,
            machineCodeLayout,
            initialWorldObjects,
            sourceMap,
            callSiteBindings,
            relativeCoordToLinearAddress,
            linearAddressToCoord,
            registerAliasMap,
            procNameToParamNames,
            tokenMap,
            tokenLookup,
            sourceLineToInstructions,
            labelValueToName,
            labelNameToValue
        );
    }

    /**
     * Converts a Protobuf parameter type to the compiler's parameter type.
     * <p>
     * The mapping lives here rather than on the compiler type: the wire format belongs to this
     * pipeline. An unknown or unrecognised value is a data error and fails rather than falling back
     * to a default, which would silently misreport a procedure's calling convention.
     *
     * @param protoType the Protobuf parameter type, must not be null
     * @return the corresponding compiler parameter type
     * @throws ResumeException if the value is null, unrecognised or unknown
     */
    private static ParamType convertProtoParamType(
            org.evochora.datapipeline.api.contracts.ParamType protoType) {
        if (protoType == null) {
            throw new ResumeException("Protobuf ParamType cannot be null");
        }
        return switch (protoType) {
            case PARAM_TYPE_REF -> ParamType.REF;
            case PARAM_TYPE_VAL -> ParamType.VAL;
            case PARAM_TYPE_LREF -> ParamType.LREF;
            case PARAM_TYPE_LVAL -> ParamType.LVAL;
            case UNRECOGNIZED -> throw new ResumeException("Unrecognized ParamType: " + protoType);
            default -> throw new ResumeException("Unknown ParamType: " + protoType);
        };
    }

    /**
     * Converts a protobuf SourceInfo to runtime SourceInfo.
     */
    private static SourceInfo convertProtoSourceInfo(
            org.evochora.datapipeline.api.contracts.SourceInfo proto) {
        return new SourceInfo(proto.getFileName(), proto.getLineNumber(), proto.getColumnNumber());
    }

    /**
     * Converts a protobuf TokenInfo to runtime TokenInfo.
     * <p>
     * The qualified name is optional in both representations: absent on the wire becomes {@code null}
     * in the record, which is what "no qualification applies" means there. The empty string is not a
     * valid qualified name and is therefore never produced.
     */
    private static TokenInfo convertProtoTokenInfo(
            org.evochora.datapipeline.api.contracts.TokenInfo proto) {
        TokenKind kind;
        try {
            kind = TokenKind.valueOf(proto.getTokenType());
        } catch (IllegalArgumentException e) {
            throw new ResumeException("Unknown token type: " + proto.getTokenType(), e);
        }
        return new TokenInfo(
            proto.getTokenText(),
            kind,
            proto.getScope(),
            proto.hasQualifiedName() ? proto.getQualifiedName() : null
        );
    }

    /**
     * Restores a single organism from its protobuf state.
     */
    private static Organism restoreOrganism(OrganismState state, Simulation simulation) {
        Organism.RestoreBuilder builder = Organism.restore(state.getOrganismId(), state.getBirthTick())
            .ip(toIntArray(state.getIp()))
            .dv(toIntArray(state.getDv()))
            .energy(state.getEnergy())
            .entropy(state.getEntropyRegister())
            .marker(state.getMoleculeMarkerRegister())
            .genomeHash(state.getGenomeHash())
            .generation(state.getGeneration())
            .initialPosition(toIntArray(state.getInitialPosition()));

        // Parent ID (optional)
        if (state.hasParentId()) {
            builder.parentId(state.getParentId());
        }

        // Program ID
        builder.programId(state.getProgramId());

        // Data pointers
        int organismId = state.getOrganismId();
        if (state.getDataPointersCount() != org.evochora.runtime.Config.NUM_DATA_POINTERS) {
            throw new ResumeException("Organism " + organismId + " has "
                    + state.getDataPointersCount() + " data pointers, this build has "
                    + org.evochora.runtime.Config.NUM_DATA_POINTERS);
        }
        List<int[]> dps = new ArrayList<>();
        for (Vector v : state.getDataPointersList()) {
            dps.add(toIntArray(v));
        }
        builder.dataPointers(dps);
        if (state.getActiveDpIndex() < 0 || state.getActiveDpIndex() >= dps.size()) {
            throw new ResumeException("Organism " + organismId + " has active data pointer index "
                    + state.getActiveDpIndex() + ", outside its " + dps.size() + " data pointers");
        }
        builder.activeDpIndex(state.getActiveDpIndex());

        // Registers (flat array)
        if (state.getRegistersCount() != RegisterBank.TOTAL_REGISTER_COUNT) {
            throw new ResumeException("Organism " + organismId + " has "
                    + state.getRegistersCount() + " registers, this build has "
                    + RegisterBank.TOTAL_REGISTER_COUNT
                    + "; the checkpoint was written with an incompatible register layout");
        }
        Object[] regs = new Object[state.getRegistersCount()];
        for (int i = 0; i < state.getRegistersCount(); i++) {
            regs[i] = convertRegisterValue(
                    state.getRegisters(i), organismId, RegisterOrigin.FLAT_REGISTER, i);
        }
        builder.registers(regs);

        // Stacks
        requireStackWithinLimit(organismId, "data stack",
                state.getDataStackCount(), org.evochora.runtime.Config.DS_MAX_DEPTH);
        requireStackWithinLimit(organismId, "location stack",
                state.getLocationStackCount(), org.evochora.runtime.Config.LOCATION_STACK_MAX_DEPTH);
        requireStackWithinLimit(organismId, "call stack",
                state.getCallStackCount(), org.evochora.runtime.Config.CALL_STACK_MAX_DEPTH);

        if (state.getDataStackCount() > 0) {
            Deque<Object> dataStack = new ArrayDeque<>();
            int index = 0;
            for (RegisterValue rv : state.getDataStackList()) {
                dataStack.addLast(convertRegisterValue(
                        rv, organismId, RegisterOrigin.DATA_STACK, index++));
            }
            builder.dataStack(dataStack);
        }
        if (state.getLocationStackCount() > 0) {
            Deque<int[]> locationStack = new ArrayDeque<>();
            for (Vector v : state.getLocationStackList()) {
                locationStack.addLast(toIntArray(v));
            }
            builder.locationStack(locationStack);
        }
        if (state.getCallStackCount() > 0) {
            Deque<Organism.ProcFrame> callStack = new ArrayDeque<>();
            for (ProcFrame pf : state.getCallStackList()) {
                callStack.addLast(convertProcFrame(pf, organismId));
            }
            builder.callStack(callStack);
        }

        // Status flags
        builder.dead(state.getIsDead());
        if (state.hasDeathTick()) {
            builder.deathTick(state.getDeathTick());
        }
        if (state.hasParentGenomeHash()) {
            builder.parentGenomeHash(state.getParentGenomeHash());
        }
        if (state.getInstructionFailed()) {
            // The domain sets flag and reason together, so a flag without a reason is a contradiction
            // in the data — about an organism whose program can branch on that very flag via IFER/INER.
            if (!state.hasFailureReason()) {
                throw new ResumeException("Organism " + organismId
                        + " is marked as failed but carries no failure reason");
            }
            builder.failed(true, state.getFailureReason());
            if (state.getFailureCallStackCount() > 0) {
                Deque<Organism.ProcFrame> failureStack = new ArrayDeque<>();
                for (org.evochora.datapipeline.api.contracts.ProcFrame protoFrame : state.getFailureCallStackList()) {
                    failureStack.addLast(convertProcFrame(protoFrame, organismId));
                }
                builder.failureCallStack(failureStack);
            }
        }

        // Persistent register state + dirty flags
        builder.currentProcLabelHash(state.getCurrentProcLabelHash());
        builder.stackSavedDirty(state.getStackSavedDirty());
        builder.persistentDirty(state.getPersistentDirty());
        if (state.hasPersistentRegisterStore()) {
            Map<Integer, Object[]> persistentState = new HashMap<>();
            for (ProcedureRegisterSnapshot snapshot : state.getPersistentRegisterStore().getProcedureSnapshotsList()) {
                if (snapshot.getRegistersCount() != RegisterBank.PERSISTENT_SNAPSHOT_SIZE) {
                    throw new ResumeException("Organism " + organismId + ": persistent snapshot for label "
                            + snapshot.getLabelHash() + " holds " + snapshot.getRegistersCount()
                            + " values, this build has " + RegisterBank.PERSISTENT_SNAPSHOT_SIZE);
                }
                Object[] procRegs = new Object[snapshot.getRegistersCount()];
                for (int i = 0; i < snapshot.getRegistersCount(); i++) {
                    procRegs[i] = convertRegisterValue(
                            snapshot.getRegisters(i), organismId, RegisterOrigin.PERSISTENT_STORE, i);
                }
                if (persistentState.put(snapshot.getLabelHash(), procRegs) != null) {
                    // Each procedure has one persistent register set; a second entry for the same
                    // label offers two, and taking the later one would pick a state at random.
                    throw new ResumeException("Organism " + organismId
                            + ": persistent register state occurs more than once for label "
                            + snapshot.getLabelHash());
                }
            }
            builder.persistentRegisterState(persistentState);
        }

        try {
            return builder.build(simulation);
        } catch (Organism.InvalidRestoreState e) {
            // The builder defends the organism's invariants for any caller; here the caller is a
            // checkpoint, so a violation is a data error and belongs in the resume path with the
            // organism named. This covers the checks the restorer does not repeat — coordinate
            // dimensions among them — and any invariant added to the builder later.
            //
            // Only that one type: every other failure inside build() says the code is wrong, not the
            // checkpoint, and reporting it as unusable data would send the search in the wrong
            // direction.
            throw new ResumeException(
                    "Organism " + organismId + " cannot be restored: " + e.getMessage(), e);
        }
    }

    /**
     * Rejects a restored stack deeper than the instruction set allows. Such a depth describes a state
     * no running organism can reach, because the instruction that would exceed the limit fails instead
     * of pushing.
     * <p>
     * {@code Organism.RestoreBuilder} checks the same limits for every caller that builds an organism.
     * The two are deliberately separate rather than sharing a helper: this one speaks for the
     * checkpoint and names it in the message, and a shared helper would have to live in one of the two
     * packages — in {@code runtime}, which depends on nothing, or in {@code datapipeline}, which
     * {@code runtime} must not depend on.
     *
     * @param organismId the organism the stack belongs to
     * @param name the stack's name, for the message
     * @param depth the depth found in the snapshot
     * @param limit the maximum depth the instruction set enforces
     * @throws ResumeException if the depth exceeds the limit
     */
    private static void requireStackWithinLimit(int organismId, String name, int depth, int limit) {
        if (depth > limit) {
            throw new ResumeException("Organism " + organismId + ": " + name + " holds " + depth
                    + " entries, above the limit of " + limit);
        }
    }

    /**
     * The structure a converted register value was read from, for diagnosable failure messages.
     * <p>
     * The constant names below are part of those messages: they reach whoever reads a rejected
     * resume, and tests assert on them. Renaming one changes what an operator sees, so it is a
     * change to the wording of a failure rather than to an internal identifier.
     */
    private enum RegisterOrigin {
        FLAT_REGISTER,
        DATA_STACK,
        PROC_FRAME_SAVED,
        PERSISTENT_STORE
    }

    /**
     * Converts a single RegisterValue proto to Object (Integer or int[]).
     * <p>
     * The message is a {@code oneof} over a scalar and a vector, and a third state exists: neither
     * set. The write side cannot produce it — every branch there sets a case, and a scalar zero
     * carries field presence — so it means corrupt data, a schema this reader does not know, or a
     * future write-side defect. Substituting a value would restore a vector register as a scalar or
     * a stored value as zero, indistinguishable from correctly stored data.
     *
     * @param rv the value to convert
     * @param organismId the organism the value belongs to
     * @param origin the structure the value was read from
     * @param index the value's position within that structure
     * @return the scalar as {@link Integer} or the vector as {@code int[]}
     * @throws ResumeException if neither case of the oneof is set
     */
    private static Object convertRegisterValue(
            RegisterValue rv, int organismId, RegisterOrigin origin, int index) {
        if (rv.hasScalar()) {
            return rv.getScalar();
        } else if (rv.hasVector()) {
            return toIntArray(rv.getVector());
        }
        throw new ResumeException("Organism " + organismId + ": register value at " + origin
                + "[" + index + "] has neither scalar nor vector set");
    }

    /**
     * Converts a ProcFrame proto to runtime ProcFrame.
     * <p>
     * An absent register snapshot must be restored as {@code null}, not as an empty array. At
     * runtime, {@code null} means "the caller had not written any stack-saved register, so no
     * snapshot was taken", and RET reacts to it by resetting those registers instead of restoring
     * them. Protobuf cannot express that distinction — a repeated field is empty in both cases —
     * so the distinction is re-established here by the element count.
     * <p>
     * An empty array instead of {@code null} would make RET attempt a restore from a zero-length
     * snapshot, which the runtime rejects as a size mismatch.
     */
    private static Organism.ProcFrame convertProcFrame(ProcFrame pf, int organismId) {
        Object[] savedRegisters = null;
        if (pf.getSavedRegistersCount() > 0) {
            if (pf.getSavedRegistersCount() != RegisterBank.STACK_SAVED_SNAPSHOT_SIZE) {
                throw new ResumeException("Organism " + organismId + ": call frame for label "
                        + pf.getLabelHash() + " holds " + pf.getSavedRegistersCount()
                        + " saved registers, this build has " + RegisterBank.STACK_SAVED_SNAPSHOT_SIZE
                        + " (an absent snapshot is expressed by holding none)");
            }
            savedRegisters = new Object[pf.getSavedRegistersCount()];
            for (int i = 0; i < savedRegisters.length; i++) {
                savedRegisters[i] = convertRegisterValue(
                        pf.getSavedRegisters(i), organismId, RegisterOrigin.PROC_FRAME_SAVED, i);
            }
        }

        Map<Integer, Integer> parameterBindings = new HashMap<>(pf.getParameterBindingsMap());

        return new Organism.ProcFrame(
            pf.getLabelHash(),
            toIntArray(pf.getAbsoluteReturnIp()),
            toIntArray(pf.getAbsoluteCallIp()),
            savedRegisters,
            parameterBindings
        );
    }

    /**
     * Holds restored plugins separated by type.
     */
    private record RestoredPlugins(
        List<PluginWithConfig> tickPlugins,
        List<InterceptorWithConfig> interceptors,
        List<DeathHandlerWithConfig> deathHandlers,
        List<BirthHandlerWithConfig> birthHandlers
    ) {}

    /**
     * Restores plugins from config and saved states, separating by interface type.
     * <p>
     * A plugin can implement multiple interfaces (ITickPlugin, IInstructionInterceptor, IDeathHandler),
     * in which case it will appear in multiple lists (with shared instance).
     *
     * @param pluginConfigs List of plugin configurations from resolvedConfigJson
     * @param savedStates List of saved plugin states from snapshot
     * @param randomProvider The random provider for plugin initialization
     * @return RestoredPlugins containing separate lists for each plugin type
     */
    private static RestoredPlugins restorePlugins(
            List<? extends Config> pluginConfigs,
            List<PluginState> savedStates,
            IRandomProvider randomProvider) {

        // Build map of class -> saved state. A duplicate class is unrecoverable: the schema keys state
        // by class name, so two instances of one class produce two entries that cannot be told apart.
        Map<String, byte[]> stateByClass = new HashMap<>();
        for (PluginState ps : savedStates) {
            byte[] previous = stateByClass.put(ps.getPluginClass(), ps.getStateBlob().toByteArray());
            if (previous != null) {
                throw new ResumeException("Checkpoint holds more than one state for plugin "
                        + ps.getPluginClass() + "; the states cannot be assigned to instances");
            }
        }

        Set<String> unusedStates = new HashSet<>(stateByClass.keySet());
        Set<String> configuredClasses = new HashSet<>();

        List<PluginWithConfig> tickPlugins = new ArrayList<>();
        List<InterceptorWithConfig> interceptors = new ArrayList<>();
        List<DeathHandlerWithConfig> deathHandlers = new ArrayList<>();
        List<BirthHandlerWithConfig> birthHandlers = new ArrayList<>();

        for (Config pluginConfig : pluginConfigs) {
            String className = pluginConfig.getString("className");
            try {
                // Get plugin options (may be empty)
                Config options = pluginConfig.hasPath("options")
                    ? pluginConfig.getConfig("options")
                    : ConfigFactory.empty();

                // Instantiate via reflection - plugin must implement ISimulationPlugin
                Object plugin = Class.forName(className)
                    .getConstructor(IRandomProvider.class, Config.class)
                    .newInstance(randomProvider, options);

                // Restore saved state (ISimulationPlugin extends ISerializable). An empty blob is the
                // legitimate value for a plugin that had not initialized itself when the snapshot was
                // taken; a missing entry means the checkpoint does not describe this plugin at all.
                if (plugin instanceof ISimulationPlugin simulationPlugin) {
                    if (!configuredClasses.add(className)) {
                        // State is keyed by class, so two instances of one class have no way to get
                        // their own state back. Rejecting here names the configuration; the duplicate
                        // check on the stored states would name the checkpoint instead.
                        throw new ResumeException("Plugin " + className
                                + " is configured more than once; their states cannot be told apart");
                    }
                    byte[] savedState = stateByClass.get(className);
                    if (savedState == null) {
                        throw new ResumeException("Checkpoint holds no state for configured plugin "
                                + className + "; it would resume with a fresh state");
                    }
                    unusedStates.remove(className);
                    if (savedState.length > 0) {
                        try {
                            simulationPlugin.loadState(savedState);
                        } catch (RuntimeException e) {
                            // Separate from the failure below: the plugin exists and was built, and
                            // what cannot be read is the state stored for it. Saying "failed to
                            // instantiate" here would point at the configured class name while the
                            // checkpoint is what needs looking at.
                            throw new ResumeException("Checkpoint holds unreadable state for plugin "
                                    + className + " (" + savedState.length + " bytes)", e);
                        }
                        log.debug("Loaded state for plugin {} ({} bytes)", className, savedState.length);
                    }
                }

                // Classify by interface - a plugin can implement multiple interfaces
                if (plugin instanceof ITickPlugin tickPlugin) {
                    tickPlugins.add(new PluginWithConfig(tickPlugin, options));
                }
                if (plugin instanceof IInstructionInterceptor interceptor) {
                    interceptors.add(new InterceptorWithConfig(interceptor, options));
                }
                if (plugin instanceof IDeathHandler deathHandler) {
                    deathHandlers.add(new DeathHandlerWithConfig(deathHandler, options));
                }
                if (plugin instanceof IBirthHandler birthHandler) {
                    birthHandlers.add(new BirthHandlerWithConfig(birthHandler, options));
                }

                // Warn if plugin implements no known interface
                if (!(plugin instanceof ITickPlugin) && !(plugin instanceof IInstructionInterceptor)
                        && !(plugin instanceof IDeathHandler) && !(plugin instanceof IBirthHandler)) {
                    log.warn("Plugin {} does not implement ITickPlugin, IInstructionInterceptor, IDeathHandler, or IBirthHandler", className);
                }
            } catch (ResumeException e) {
                // Already says what is wrong with the checkpoint; wrapping it would replace that with
                // "failed to instantiate", which is not what happened.
                throw e;
            } catch (Exception e) {
                throw new ResumeException("Failed to instantiate plugin: " + className, e);
            }
        }

        if (!unusedStates.isEmpty()) {
            throw new ResumeException("Checkpoint holds state for plugins that are not configured: "
                    + unusedStates);
        }

        return new RestoredPlugins(tickPlugins, interceptors, deathHandlers, birthHandlers);
    }

    /**
     * Converts a Vector proto to int array.
     */
    private static int[] toIntArray(Vector v) {
        int[] result = new int[v.getComponentsCount()];
        for (int i = 0; i < v.getComponentsCount(); i++) {
            result[i] = v.getComponents(i);
        }
        return result;
    }
}
