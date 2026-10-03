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
 * @param sources One entry per inclusion of a file: the main file first, then every module
 *                placement and every text inclusion in the order the compiler meets it, each with
 *                the regions and notes the preprocessor recorded for it.
 * @param expansionHomes For every instance of injected tokens that is no inclusion of its own, such
 *                       as a macro expansion, the instance of the entry whose lines its tokens stand
 *                       on; that entry has the instance's placement and file.
 * @param machineCodeLayout A map from relative coordinates to the integer representation of a molecule.
 * @param initialWorldObjects A map from relative coordinates to molecules that should be placed in the world initially.
 * @param sourceMap A map from linear address to source information, for debugging; the position
 *                  names the instance of injected tokens the instruction was compiled in.
 * @param callSiteBindings A map from the address recorded for a CALL instruction to that call's
 *                         parameter bindings: each formal register id (FDR/FLR bank) to the
 *                         caller register bound to it.
 * @param relativeCoordToLinearAddress A map from relative coordinate string to linear address.
 * @param linearAddressToCoord A map from linear address to relative coordinates.
 * @param registerAliasMap A map from register alias names (e.g., "%MY_REG") to their physical register index.
 * @param procNameToParamNames A map from procedure names to a list of their parameter information (name and type).
 * @param tokenMap A map from SourceInfo to TokenInfo for deterministic token classification. Its
 *                 keys carry instance 0: a token is classified by its position alone, the same in
 *                 every instance of injected tokens, because the debugger annotates a line at
 *                 runtime by the positions of its tokens.
 * @param tokenLookup A map from placement to fileName to lineNumber to columnNumber to {@code List<TokenInfo>} for efficient
 *                    placement-file-line-column-based lookup, by position alone as the token map.
 * @param sourceLineToInstructions A map from placement to fileName to entry instance to lineNumber to the machine
 *                                 instructions that were generated from that source line in that entry, sorted by
 *                                 linear address. The placement, file and instance name an entry of {@code sources},
 *                                 one inclusion of a file; an instruction of an instance that is no inclusion stands
 *                                 under the instance {@code expansionHomes} names for it.
 * @param labelValueToName A map from label hash value to label name (for reverse lookup in visualizer).
 * @param labelNameToValue A map from label name to label hash value (for forward lookup).
 */
public record ProgramArtifact(
        String programId,
        List<SourceFile> sources,
        Map<Integer, Integer> expansionHomes,
        Map<int[], Integer> machineCodeLayout,
        Map<int[], PlacedMolecule> initialWorldObjects,
        Map<Integer, SourceInfo> sourceMap,
        Map<Integer, Map<Integer, Integer>> callSiteBindings,
        Map<String, Integer> relativeCoordToLinearAddress,
        Map<Integer, int[]> linearAddressToCoord,
        Map<String, Integer> registerAliasMap,
        Map<String, List<ParamInfo>> procNameToParamNames,
        Map<SourceInfo, TokenInfo> tokenMap,
        Map<String, Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>>> tokenLookup,
        Map<String, Map<String, Map<Integer, Map<Integer, List<MachineInstructionInfo>>>>> sourceLineToInstructions,
        Map<Integer, String> labelValueToName,
        Map<String, Integer> labelNameToValue
) {
    /**
     * Canonical constructor that makes the artifact immutable by wrapping every map component and
     * the list of sources in an unmodifiable view. {@code sources}, {@code expansionHomes},
     * {@code procNameToParamNames},
     * {@code tokenMap}, {@code tokenLookup}, {@code sourceLineToInstructions},
     * {@code labelValueToName} and {@code labelNameToValue} accept null and become empty; every
     * other component must be non-null. Wrapping does not copy, so a caller that keeps a reference
     * to a collection it passed in can still change what the artifact exposes.
     *
     * @throws NullPointerException if one of the map components without null handling is null.
     */
    public ProgramArtifact {
        sources = sources != null ? Collections.unmodifiableList(sources) : Collections.emptyList();
        expansionHomes = expansionHomes != null ? Collections.unmodifiableMap(expansionHomes) : Collections.emptyMap();
        machineCodeLayout = Collections.unmodifiableMap(machineCodeLayout);
        initialWorldObjects = Collections.unmodifiableMap(initialWorldObjects);
        sourceMap = Collections.unmodifiableMap(sourceMap);
        callSiteBindings = Collections.unmodifiableMap(callSiteBindings);
        relativeCoordToLinearAddress = Collections.unmodifiableMap(relativeCoordToLinearAddress);
        linearAddressToCoord = Collections.unmodifiableMap(linearAddressToCoord);
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
     * <p><b>Usage:</b></p>
     * <pre>{@code
     * ProgramArtifact artifact = ...;
     * EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
     * LinearizedProgramArtifact linearized = artifact.toLinearized(envProps);
     * 
     * // Jackson serialization
     * String json = objectMapper.writeValueAsString(linearized);
     * }</pre>
     * 
     * <p><b>Linearized Fields:</b></p>
     * <ul>
     *   <li><strong>machineCodeLayout</strong>: {@code Map<int[], Integer>} &rarr; {@code Map<Integer, Integer>}</li>
     *   <li><strong>initialWorldObjects</strong>: {@code Map<int[], PlacedMolecule>} &rarr; {@code Map<Integer, PlacedMolecule>}</li>
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

}
