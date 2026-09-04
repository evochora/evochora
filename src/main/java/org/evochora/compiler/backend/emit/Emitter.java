package org.evochora.compiler.backend.emit;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrLabelDef;
import org.evochora.compiler.model.ir.IrOperand;
import org.evochora.compiler.model.ir.IrProgram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Emitter is the final stage of the compiler backend. It walks the linked IR in the
 * order the layout placed it, writes the cells the {@link OperandEncoder} produces to the
 * coordinates the layout assigned, and assembles the {@link ProgramArtifact} from the machine
 * code, the layout's tables and what the emission contributors collected.
 */
public class Emitter {

    /**
     * Emits the final program artifact from the IR and layout information.
     *
     * <p>Before the items are encoded, all registered {@link IEmissionContributor}s are invoked
     * for each IR item. Contributors populate the {@link EmissionContext} with feature-specific
     * metadata (e.g., procedure parameter info), which the artifact carries.</p>
     *
     * @param program The linked IR program.
     * @param layout The layout result, containing coordinate and source mapping.
     * @param linkingContext The context from the linking phase, containing call site bindings.
     * @param isa The instruction set architecture for opcode and register resolution.
     * @param contributorRegistry Registry of emission contributors for extracting metadata from IR.
     * @param sources The text of every source file, keyed by file name; the artifact carries
     *                them line by line.
     * @param tokenMap Token classification per source position, copied into the artifact unchanged.
     * @param tokenLookup The same token classification indexed by file name, line and column,
     *                    copied into the artifact unchanged.
     * @return The final, compiled {@link ProgramArtifact}.
     * @throws CompilationException if an item cannot be encoded or has no cell in the layout.
     */
    public ProgramArtifact emit(IrProgram program,
                                LayoutResult layout,
                                LinkingContext linkingContext,
                                IInstructionSet isa,
                                EmissionContributorRegistry contributorRegistry,
                                Map<String, String> sources,
                                Map<SourceInfo, TokenInfo> tokenMap,
                                Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>> tokenLookup) throws CompilationException {
        EmissionContext emissionContext = new EmissionContext();
        List<IEmissionContributor> contributors = contributorRegistry.contributors();
        for (IrItem item : program.items()) {
            for (IEmissionContributor contributor : contributors) {
                contributor.onItem(item, emissionContext);
            }
        }

        OperandEncoder encoder = new OperandEncoder(isa);
        SourceLineIndex sourceLines = new SourceLineIndex(layout);
        Map<Integer, int[]> linearToCoord = layout.linearAddressToCoord();
        Map<int[], Integer> machineCodeLayout = new HashMap<>();

        int address = 0;
        for (IrItem item : program.items()) {
            switch (item) {
                case IrDirective directive -> {
                    // A directive occupies no cell; the contributors above have read it.
                }
                case IrLabelDef lbl -> {
                    // A label sits at the address the layout registered for it, not at the counter
                    Integer labelAddress = layout.labelToAddress().get(lbl.name());
                    if (labelAddress == null) {
                        throw new CompilationException(SourceInfo.locate(lbl.source(), "Label '" + lbl.name() + "' not found in layout"));
                    }
                    machineCodeLayout.put(coordinateOf(linearToCoord, labelAddress, lbl.source()),
                            encoder.encodeLabel(layout.labelToValue().get(lbl.name())));
                    address = labelAddress + 1;
                }
                case IrInstruction ins -> {
                    int opcodeAddress = address;
                    int[] opcodeCoord = coordinateOf(linearToCoord, address, ins.source());
                    machineCodeLayout.put(opcodeCoord, encoder.encodeOpcode(ins));
                    address++;
                    for (IrOperand op : ins.operands()) {
                        for (int cell : encoder.encodeOperand(op, ins.source())) {
                            machineCodeLayout.put(coordinateOf(linearToCoord, address, ins.source()), cell);
                            address++;
                        }
                    }
                    sourceLines.note(ins, opcodeAddress, opcodeCoord);
                }
            }
        }

        // Sort both machineCodeLayout and initialObjects by coordinate to ensure deterministic iteration.
        // HashMap<int[], V> has non-deterministic iteration order because int[] uses identity-based hashCode.
        Map<int[], Integer> sortedMachineCodeLayout = sortMapByCoordinate(machineCodeLayout);
        Map<int[], PlacedMolecule> sortedInitialObjects = sortMapByCoordinate(layout.initialWorldObjects());

        int contentHash = sortedMachineCodeLayout.entrySet().stream()
                .mapToInt(e -> Arrays.hashCode(e.getKey()) * 31 + e.getValue().hashCode())
                .sum();
        String programId = Integer.toHexString(contentHash);

        // Label values for the visualizer's view of fuzzy jumps
        Map<Integer, String> labelValueToName = new HashMap<>();
        Map<String, Integer> labelNameToValue = new HashMap<>();
        layout.labelToValue().forEach((name, value) -> {
            labelValueToName.put(value, name);
            labelNameToValue.put(name, value);
        });

        Map<String, List<String>> linesByFile = new HashMap<>();
        sources.forEach((path, text) -> linesByFile.put(path, Arrays.asList(text.split("\\r?\\n"))));

        return new ProgramArtifact(
                programId,
                linesByFile,
                sortedMachineCodeLayout,
                sortedInitialObjects,
                layout.sourceMap(),
                linkingContext.callSiteBindings(),
                layout.relativeCoordToLinearAddress(),
                linearToCoord,
                emissionContext.registerAliasMap(),
                emissionContext.procNameToParamNames(),
                tokenMap,
                tokenLookup,
                sourceLines.byLine(),
                labelValueToName,
                labelNameToValue
        );
    }

    /**
     * Looks up the coordinate the layout assigned to an address.
     *
     * @throws CompilationException if the layout has no cell for the address, which means the
     *         layout and the emitter disagree about how many cells an item occupies.
     */
    private static int[] coordinateOf(Map<Integer, int[]> linearToCoord, int address, SourceInfo src) throws CompilationException {
        int[] coord = linearToCoord.get(address);
        if (coord == null) {
            throw new CompilationException(SourceInfo.locate(src, "Missing coord for address " + address));
        }
        return coord;
    }

    /**
     * Sorts a map with int[] keys by coordinate values to ensure deterministic iteration.
     * Creates a new LinkedHashMap with entries sorted lexicographically by coordinate.
     *
     * @param <V> The value type of the map
     * @param unsortedMap The map to sort
     * @return A new LinkedHashMap with sorted entries
     */
    private <V> Map<int[], V> sortMapByCoordinate(Map<int[], V> unsortedMap) {
        List<Map.Entry<int[], V>> sortedEntries = new ArrayList<>(unsortedMap.entrySet());
        sortedEntries.sort((e1, e2) -> Arrays.compare(e1.getKey(), e2.getKey()));

        Map<int[], V> sortedMap = new LinkedHashMap<>();
        for (Map.Entry<int[], V> entry : sortedEntries) {
            sortedMap.put(entry.getKey(), entry.getValue());
        }
        return sortedMap;
    }
}
