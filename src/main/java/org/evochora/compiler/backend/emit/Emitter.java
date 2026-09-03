package org.evochora.compiler.backend.emit;

import org.evochora.runtime.Config;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.MachineInstructionInfo;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.ir.*;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MoleculeTypeRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The Emitter is the final stage of the compiler backend. It takes the linked
 * Intermediate Representation (IR) and the layout information to produce the
 * final, self-contained {@link ProgramArtifact}. This includes generating the
 * machine code, source maps, and other metadata needed by the runtime.
 */
public class Emitter {

    /**
     * Emits the final program artifact from the IR and layout information.
     *
     * <p>During the item loop, all registered {@link IEmissionContributor}s are invoked
     * for each IR item. Contributors populate the {@link EmissionContext} with feature-specific
     * metadata (e.g., procedure parameter info). The Emitter reads the accumulated metadata
     * when building the final {@link ProgramArtifact}.</p>
     *
     * @param program The linked IR program.
     * @param layout The layout result, containing coordinate and source mapping.
     * @param linkingContext The context from the linking phase, containing call site bindings.
     * @param isa The instruction set architecture for opcode and register resolution.
     * @param contributorRegistry Registry of emission contributors for extracting metadata from IR.
     * @param sources A map of source file names to their content.
     * @param tokenMap Token classification per source position, copied into the artifact unchanged.
     * @param tokenLookup The same token classification indexed by file name, line and column,
     *                    copied into the artifact unchanged.
     * @return The final, compiled {@link ProgramArtifact}.
     * @throws CompilationException if an error occurs during emission.
     */
    public ProgramArtifact emit(IrProgram program,
                                LayoutResult layout,
                                LinkingContext linkingContext,
                                IInstructionSet isa,
                                EmissionContributorRegistry contributorRegistry,
                                Map<String, List<String>> sources,
                                Map<SourceInfo, TokenInfo> tokenMap,
                                Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>> tokenLookup) throws CompilationException {
        Map<int[], Integer> machineCodeLayout = new HashMap<>();
        Map<Integer, int[]> linearToCoord = layout.linearAddressToCoord();
        Map<String, Integer> coordToLinear = layout.relativeCoordToLinearAddress();
        Map<Integer, SourceInfo> sourceMap = layout.sourceMap();
        Map<int[], PlacedMolecule> initialObjects = layout.initialWorldObjects();

        // Map to collect machine instructions per source line for frontend visualization
        // Key: "fileName:lineNumber", Value: List of machine instructions (sorted by linear address)
        Map<String, List<MachineInstructionInfo>> sourceLineToInstructions = new HashMap<>();

        // Invoke emission contributors to extract feature-specific metadata from IR
        EmissionContext emissionContext = new EmissionContext();
        List<IEmissionContributor> contributors = contributorRegistry.contributors();
        for (IrItem item : program.items()) {
            for (IEmissionContributor contributor : contributors) {
                contributor.onItem(item, emissionContext);
            }
        }

        int address = 0;
        for (IrItem item : program.items()) {
            switch (item) {
                case IrDirective directive -> {
                    // A directive occupies no cell; the contributors above have read it.
                }
                case IrLabelDef lbl -> {
                    // Use the label's registered linear address from the layout, not the sequential counter
                    Integer labelLinearAddr = layout.labelToAddress().get(lbl.name());
                    if (labelLinearAddr == null) {
                        throw new CompilationException(formatSource(lbl.source(), "Label '" + lbl.name() + "' not found in layout"));
                    }
                    int[] labelCoord = linearToCoord.get(labelLinearAddr);
                    if (labelCoord == null) {
                        throw new CompilationException(formatSource(lbl.source(), "Missing coord for label address " + labelLinearAddr));
                    }
                    machineCodeLayout.put(labelCoord, new Molecule(Config.TYPE_LABEL, lbl.value()).toInt());
                    address = labelLinearAddr + 1; // Sync the counter for subsequent items
                }
                case IrInstruction ins -> {
                    // Track the opcode address (where this instruction's opcode is located)
                    int opcodeAddress = address;

                    int opcode = isa.getInstructionIdByName(ins.opcode()).orElseThrow(() ->
                            new RuntimeException(formatSource(ins.source(), "Unknown opcode: " + ins.opcode())));
                    int[] opcodeCoord = linearToCoord.get(address);
                    if (opcodeCoord == null) throw new CompilationException(formatSource(ins.source(), "Missing coord for address " + address));
                    machineCodeLayout.put(opcodeCoord, opcode);
                    address++;

                    // Format operands as string for display
                    String operandsAsString = formatOperandsAsString(ins, layout, opcodeCoord, isa, ins.source());

                    // Create MachineInstructionInfo and add to sourceLineToInstructions map
                    SourceInfo src = ins.source();
                    if (src != null) {
                        String sourceLineKey = createSourceLineKey(src);
                        MachineInstructionInfo machineInfo = new MachineInstructionInfo(
                                opcodeAddress,
                                ins.opcode(),
                                operandsAsString,
                                ins.synthetic()
                        );
                        sourceLineToInstructions.computeIfAbsent(sourceLineKey, k -> new ArrayList<>()).add(machineInfo);
                    }

                    for (IrOperand op : ins.operands()) {
                        for (int cell : encodeOperand(op, isa, ins.source())) {
                            int[] coord = linearToCoord.get(address);
                            if (coord == null) throw new CompilationException(formatSource(ins.source(), "Missing coord for address " + address));
                            machineCodeLayout.put(coord, cell);
                            address++;
                        }
                    }
                }
            }
        }

        // Sort machine instructions within each source line by linear address
        Map<String, List<MachineInstructionInfo>> sortedSourceLineToInstructions = new LinkedHashMap<>();
        for (Map.Entry<String, List<MachineInstructionInfo>> entry : sourceLineToInstructions.entrySet()) {
            List<MachineInstructionInfo> sorted = entry.getValue().stream()
                    .sorted((a, b) -> Integer.compare(a.linearAddress(), b.linearAddress()))
                    .collect(Collectors.toList());
            sortedSourceLineToInstructions.put(entry.getKey(), sorted);
        }

        // Sort both machineCodeLayout and initialObjects by coordinate to ensure deterministic iteration.
        // HashMap<int[], V> has non-deterministic iteration order because int[] uses identity-based hashCode.
        // Sorting here at compile time ensures any consumer gets consistent, deterministic iteration order.
        Map<int[], Integer> sortedMachineCodeLayout = sortMapByCoordinate(machineCodeLayout);
        Map<int[], PlacedMolecule> sortedInitialObjects = sortMapByCoordinate(initialObjects);

        int contentHash = sortedMachineCodeLayout.entrySet().stream()
                .mapToInt(e -> java.util.Arrays.hashCode(e.getKey()) * 31 + e.getValue().hashCode())
                .sum();
        String programId = Integer.toHexString(contentHash);

        // Build label hash maps for fuzzy jump matching visualization
        Map<Integer, String> labelValueToName = new HashMap<>();
        Map<String, Integer> labelNameToValue = new HashMap<>();
        for (Map.Entry<String, Integer> entry : layout.labelToAddress().entrySet()) {
            String name = entry.getKey();
            int value = IrLabelDef.valueOf(name);
            labelValueToName.put(value, name);
            labelNameToValue.put(name, value);
        }

        return new ProgramArtifact(
                programId,
                sources,
                sortedMachineCodeLayout,
                sortedInitialObjects,
                sourceMap,
                linkingContext.callSiteBindings(),
                coordToLinear,
                linearToCoord,
                emissionContext.registerAliasMap(),
                emissionContext.procNameToParamNames(),
                tokenMap,
                tokenLookup,
                sortedSourceLineToInstructions,
                labelValueToName,
                labelNameToValue
        );
    }

