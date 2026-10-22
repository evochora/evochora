package org.evochora.compiler.internal;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.runtime.model.EnvironmentProperties;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON form of a ProgramArtifact, in which no map has an int[] key.
 * 
 * <h2>Purpose</h2>
 * A JSON object key is a string, so the maps of the artifact keyed by a coordinate are written as
 * lists of entries, each carrying its coordinate relative to the program's origin as an array.
 * Where the program lands in the world is decided by the start position the simulation gives
 * it, so no coordinate is turned into a cell of the world.
 * 
 * <h2>Usage</h2>
 * <pre>{@code
 * ProgramArtifact original = ...;
 * EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
 * String json = new Gson().toJson(JsonProgramArtifact.from(original, envProps));
 * }</pre>
 * 
 * <p>This is a write-only representation: it turns an artifact into JSON for inspection and is
 * never read back. A stored artifact is restored from the protobuf representation in
 * {@code metadata_contracts.proto} instead, which covers every field of the record.</p>
 * 
 * <h2>Fields Written as Lists</h2>
 * Only the following fields change their form, in the order of the artifact, which sorts them by
 * coordinate:
 * <ul>
 *   <li><strong>machineCodeLayout</strong>: {@code Map<int[], Integer>} &rarr; {@code List<InstructionMapping>}</li>
 *   <li><strong>initialWorldObjects</strong>: {@code Map<int[], PlacedMolecule>} &rarr; {@code List<PlacedMoleculeMapping>}</li>
 * </ul>
 * 
 * <h2>Unchanged Fields</h2>
 * All other fields remain unchanged:
 * <ul>
 *   <li><strong>sources</strong>: {@code List<SourceFile>} (unchanged)</li>
 *   <li><strong>expansions</strong>: {@code Map<Integer, Expansion>} (unchanged)</li>
 *   <li><strong>sourceMap</strong>: {@code Map<Integer, JsonSourceInfo>} (unchanged)</li>
 *   <li><strong>callSiteBindings</strong>: {@code Map<Integer, Map<Integer, Integer>>} (unchanged)</li>
 *   <li><strong>linearAddressToCoord</strong>: {@code Map<Integer, int[]>} (unchanged)</li>
 *   <li><strong>registerAliasMap</strong>: {@code Map<String, Integer>} (unchanged)</li>
 *   <li><strong>constantValues</strong>: {@code Map<String, String>} (unchanged)</li>
 *   <li><strong>procNameToParamNames</strong>: {@code Map<String, List<ParamInfo>>} (unchanged)</li>
 *   <li><strong>tokenMap</strong>: {@code Map<JsonSourceInfo, TokenInfo>} (unchanged)</li>
 *   <li><strong>tokenLookup</strong>: {@code Map<String, Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>>>} (unchanged)</li>
 *   <li><strong>sourceLineToInstructions</strong>: {@code Map<String, Map<String, Map<Integer, Map<Integer, List<MachineInstructionInfo>>>>>} (unchanged)</li>
 * </ul>
 * 
 * @param programId Unique identifier for the compiled program.
 * @param sources One entry per inclusion of a file.
 * @param expansions Map from the number of an instance of injected tokens that is no inclusion to
 *                   where it came from and the regions and notes recorded in it.
 * @param machineCodeLayout The cells of the code, each with its coordinate and encoded molecule.
 * @param initialWorldObjects The placed molecules, each with its coordinate.
 * @param sourceMap Map from linear address to serializable source info.
 * @param callSiteBindings Map from the address recorded for a CALL instruction to that call's
 *                         parameter bindings: formal register id to caller register.
 * @param linearAddressToCoord Map from linear address to relative coordinate array.
 * @param registerAliasMap Map from the path of a register alias to its physical register index.
 * @param constantValues Map from the path of a constant to its value as text.
 * @param procNameToParamNames Map from procedure names to parameter info lists.
 * @param tokenMap Map from serializable source info to token info.
 * @param tokenLookup Map from placement/file/line/col to token info list.
 * @param sourceLineToInstructions Map from placement/file/expansion/line to machine instruction info list; the
 *                                 expansion is the instance of an entry or the number of an expansion.
 * @param labelValueToName Map from label hash value to label name.
 * @param labelNameToValue Map from label name to label hash value.
 * @param envProps The world the artifact was compiled for.
 * @see ProgramArtifact
 * @since 1.0
 */
