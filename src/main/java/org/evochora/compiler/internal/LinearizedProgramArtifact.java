package org.evochora.compiler.internal;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.runtime.model.EnvironmentProperties;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Jackson-compatible version of ProgramArtifact with Integer keys for maps
 * that originally had int[] keys.
 * 
 * <h3>Purpose</h3>
 * This class solves the Jackson serialization problem with int[] map keys
 * by converting them to linearized Integer keys.
 * 
 * <h3>Usage</h3>
 * <pre>{@code
 * // Convert ProgramArtifact to LinearizedProgramArtifact
 * ProgramArtifact original = ...;
 * EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
 * LinearizedProgramArtifact linearized = LinearizedProgramArtifact.from(original, envProps);
 * 
 * // Jackson serialization
 * String json = objectMapper.writeValueAsString(linearized);
 * 
 * // Jackson deserialization
 * LinearizedProgramArtifact restored = objectMapper.readValue(json, LinearizedProgramArtifact.class);
 * 
 * // Back to ProgramArtifact
 * ProgramArtifact artifact = restored.toProgramArtifact();
 * }</pre>
 * 
 * <h3>Linearized Fields</h3>
 * Only the following fields are linearized (int[] → Integer):
 * <ul>
 *   <li><strong>machineCodeLayout</strong>: Map<int[], Integer> → Map<Integer, Integer></li>
 *   <li><strong>initialWorldObjects</strong>: Map<int[], PlacedMolecule> → Map<Integer, PlacedMolecule></li>
 * </ul>
 * 
 * <h3>Unchanged Fields</h3>
 * All other fields remain unchanged:
 * <ul>
 *   <li><strong>sourceMap</strong>: Map<Integer, SourceInfo> (unchanged)</li>
 *   <li><strong>callSiteBindings</strong>: Map<Integer, int[]> (unchanged)</li>
 *   <li><strong>relativeCoordToLinearAddress</strong>: Map<String, Integer> (unchanged)</li>
 *   <li><strong>linearAddressToCoord</strong>: Map<Integer, int[]> (unchanged)</li>
 *   <li><strong>labelAddressToName</strong>: Map<Integer, String> (unchanged)</li>
 *   <li><strong>registerAliasMap</strong>: Map<String, Integer> (unchanged)</li>
 *   <li><strong>procNameToParamNames</strong>: Map<String, List<String>> (unchanged)</li>
 *   <li><strong>tokenMap</strong>: Map<SerializableSourceInfo, TokenInfo> (unchanged)</li>
 *   <li><strong>tokenLookup</strong>: Map<String, Map<Integer, Map<Integer, List<TokenInfo>>> (unchanged)</li>
 * </ul>
 * 
 * <h3>Performance</h3>
 * Conversion only occurs when needed:
 * <ul>
 *   <li><strong>Serialization</strong>: ProgramArtifact → LinearizedProgramArtifact</li>
 *   <li><strong>Deserialization</strong>: LinearizedProgramArtifact → ProgramArtifact</li>
 *   <li><strong>Runtime</strong>: No conversion required</li>
 * </ul>
 * 
 * @see CoordinateConverter
 * @see ProgramArtifact
 * @since 1.0
 */