    /**
     * Formats operands of an instruction as a string for display in the frontend.
     * 
     * Note: For CALL instructions, REF/VAL operands (on {@code IrCallInstruction}) are emitted
     * as separate PUSH/POP instructions by the marshalling rules, so only the main operands
     * (procedure address/label) are formatted here.
     * 
     * @param ins The IR instruction.
     * @param layout The layout result for resolving label addresses.
     * @param opcodeCoord The coordinates of the opcode.
     * @param isa The instruction set for resolving register names.
     * @param ctx The source information for error reporting.
     * @return A formatted string representation of the operands (space-separated).
     */
    private String formatOperandsAsString(IrInstruction ins, LayoutResult layout, int[] opcodeCoord, IInstructionSet isa, SourceInfo ctx) {
        List<String> operandStrings = new ArrayList<>();
        
        // Format main operands (e.g., procedure address for CALL, register for PUSH/POP)
        List<IrOperand> mainOperands = ins.operands();
        if (mainOperands != null) {
            for (IrOperand op : mainOperands) {
                operandStrings.add(formatOperandAsString(op, layout, opcodeCoord, isa, ctx));
            }
        }
        
        return String.join(" ", operandStrings);
    }

    /**
     * Formats a single operand as a string for display.
     * 
     * @param op The IR operand to format.
     * @param layout The layout result for resolving label addresses.
     * @param opcodeCoord The coordinates of the opcode (for calculating label deltas).
     * @param isa The instruction set for resolving register names.
     * @param ctx The source information for error reporting.
     * @return A formatted string representation of the operand.
     */
    private String formatOperandAsString(IrOperand op, LayoutResult layout, int[] opcodeCoord, IInstructionSet isa, SourceInfo ctx) {
        return switch (op) {
            // The register name as-is (e.g., "%DR0")
            case IrReg r -> r.name();
            case IrImm imm -> String.valueOf(imm.value());
            // "TYPE:VALUE" (e.g., "DATA:3")
            case IrTypedImm ti -> ti.typeName() + ":" + ti.value();
            // Vector components joined with "|" (e.g., "10|20")
            case IrVec vec -> Arrays.stream(vec.components())
                    .mapToObj(String::valueOf)
                    .collect(Collectors.joining("|"));
            case IrLabelRef ref -> formatLabelDelta(ref, layout, opcodeCoord);
        };
    }

