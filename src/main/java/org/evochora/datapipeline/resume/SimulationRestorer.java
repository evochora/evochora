package org.evochora.datapipeline.resume;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.evochora.compiler.api.MachineInstructionInfo;
import org.evochora.compiler.api.ParamInfo;
import org.evochora.compiler.api.ParamType;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.frontend.semantics.Symbol;
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
import org.evochora.datapipeline.api.contracts.RegisterValue;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.SourceMapEntry;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TokenMapEntry;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.runtime.Simulation;
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
     */
    public record PluginWithConfig(ITickPlugin plugin, Config config) {}

    /**
     * Bundles an instruction interceptor with its configuration.
     * Used for extracting plugin state during simulation.
     */
    public record InterceptorWithConfig(IInstructionInterceptor interceptor, Config config) {}

    /**
     * Bundles a death handler with its configuration.
     * Used for extracting plugin state during simulation.
     */
    public record DeathHandlerWithConfig(IDeathHandler handler, Config config) {}

    /**
     * Bundles a birth handler with its configuration.
     * Used for extracting plugin state during simulation.
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
     *   <li>The restored Simulation (with Environment, Organisms, ProgramArtifacts)</li>
     *   <li>The IRandomProvider with restored RNG state</li>
     *   <li>The instantiated TickPlugins with restored state</li>
     *   <li>The original runId and metadata for continuity</li>
     * </ul>
     *
     * @param checkpoint The loaded checkpoint data
     * @param randomProvider A fresh IRandomProvider instance (state will be loaded into it)
     * @return A RestoredState ready for SimulationEngine initialization
     * @throws ResumeException if restoration fails
     */
    public static RestoredState restore(ResumeCheckpoint checkpoint, IRandomProvider randomProvider) {
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
        populateCells(environment, snapshot.getCellColumns(), shape);

        // 6. Extract state from snapshot (always complete since we resume from chunk start)
        long currentTick = snapshot.getTickNumber();
        long totalOrganismsCreated = snapshot.getTotalOrganismsCreated();
        ByteString rngState = snapshot.getRngState();
        List<OrganismState> organismStates = snapshot.getOrganismsList();
        List<PluginState> pluginStates = snapshot.getPluginStatesList();

        // 7. Restore genome hash set from snapshot (with backwards-compatible fallback)
        LongOpenHashSet allGenomesEverSeen = new LongOpenHashSet();
        List<Long> savedHashes = snapshot.getAllGenomeHashesEverSeenList();
        if (!savedHashes.isEmpty()) {
            for (long hash : savedHashes) {
                allGenomesEverSeen.add(hash);
            }
        } else {
            // Backwards compatibility: old simulations have no saved genome set.
            // Reconstruct from living organisms' genome hashes (loses extinct genomes).
            for (OrganismState org : organismStates) {
                long hash = org.getGenomeHash();
                if (hash != 0L) {
                    allGenomesEverSeen.add(hash);
                }
            }
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
            organismConfig
        );

        // 8. Restore RNG state
        if (!rngState.isEmpty()) {
            randomProvider.loadState(rngState.toByteArray());
            log.debug("Loaded RNG state ({} bytes)", rngState.size());
        }
        simulation.setRandomProvider(randomProvider);
        labelMatchingStrategy.setRandomProvider(randomProvider.deriveFor("labelMatching", 0));

        // 9. Restore ProgramArtifacts
        Map<String, ProgramArtifact> programs = restoreProgramArtifacts(metadata);
        simulation.setProgramArtifacts(programs);
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
     */
    private static void populateCells(Environment environment, CellDataColumns cellData, int[] shape) {
        List<Integer> flatIndices = cellData.getFlatIndicesList();
        List<Integer> moleculeData = cellData.getMoleculeDataList();
        List<Integer> ownerIds = cellData.getOwnerIdsList();

        for (int i = 0; i < flatIndices.size(); i++) {
            int flatIndex = flatIndices.get(i);
            int moleculeValue = moleculeData.get(i);
            int ownerId = ownerIds.get(i);

            // Convert flat index to coordinates
            int[] coord = flatIndexToCoord(flatIndex, shape);

            // Convert packed int to Molecule and set with owner
            org.evochora.runtime.model.Molecule molecule =
                org.evochora.runtime.model.Molecule.fromInt(moleculeValue);
            environment.setMolecule(molecule, ownerId, coord);
        }
    }

    /**
     * Converts a flat index to n-dimensional coordinates.
     */
    private static int[] flatIndexToCoord(int flatIndex, int[] shape) {
        int[] coord = new int[shape.length];
        int remaining = flatIndex;
        for (int dim = shape.length - 1; dim >= 0; dim--) {
            coord[dim] = remaining % shape[dim];
            remaining /= shape[dim];
        }
        return coord;
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

        // Convert call site bindings
        Map<Integer, int[]> callSiteBindings = new HashMap<>();
        for (CallSiteBinding binding : proto.getCallSiteBindingsList()) {
            callSiteBindings.put(
                binding.getLinearAddress(),
                binding.getRegisterIdsList().stream().mapToInt(Integer::intValue).toArray()
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
                params.add(new ParamInfo(param.getName(), ParamType.fromProtobuf(param.getType())));
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
     * Converts a protobuf SourceInfo to runtime SourceInfo.
     */
    private static SourceInfo convertProtoSourceInfo(
            org.evochora.datapipeline.api.contracts.SourceInfo proto) {
        return new SourceInfo(proto.getFileName(), proto.getLineNumber(), proto.getColumnNumber());
    }

    /**
     * Converts a protobuf TokenInfo to runtime TokenInfo.
     */
    private static TokenInfo convertProtoTokenInfo(
            org.evochora.datapipeline.api.contracts.TokenInfo proto) {
        return new TokenInfo(
            proto.getTokenText(),
            Symbol.Type.valueOf(proto.getTokenType()),
            proto.getScope()
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
            .initialPosition(toIntArray(state.getInitialPosition()));

        // Parent ID (optional)
        if (state.hasParentId()) {
            builder.parentId(state.getParentId());
        }

        // Program ID
        if (!state.getProgramId().isEmpty()) {
            builder.programId(state.getProgramId());
        }

        // Data pointers
        if (state.getDataPointersCount() > 0) {
            List<int[]> dps = new ArrayList<>();
            for (Vector v : state.getDataPointersList()) {
                dps.add(toIntArray(v));
            }
            builder.dataPointers(dps);
            builder.activeDpIndex(state.getActiveDpIndex());
        }

        // Registers
        if (state.getDataRegistersCount() > 0) {
            builder.dataRegisters(convertRegisterValues(state.getDataRegistersList()));
        }
        if (state.getProcedureRegistersCount() > 0) {
            builder.procRegisters(convertRegisterValues(state.getProcedureRegistersList()));
        }
        if (state.getFormalParamRegistersCount() > 0) {
            builder.formalParamRegisters(convertRegisterValues(state.getFormalParamRegistersList()));
        }
        if (state.getLocationRegistersCount() > 0) {
            List<Object> lrs = new ArrayList<>();
            for (Vector v : state.getLocationRegistersList()) {
                lrs.add(toIntArray(v));
            }
            builder.locationRegisters(lrs);
        }

        // Stacks
        if (state.getDataStackCount() > 0) {
            Deque<Object> dataStack = new ArrayDeque<>();
            for (RegisterValue rv : state.getDataStackList()) {
                dataStack.addLast(convertRegisterValue(rv));
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
                callStack.addLast(convertProcFrame(pf));
            }
            builder.callStack(callStack);
        }

        // Status flags
        builder.dead(state.getIsDead());
        if (state.hasDeathTick()) {
            builder.deathTick(state.getDeathTick());
        }
        if (state.getInstructionFailed()) {
            String reason = state.hasFailureReason() ? state.getFailureReason() : "Unknown";
            builder.failed(true, reason);
        }

        return builder.build(simulation);
    }

    /**
     * Converts a list of RegisterValue protos to a list of Objects (Integer or int[]).
     */
    private static List<Object> convertRegisterValues(List<RegisterValue> values) {
        List<Object> result = new ArrayList<>();
        for (RegisterValue rv : values) {
            result.add(convertRegisterValue(rv));
        }
        return result;
    }

    /**
     * Converts a single RegisterValue proto to Object (Integer or int[]).
     */
    private static Object convertRegisterValue(RegisterValue rv) {
        if (rv.hasScalar()) {
            return rv.getScalar();
        } else if (rv.hasVector()) {
            return toIntArray(rv.getVector());
        }
        return 0; // Default for unset
    }

    /**
     * Converts a ProcFrame proto to runtime ProcFrame.
     */
    private static Organism.ProcFrame convertProcFrame(ProcFrame pf) {
        // Convert saved registers
        Object[] savedPrs = new Object[pf.getSavedPrsCount()];
        for (int i = 0; i < pf.getSavedPrsCount(); i++) {
            savedPrs[i] = convertRegisterValue(pf.getSavedPrs(i));
        }

        Object[] savedFprs = new Object[pf.getSavedFprsCount()];
        for (int i = 0; i < pf.getSavedFprsCount(); i++) {
            savedFprs[i] = convertRegisterValue(pf.getSavedFprs(i));
        }

        // Convert FPR bindings map
        Map<Integer, Integer> fprBindings = new HashMap<>(pf.getFprBindingsMap());

        return new Organism.ProcFrame(
            pf.getProcName(),
            toIntArray(pf.getAbsoluteReturnIp()),
            toIntArray(pf.getAbsoluteCallIp()),
            savedPrs,
            savedFprs,
            fprBindings
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

        // Build map of class -> saved state
        Map<String, byte[]> stateByClass = new HashMap<>();
        for (PluginState ps : savedStates) {
            stateByClass.put(ps.getPluginClass(), ps.getStateBlob().toByteArray());
        }

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

                // Restore saved state if available (ISimulationPlugin extends ISerializable)
                if (plugin instanceof ISimulationPlugin simulationPlugin) {
                    byte[] savedState = stateByClass.get(className);
                    if (savedState != null && savedState.length > 0) {
                        simulationPlugin.loadState(savedState);
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
            } catch (Exception e) {
                throw new ResumeException("Failed to instantiate plugin: " + className, e);
            }
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