public record LinearizedProgramArtifact(
        String programId,
        Map<String, List<String>> sources,
        Map<Integer, Integer> machineCodeLayout,
        Map<Integer, PlacedMolecule> initialWorldObjects,
        Map<Integer, SerializableSourceInfo> sourceMap,
        Map<Integer, int[]> callSiteBindings,
        Map<String, Integer> relativeCoordToLinearAddress,
        Map<Integer, int[]> linearAddressToCoord,
        Map<Integer, String> labelAddressToName,
        Map<String, Integer> registerAliasMap,
        Map<String, List<String>> procNameToParamNames,
        Map<SerializableSourceInfo, TokenInfo> tokenMap,
        Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>> tokenLookup,
        EnvironmentProperties envProps
) {
    
    public LinearizedProgramArtifact {
        sources = sources != null ? Collections.unmodifiableMap(sources) : Collections.emptyMap();
        machineCodeLayout = machineCodeLayout != null ? Collections.unmodifiableMap(machineCodeLayout) : Collections.emptyMap();
        initialWorldObjects = initialWorldObjects != null ? Collections.unmodifiableMap(initialWorldObjects) : Collections.emptyMap();
        sourceMap = sourceMap != null ? Collections.unmodifiableMap(sourceMap) : Collections.emptyMap();
        callSiteBindings = callSiteBindings != null ? Collections.unmodifiableMap(callSiteBindings) : Collections.emptyMap();
        relativeCoordToLinearAddress = relativeCoordToLinearAddress != null ? Collections.unmodifiableMap(relativeCoordToLinearAddress) : Collections.emptyMap();
        linearAddressToCoord = linearAddressToCoord != null ? Collections.unmodifiableMap(linearAddressToCoord) : Collections.emptyMap();
        labelAddressToName = labelAddressToName != null ? Collections.unmodifiableMap(labelAddressToName) : Collections.emptyMap();
        registerAliasMap = registerAliasMap != null ? Collections.unmodifiableMap(registerAliasMap) : Collections.emptyMap();
        procNameToParamNames = procNameToParamNames != null ? Collections.unmodifiableMap(procNameToParamNames) : Collections.emptyMap();
        tokenMap = tokenMap != null ? Collections.unmodifiableMap(tokenMap) : Collections.emptyMap();
        tokenLookup = tokenLookup != null ? Collections.unmodifiableMap(tokenLookup) : Collections.emptyMap();
        envProps = envProps != null ? envProps : new EnvironmentProperties(new int[0], false);
    }
    
    /**
     * Converts a ProgramArtifact to a LinearizedProgramArtifact.
     * @param artifact The ProgramArtifact to convert.
     * @param envProps The environment properties containing world shape and toroidal information.
     * @return A new LinearizedProgramArtifact.
     */
    public static LinearizedProgramArtifact from(ProgramArtifact artifact, EnvironmentProperties envProps) {
        CoordinateConverter converter = new CoordinateConverter(envProps);
        
        return new LinearizedProgramArtifact(
                artifact.programId(),
                artifact.sources(),
                converter.linearizeMap(artifact.machineCodeLayout()),
                converter.linearizeMap(artifact.initialWorldObjects()),
                convertSourceMap(artifact.sourceMap()),
                artifact.callSiteBindings(),
                artifact.relativeCoordToLinearAddress(),
                artifact.linearAddressToCoord(),
                artifact.labelAddressToName(),
                artifact.registerAliasMap(),
                artifact.procNameToParamNames(),
                convertTokenMap(artifact.tokenMap()),
                artifact.tokenLookup(),
                envProps
        );
    }
    
    /**
     * Converts this LinearizedProgramArtifact back to a ProgramArtifact.
     * @return A new ProgramArtifact.
     */
    public ProgramArtifact toProgramArtifact() {
        CoordinateConverter converter = new CoordinateConverter(envProps);
        
        return new ProgramArtifact(
                programId,
                sources,
                converter.delinearizeMap(machineCodeLayout()),
                converter.delinearizeMap(initialWorldObjects()),
                convertSourceMapBack(sourceMap),
                callSiteBindings(),
                relativeCoordToLinearAddress(),
                linearAddressToCoord(),
                labelAddressToName(),
                registerAliasMap(),
                procNameToParamNames,
                convertTokenMapBack(tokenMap),
                tokenLookup
        );
    }
    
    /**
     * Converts a Map<Integer, SourceInfo> to Map<Integer, SerializableSourceInfo>
     */
    private static Map<Integer, SerializableSourceInfo> convertSourceMap(Map<Integer, org.evochora.compiler.api.SourceInfo> sourceMap) {
        Map<Integer, SerializableSourceInfo> result = new HashMap<>();
        for (Map.Entry<Integer, org.evochora.compiler.api.SourceInfo> entry : sourceMap.entrySet()) {
            result.put(entry.getKey(), SerializableSourceInfo.from(entry.getValue()));
        }
        return result;
    }
    
    /**
     * Converts a Map<Integer, SerializableSourceInfo> back to Map<Integer, SourceInfo>
     */
    private static Map<Integer, org.evochora.compiler.api.SourceInfo> convertSourceMapBack(Map<Integer, SerializableSourceInfo> sourceMap) {
        Map<Integer, org.evochora.compiler.api.SourceInfo> result = new HashMap<>();
        for (Map.Entry<Integer, SerializableSourceInfo> entry : sourceMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toSourceInfo());
        }
        return result;
    }
    
    /**
     * Converts a Map<SourceInfo, TokenInfo> to Map<SerializableSourceInfo, TokenInfo>
     */
    private static Map<SerializableSourceInfo, TokenInfo> convertTokenMap(Map<org.evochora.compiler.api.SourceInfo, TokenInfo> tokenMap) {
        Map<SerializableSourceInfo, TokenInfo> result = new HashMap<>();
        for (Map.Entry<org.evochora.compiler.api.SourceInfo, TokenInfo> entry : tokenMap.entrySet()) {
            result.put(SerializableSourceInfo.from(entry.getKey()), entry.getValue());
        }
        return result;
    }
    
    /**
     * Converts a Map<SerializableSourceInfo, TokenInfo> back to Map<SourceInfo, TokenInfo>
     */
    private static Map<org.evochora.compiler.api.SourceInfo, TokenInfo> convertTokenMapBack(Map<SerializableSourceInfo, TokenInfo> tokenMap) {
        Map<org.evochora.compiler.api.SourceInfo, TokenInfo> result = new HashMap<>();
        for (Map.Entry<SerializableSourceInfo, TokenInfo> entry : tokenMap.entrySet()) {
            result.put(entry.getKey().toSourceInfo(), entry.getValue());
        }
        return result;
    }
}