    /**
     * Formats a label reference as the delta from the opcode to the label, "x|y|...", or as
     * the label name if the layout does not know the label.
     */
    private String formatLabelDelta(IrLabelRef ref, LayoutResult layout, int[] opcodeCoord) {
        Integer targetAddr = layout.labelToAddress().get(ref.labelName());
        if (targetAddr != null) {
            int[] targetCoord = layout.linearAddressToCoord().get(targetAddr);
            if (targetCoord != null && opcodeCoord != null) {
                int dims = Math.max(opcodeCoord.length, targetCoord.length);
                int[] delta = new int[dims];
                for (int d = 0; d < dims; d++) {
                    int s = d < opcodeCoord.length ? opcodeCoord[d] : 0;
                    int t = d < targetCoord.length ? targetCoord[d] : 0;
                    delta[d] = t - s;
                }
                return Arrays.stream(delta)
                        .mapToObj(String::valueOf)
                        .collect(Collectors.joining("|"));
            }
        }
        return ref.labelName();
    }

    /**
     * Creates a string key from SourceInfo for efficient lookup in the frontend.
     * Format: "fileName:lineNumber"
     * 
     * @param src The source information.
     * @return A string key for the source line.
     */
    private String createSourceLineKey(SourceInfo src) {
        if (src == null) {
            return "<unknown>:-1";
        }
        String fileName = src.fileName() != null ? src.fileName() : "<unknown>";
        int lineNumber = src.lineNumber();
        return fileName + ":" + lineNumber;
    }

    /**
     * Encodes an IR operand into the cells it occupies in the machine code: one for a register
     * or a literal, one per component for a vector.
     * @param op The IR operand to encode.
     * @param isa The instruction set for resolving register names.
     * @param ctx The source information for error reporting.
     * @return The packed molecules of the operand's cells, in placement order.
     * @throws CompilationException if the operand cannot be encoded: an unknown molecule type,
     *         or a label reference that linking left unresolved.
     */
    private int[] encodeOperand(IrOperand op, IInstructionSet isa, SourceInfo ctx) throws CompilationException {
        return switch (op) {
            case IrReg r -> {
                int regId = isa.resolveRegisterToken(r.name()).orElseThrow(() -> new RuntimeException(formatSource(ctx, "Unknown register: " + r.name())));
                yield new int[]{new Molecule(Config.TYPE_REGISTER, regId).toInt()};
            }
            case IrImm imm -> new int[]{new Molecule(Config.TYPE_DATA, (int) imm.value()).toInt()};
            case IrTypedImm ti -> {
                int type;
                try {
                    type = MoleculeTypeRegistry.nameToType(ti.typeName());
                } catch (IllegalArgumentException e) {
                    throw new CompilationException(formatSource(ctx, "Unknown molecule type: " + ti.typeName() + ". " + e.getMessage()));
                }
                yield new int[]{new Molecule(type, (int) ti.value()).toInt()};
            }
            case IrVec vec -> {
                int[] cells = new int[vec.components().length];
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = new Molecule(Config.TYPE_DATA, vec.components()[i]).toInt();
                }
                yield cells;
            }
            // A label reference is turned into a LABELREF literal by the linking rule of the
            // label feature; one that reaches the emitter names a label the layout does not have.
            case IrLabelRef ref -> throw new CompilationException(formatSource(ctx,
                    "Internal error: IrLabelRef '" + ref.labelName() + "' was not resolved during linking. " +
                    "This indicates a bug in LabelRefLinkingRule or a missing label definition."));
        };
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

    /**
     * Formats an error message with source information.
     * @param src The source information.
     * @param message The error message.
     * @return The formatted error string.
     */
    private String formatSource(SourceInfo src, String message) {
        if (src == null) return message;
        String file = src.fileName() != null ? src.fileName() : "<unknown>";
        int line = src.lineNumber();
        return String.format("%s:%d: %s", file, line, message);
    }
}