public record JsonProgramArtifact(
        String programId,
        List<org.evochora.compiler.api.SourceFile> sources,
        Map<Integer, org.evochora.compiler.api.Expansion> expansions,
        List<InstructionMapping> machineCodeLayout,
        List<PlacedMoleculeMapping> initialWorldObjects,
        Map<Integer, JsonSourceInfo> sourceMap,
        Map<Integer, Map<Integer, Integer>> callSiteBindings,
        Map<Integer, int[]> linearAddressToCoord,
        Map<String, Integer> registerAliasMap,
        Map<String, String> constantValues,
        Map<String, List<org.evochora.compiler.api.ParamInfo>> procNameToParamNames,
        Map<JsonSourceInfo, TokenInfo> tokenMap,
        Map<String, Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>>> tokenLookup,
        Map<String, Map<String, Map<Integer, Map<Integer, List<org.evochora.compiler.api.MachineInstructionInfo>>>>> sourceLineToInstructions,
        Map<Integer, String> labelValueToName,
        Map<String, Integer> labelNameToValue,
        EnvironmentProperties envProps
) {
    
    /**
     * Canonical constructor that makes the artifact immutable and free of nulls: every map component
     * and the list of sources is wrapped in an unmodifiable view, a null one becomes an empty one, and null environment
     * properties become a zero-dimensional, non-toroidal environment. Wrapping does not copy, so a
     * caller that keeps a reference to a map it passed in can still change what the artifact exposes.
     */
    public JsonProgramArtifact {
        sources = sources != null ? Collections.unmodifiableList(sources) : Collections.emptyList();
        expansions = expansions != null ? Collections.unmodifiableMap(expansions) : Collections.emptyMap();
        machineCodeLayout = machineCodeLayout != null ? Collections.unmodifiableList(machineCodeLayout) : Collections.emptyList();
        initialWorldObjects = initialWorldObjects != null ? Collections.unmodifiableList(initialWorldObjects) : Collections.emptyList();
        sourceMap = sourceMap != null ? Collections.unmodifiableMap(sourceMap) : Collections.emptyMap();
        callSiteBindings = callSiteBindings != null ? Collections.unmodifiableMap(callSiteBindings) : Collections.emptyMap();
        linearAddressToCoord = linearAddressToCoord != null ? Collections.unmodifiableMap(linearAddressToCoord) : Collections.emptyMap();
        registerAliasMap = registerAliasMap != null ? Collections.unmodifiableMap(registerAliasMap) : Collections.emptyMap();
        constantValues = constantValues != null ? Collections.unmodifiableMap(constantValues) : Collections.emptyMap();
        procNameToParamNames = procNameToParamNames != null ? Collections.unmodifiableMap(procNameToParamNames) : Collections.emptyMap();
        tokenMap = tokenMap != null ? Collections.unmodifiableMap(tokenMap) : Collections.emptyMap();
        tokenLookup = tokenLookup != null ? Collections.unmodifiableMap(tokenLookup) : Collections.emptyMap();
        sourceLineToInstructions = sourceLineToInstructions != null ? Collections.unmodifiableMap(sourceLineToInstructions) : Collections.emptyMap();
        labelValueToName = labelValueToName != null ? Collections.unmodifiableMap(labelValueToName) : Collections.emptyMap();
        labelNameToValue = labelNameToValue != null ? Collections.unmodifiableMap(labelNameToValue) : Collections.emptyMap();
        envProps = envProps != null ? envProps : new EnvironmentProperties(new int[0], false);
    }
    
    /**
     * Converts a ProgramArtifact to a JsonProgramArtifact.
     * @param artifact The ProgramArtifact to convert.
     * @param envProps The world the artifact was compiled for, written into the JSON with it.
     * @throws IllegalArgumentException if {@code envProps} is null.
     * @return A new JsonProgramArtifact.
     */
    public static JsonProgramArtifact from(ProgramArtifact artifact, EnvironmentProperties envProps) {
        if (envProps == null) {
            throw new IllegalArgumentException("EnvironmentProperties must not be null");
        }
        return new JsonProgramArtifact(
                artifact.programId(),
                artifact.sources(),
                artifact.expansions(),
                artifact.machineCodeLayout().entrySet().stream()
                        .map(cell -> new InstructionMapping(cell.getKey(), cell.getValue())).toList(),
                artifact.initialWorldObjects().entrySet().stream()
                        .map(placed -> new PlacedMoleculeMapping(placed.getKey(), placed.getValue())).toList(),
                convertSourceMap(artifact.sourceMap()),
                artifact.callSiteBindings(),
                artifact.linearAddressToCoord(),
                artifact.registerAliasMap(),
                artifact.constantValues(),
                artifact.procNameToParamNames(),
                convertTokenMap(artifact.tokenMap()),
                artifact.tokenLookup(),
                artifact.sourceLineToInstructions(),
                artifact.labelValueToName(),
                artifact.labelNameToValue(),
                envProps
        );
    }
    
    /**
     * Converts a Map<Integer, SourceInfo> to Map<Integer, JsonSourceInfo>
     */
    private static Map<Integer, JsonSourceInfo> convertSourceMap(Map<Integer, org.evochora.compiler.api.SourceInfo> sourceMap) {
        Map<Integer, JsonSourceInfo> result = new HashMap<>();
        for (Map.Entry<Integer, org.evochora.compiler.api.SourceInfo> entry : sourceMap.entrySet()) {
            result.put(entry.getKey(), JsonSourceInfo.from(entry.getValue()));
        }
        return result;
    }
    
    /**
     * Converts a Map<SourceInfo, TokenInfo> to Map<JsonSourceInfo, TokenInfo>
     */
    private static Map<JsonSourceInfo, TokenInfo> convertTokenMap(Map<org.evochora.compiler.api.SourceInfo, TokenInfo> tokenMap) {
        Map<JsonSourceInfo, TokenInfo> result = new HashMap<>();
        for (Map.Entry<org.evochora.compiler.api.SourceInfo, TokenInfo> entry : tokenMap.entrySet()) {
            result.put(JsonSourceInfo.from(entry.getKey()), entry.getValue());
        }
        return result;
    }

}
