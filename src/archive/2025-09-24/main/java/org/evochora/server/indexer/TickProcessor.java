package org.evochora.server.indexer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.indexer.ArtifactValidator.ArtifactValidity;
import org.evochora.server.config.SimulationConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Processes and transforms raw tick data into prepared debug tick states.
 * Handles world state transformation, organism details building, and coordinates between helper classes.
 */
public class TickProcessor {
    
    private static final Logger log = LoggerFactory.getLogger(TickProcessor.class);
    
    private final ObjectMapper objectMapper;
    private final ArtifactValidator artifactValidator;
    private final SourceViewBuilder sourceViewBuilder;
    private final InstructionBuilder instructionBuilder;
    private final InternalStateBuilder internalStateBuilder;
    private final SimulationConfiguration.MemoryOptimizationConfig memoryOptimizationConfig;
    
    // ThreadLocal collections for memory optimization
    private final ThreadLocal<ArrayList<RawOrganismState>> tempOrganismList;
    private final ThreadLocal<HashMap<String, Object>> tempMap;
    
    public TickProcessor(ObjectMapper objectMapper, 
                        ArtifactValidator artifactValidator,
                        SourceViewBuilder sourceViewBuilder,
                        InstructionBuilder instructionBuilder,
                        InternalStateBuilder internalStateBuilder,
                        SimulationConfiguration.MemoryOptimizationConfig memoryOptimizationConfig) {
        this.objectMapper = objectMapper;
        this.artifactValidator = artifactValidator;
        this.sourceViewBuilder = sourceViewBuilder;
        this.instructionBuilder = instructionBuilder;
        this.internalStateBuilder = internalStateBuilder;
        this.memoryOptimizationConfig = memoryOptimizationConfig != null ? memoryOptimizationConfig : new SimulationConfiguration.MemoryOptimizationConfig();
        
        // Initialize ThreadLocal collections if memory optimization is enabled
        this.tempOrganismList = ThreadLocal.withInitial(() -> 
            this.memoryOptimizationConfig.enabled ? new ArrayList<>(100) : new ArrayList<>());
        this.tempMap = ThreadLocal.withInitial(() -> 
            this.memoryOptimizationConfig.enabled ? new HashMap<>(50) : new HashMap<>());
    }
    
    /**
     * Transforms a raw tick state into a prepared debug tick state.
     * 
     * @param raw The raw tick state to transform
     * @param artifacts Map of program artifacts by program ID
     * @param envProps Environment properties for the simulation
     * @return The transformed prepared tick state
     */
    public PreparedTickState transformRawToPrepared(RawTickState raw, 
                                                   Map<String, ProgramArtifact> artifacts,
                                                   EnvironmentProperties envProps) {
        if (envProps == null) {
            throw new IllegalStateException("EnvironmentProperties not available for transformRawToPrepared");
        }
        
        PreparedTickState.WorldMeta meta = new PreparedTickState.WorldMeta(envProps.getWorldShape());

        List<PreparedTickState.Cell> cells = raw.cells().stream()
                .map(c -> {
                    Molecule m = Molecule.fromInt(c.molecule());
                    String opcodeName = null;
                    if (m.type() == Config.TYPE_CODE && (m.value() != 0 || c.ownerId() != 0)) {
                        opcodeName = Instruction.getInstructionNameById(m.toInt());
                    }
                                         return new PreparedTickState.Cell(toList(c.pos()), MoleculeTypeUtils.typeIdToName(m.type()), m.toScalarValue(), c.ownerId(), opcodeName);
                }).toList();

        List<PreparedTickState.OrganismBasic> orgBasics = raw.organisms().stream()
                .filter(o -> !o.isDead())
                .map(o -> new PreparedTickState.OrganismBasic(o.id(), o.programId(), toList(o.ip()), o.er(), o.dps().stream().map(this::toList).toList(), toList(o.dv())))
                .toList();

        PreparedTickState.WorldState worldState = new PreparedTickState.WorldState(cells, orgBasics);

        Map<String, PreparedTickState.OrganismDetails> details = new HashMap<>();
        for (RawOrganismState o : raw.organisms()) {
            if (o.isDead()) continue;
            
                         // Central method for all organism details
             PreparedTickState.OrganismDetails organismDetails = buildOrganismDetails(o, raw, artifacts, envProps);
            details.put(String.valueOf(o.id()), organismDetails);
        }

        return new PreparedTickState("debug", raw.tickNumber(), meta, worldState, details);
    }
    
    /**
     * Central method for creating all organism details.
     * Coordinates validation and calls all builders.
     * 
     * @param organism The organism to build details for
     * @param rawTickState The raw tick state for context
     * @param artifacts Map of program artifacts by program ID
     * @return The complete organism details
     */
        private PreparedTickState.OrganismDetails buildOrganismDetails(RawOrganismState organism, 
                                                                   RawTickState rawTickState,
                                                                   Map<String, ProgramArtifact> artifacts,
                                                                   EnvironmentProperties envProps) {
        ProgramArtifact artifact = artifacts.get(organism.programId());
        ArtifactValidity validity = artifactValidator.checkArtifactValidity(organism, artifact);
        
        var basicInfo = new PreparedTickState.BasicInfo(organism.id(), organism.programId(), organism.parentId(), 
                                                       organism.birthTick(), organism.er(), toList(organism.ip()), toList(organism.dv()));
                 var nextInstruction = instructionBuilder.buildNextInstruction(organism, artifact, validity, rawTickState, envProps);
        var internalState = internalStateBuilder.buildInternalState(organism, artifact, validity);
        var sourceView = sourceViewBuilder.buildSourceView(organism, artifact, validity);

        return new PreparedTickState.OrganismDetails(basicInfo, nextInstruction, internalState, sourceView);
    }
    
    /**
     * Converts an int array to a List<Integer>.
     * 
     * @param arr The array to convert
     * @return The list
     */
    private List<Integer> toList(int[] arr) {
        if (arr == null) return new ArrayList<>();
        return Arrays.stream(arr).boxed().collect(Collectors.toList());
    }
    

}
