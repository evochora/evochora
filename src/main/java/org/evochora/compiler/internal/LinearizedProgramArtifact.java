package org.evochora.compiler.internal;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.runtime.model.EnvironmentProperties;

import java.util.Collections;
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
        Map<Integer, SourceInfo> sourceMap,
        Map<Integer, int[]> callSiteBindings,
        Map<String, Integer> relativeCoordToLinearAddress,
        Map<Integer, int[]> linearAddressToCoord,
        Map<Integer, String> labelAddressToName,
        Map<String, Integer> registerAliasMap,
        Map<String, List<String>> procNameToParamNames,
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
        envProps = envProps != null ? envProps : new EnvironmentProperties(new int[0], false);
    }
    
    /**
     * Converts a ProgramArtifact to a LinearizedProgramArtifact.
     * @param artifact The ProgramArtifact to convert.
     * @param worldShape The shape of the world.
     * @return A new LinearizedProgramArtifact.
     */
    public static LinearizedProgramArtifact from(ProgramArtifact artifact, EnvironmentProperties envProps) {
        CoordinateConverter converter = new CoordinateConverter(envProps);
        
        return new LinearizedProgramArtifact(
                artifact.programId(),
                artifact.sources(),
                converter.linearizeMap(artifact.machineCodeLayout()),
                converter.linearizeMap(artifact.initialWorldObjects()),
                artifact.sourceMap(),
                artifact.callSiteBindings(),
                artifact.relativeCoordToLinearAddress(),
                artifact.linearAddressToCoord(),
                artifact.labelAddressToName(),
                artifact.registerAliasMap(),
                artifact.procNameToParamNames(),
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
                sourceMap,
                callSiteBindings(),
                relativeCoordToLinearAddress(),
                linearAddressToCoord(),
                labelAddressToName(),
                registerAliasMap(),
                procNameToParamNames
        );
    }
}
