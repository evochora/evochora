package org.evochora.compiler.api;

import org.evochora.compiler.internal.CoordinateConverter;
import org.evochora.compiler.internal.LinearizedProgramArtifact;
import org.evochora.runtime.model.EnvironmentProperties;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents the complete, self-contained output of the compilation process.
 * This is an immutable data carrier that includes the compiled machine code,
 * source mapping, and various metadata required by the runtime and debugger.
 *
 * @param programId A unique identifier for the compiled program.
 * @param sources A map of source file names to their content.
 * @param machineCodeLayout A map from relative coordinates to the integer representation of a molecule.
 * @param initialWorldObjects A map from relative coordinates to molecules that should be placed in the world initially.
 * @param sourceMap A map from linear address to source information, for debugging.
 * @param callSiteBindings A map from linear address of a CALL instruction to its target coordinates.
 * @param relativeCoordToLinearAddress A map from relative coordinate string to linear address.
 * @param linearAddressToCoord A map from linear address to relative coordinates.
 * @param labelAddressToName A map from linear address of a label to its name.
 * @param registerAliasMap A map from register alias names (e.g., "%MY_REG") to their physical register index.
 * @param procNameToParamNames A map from procedure names to a list of their parameter information (name and type).
 * @param tokenMap A map from SourceInfo to TokenInfo for deterministic token classification.
 * @param tokenLookup A map from fileName to lineNumber to columnNumber to List<TokenInfo> for efficient file-line-column-based lookup.
 * @param sourceLineToInstructions A map from source line identifier (fileName:lineNumber) to a list of machine instructions
 *                                 that were generated from that source line, sorted by linear address.
 * @param labelValueToName A map from label hash value to label name (for reverse lookup in visualizer).
 * @param labelNameToValue A map from label name to label hash value (for forward lookup).
 */
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
        Map<String, List<ParamInfo>> procNameToParamNames,
        Map<SourceInfo, TokenInfo> tokenMap,
        Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>> tokenLookup,
        Map<String, List<MachineInstructionInfo>> sourceLineToInstructions,
        Map<Integer, String> labelValueToName,
        Map<String, Integer> labelNameToValue
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
        procNameToParamNames = procNameToParamNames != null 
                ? Collections.unmodifiableMap(procNameToParamNames) 
                : Collections.emptyMap();
        tokenMap = tokenMap != null ? Collections.unmodifiableMap(tokenMap) : Collections.emptyMap();
        tokenLookup = tokenLookup != null ? Collections.unmodifiableMap(tokenLookup) : Collections.emptyMap();
        sourceLineToInstructions = sourceLineToInstructions != null
                ? Collections.unmodifiableMap(sourceLineToInstructions)
                : Collections.emptyMap();
        labelValueToName = labelValueToName != null
                ? Collections.unmodifiableMap(labelValueToName)
                : Collections.emptyMap();
        labelNameToValue = labelNameToValue != null
                ? Collections.unmodifiableMap(labelNameToValue)
                : Collections.emptyMap();
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
     * EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
     * LinearizedProgramArtifact linearized = artifact.toLinearized(envProps);
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
     * @param envProps The environment properties containing world shape and toroidal information
     * @return A LinearizedProgramArtifact with Integer keys for Jackson serialization
     * @throws IllegalArgumentException if envProps is null
     * @see LinearizedProgramArtifact
     * @see CoordinateConverter
     */
    public LinearizedProgramArtifact toLinearized(EnvironmentProperties envProps) {
        return LinearizedProgramArtifact.from(this, envProps);
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
