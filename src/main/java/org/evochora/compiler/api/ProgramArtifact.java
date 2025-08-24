package org.evochora.compiler.api;

import org.evochora.compiler.internal.LinearizedProgramArtifact;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record ProgramArtifact(
        String programId,
        Map<String, List<String>> sources,
        Map<int[], Integer> machineCodeLayout,
        Map<int[], PlacedMolecule> initialWorldObjects,
        Map<Integer, SourceInfo> sourceMap,
        Map<Integer, int[]> callSiteBindings,
        Map<String, Integer> relativeCoordToLinearAddress,
        Map<Integer, int[]> linearAddressToCoord,
        Map<Integer, String> labelAddressToName,
        Map<String, Integer> registerAliasMap,
        Map<String, List<String>> procNameToParamNames
) {
    public ProgramArtifact {
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
    }
    
    /**
     * Converts this ProgramArtifact to a LinearizedProgramArtifact
     * for Jackson serialization.
     * 
     * <p>This method linearizes all maps with int[] keys to Integer keys
     * to enable Jackson serialization. The original data remains unchanged.</p>
     * 
     * <h3>Usage</h3>
     * <pre>{@code
     * ProgramArtifact artifact = ...;
     * int[] worldShape = {100, 100};
     * LinearizedProgramArtifact linearized = artifact.toLinearized(worldShape);
     * 
     * // Jackson serialization
     * String json = objectMapper.writeValueAsString(linearized);
     * }</pre>
     * 
     * <h3>Linearized Fields</h3>
     * <ul>
     *   <li><strong>machineCodeLayout</strong>: Map<int[], Integer> → Map<Integer, Integer></li>
     *   <li><strong>initialWorldObjects</strong>: Map<int[], PlacedMolecule> → Map<Integer, PlacedMolecule></li>
     * </ul>
     * 
     * @param worldShape The world shape for coordinate linearization
     * @return A LinearizedProgramArtifact with Integer keys for Jackson serialization
     * @throws IllegalArgumentException if worldShape is null or empty
     * @see LinearizedProgramArtifact
     * @see CoordinateConverter
     */
    public LinearizedProgramArtifact toLinearized(int[] worldShape) {
        return LinearizedProgramArtifact.from(this, worldShape);
    }
    
    /**
     * Converts a LinearizedProgramArtifact back to a ProgramArtifact.
     * 
     * <p>This method restores the original structure with int[] keys
     * after a LinearizedProgramArtifact has been deserialized.</p>
     * 
     * <h3>Usage</h3>
     * <pre>{@code
     * // After Jackson deserialization
     * LinearizedProgramArtifact linearized = objectMapper.readValue(json, LinearizedProgramArtifact.class);
     * 
     * // Back to ProgramArtifact
     * ProgramArtifact artifact = ProgramArtifact.fromLinearized(linearized);
     * }</pre>
     * 
     * @param linearized The LinearizedProgramArtifact to convert
     * @return A ProgramArtifact with the original structure
     * @throws IllegalArgumentException if linearized is null
     * @see LinearizedProgramArtifact#toProgramArtifact()
     */
    public static ProgramArtifact fromLinearized(LinearizedProgramArtifact linearized) {
        return linearized.toProgramArtifact();
    }
}
