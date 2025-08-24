package org.evochora.compiler.internal;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.SourceInfo;

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
 * int[] worldShape = {100, 100};
 * LinearizedProgramArtifact linearized = LinearizedProgramArtifact.from(original, worldShape);
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
        Map<Integer, Integer> machineCodeLayout,        // Linearisiert von int[]
        Map<Integer, PlacedMolecule> initialWorldObjects, // Linearisiert von int[]
        Map<Integer, SourceInfo> sourceMap,             // Unverändert
        Map<Integer, int[]> callSiteBindings,           // Unverändert
        Map<String, Integer> relativeCoordToLinearAddress, // Unverändert
        Map<Integer, int[]> linearAddressToCoord,       // Unverändert
        Map<Integer, String> labelAddressToName,        // Unverändert
        Map<String, Integer> registerAliasMap,          // Unverändert
        Map<String, List<String>> procNameToParamNames, // Unverändert
        int[] worldShape
) {
    
    public LinearizedProgramArtifact {
        sources = sources != null ? Collections.unmodifiableMap(sources) : Collections.emptyMap();
        machineCodeLayout = Collections.unmodifiableMap(machineCodeLayout);
        initialWorldObjects = Collections.unmodifiableMap(initialWorldObjects);
        sourceMap = Collections.unmodifiableMap(sourceMap);
        callSiteBindings = Collections.unmodifiableMap(callSiteBindings);
        relativeCoordToLinearAddress = Collections.unmodifiableMap(relativeCoordToLinearAddress);
        linearAddressToCoord = Collections.unmodifiableMap(linearAddressToCoord);
        labelAddressToName = Collections.unmodifiableMap(labelAddressToName);
        registerAliasMap = Collections.unmodifiableMap(registerAliasMap);
        procNameToParamNames = procNameToParamNames != null ? Collections.unmodifiableMap(procNameToParamNames) : Collections.emptyMap();
        worldShape = worldShape != null ? worldShape.clone() : new int[0];
    }
    
    /**
     * Konvertiert ein ProgramArtifact zu einem LinearizedProgramArtifact.
     */
    public static LinearizedProgramArtifact from(ProgramArtifact artifact, int[] worldShape) {
        CoordinateConverter converter = new CoordinateConverter(worldShape);
        
        return new LinearizedProgramArtifact(
                artifact.programId(),
                artifact.sources(),
                converter.linearizeMap(artifact.machineCodeLayout()),
                converter.linearizeMap(artifact.initialWorldObjects()),
                artifact.sourceMap(),
                artifact.callSiteBindings(), // Map<Integer, int[]> - unverändert
                artifact.relativeCoordToLinearAddress(), // Map<String, Integer> - unverändert
                artifact.linearAddressToCoord(), // Map<Integer, int[]> - unverändert
                artifact.labelAddressToName(), // Map<Integer, String> - unverändert
                artifact.registerAliasMap(), // Map<String, Integer> - unverändert
                artifact.procNameToParamNames(),
                worldShape
        );
    }
    
    /**
     * Konvertiert ein LinearizedProgramArtifact zurück zu einem ProgramArtifact.
     */
    public ProgramArtifact toProgramArtifact() {
        CoordinateConverter converter = new CoordinateConverter(worldShape);
        
        return new ProgramArtifact(
                programId,
                sources,
                converter.delinearizeMap(machineCodeLayout()),
                converter.delinearizeMap(initialWorldObjects()),
                sourceMap,
                callSiteBindings(), // Map<Integer, int[]> - unverändert
                relativeCoordToLinearAddress(), // Map<String, Integer> - unverändert
                linearAddressToCoord(), // Map<Integer, int[]> - unverändert
                labelAddressToName(), // Map<Integer, String> - unverändert
                registerAliasMap(), // Map<String, Integer> - unverändert
                procNameToParamNames
        );
    }
}
