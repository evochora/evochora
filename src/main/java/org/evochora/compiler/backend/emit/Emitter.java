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
import org.evochora.compiler.ir.*;
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
     * @param program The linked IR program.
     * @param layout The layout result, containing coordinate and source mapping.
     * @param linkingContext The context from the linking phase, containing call site bindings.
     * @param isa The instruction set architecture for opcode and register resolution.
     * @param registerAliasMap A map of register aliases to their physical indices.
     * @param procNameToParamNames A map of procedure names to their parameter information (name and type).
     * @param sources A map of source file names to their content.
     * @return The final, compiled {@link ProgramArtifact}.
     * @throws CompilationException if an error occurs during emission.
     */
    public ProgramArtifact emit(IrProgram program,
                                LayoutResult layout,
                                LinkingContext linkingContext,
                                IInstructionSet isa,
                                Map<String, Integer> registerAliasMap,
                                Map<String, List<org.evochora.compiler.api.ParamInfo>> procNameToParamNames,
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
        Map<Integer, String> labelAddressToNameForDisplay = new HashMap<>();
        layout.labelToAddress().forEach((name, addr) -> labelAddressToNameForDisplay.put(addr, name));

        int address = 0;
        for (IrItem item : program.items()) {
            // Emit LABEL molecules (jump target markers)
            if (item instanceof IrLabelDef lbl) {
                // Use the label's registered linear address from the layout, not the sequential counter
                Integer labelLinearAddr = layout.labelToAddress().get(lbl.name());
                if (labelLinearAddr == null) {
                    throw new CompilationException(formatSource(lbl.source(), "Label '" + lbl.name() + "' not found in layout"));
                }
                int[] labelCoord = linearToCoord.get(labelLinearAddr);
                if (labelCoord == null) {
                    throw new CompilationException(formatSource(lbl.source(), "Missing coord for label address " + labelLinearAddr));
                }
                // Generate label hash from name (19-bit, always positive)
                int labelHash = lbl.name().hashCode() & 0x7FFFF;
                machineCodeLayout.put(labelCoord, new Molecule(Config.TYPE_LABEL, labelHash).toInt());
                address = labelLinearAddr + 1; // Sync the counter for subsequent items
                continue;
            }

            if (item instanceof IrInstruction ins) {
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
                            operandsAsString
                    );
                    sourceLineToInstructions.computeIfAbsent(sourceLineKey, k -> new ArrayList<>()).add(machineInfo);
                }

                List<IrOperand> ops = ins.operands();
                if (ops != null) {
                    for (IrOperand op : ops) {
                        if (op instanceof IrVec vec) {
                            int[] comps = vec.components();
                            for (int c : comps) {
                                int[] coord = linearToCoord.get(address);
                                if (coord == null) throw new CompilationException(formatSource(ins.source(), "Missing coord for address " + address));
                                machineCodeLayout.put(coord, new Molecule(Config.TYPE_DATA, c).toInt());
                                address++;
                            }
                        } else if (op instanceof IrLabelRef ref) {
                            // IrLabelRef should have been converted to IrImm by LabelRefLinkingRule
                            // If we reach here, something is wrong in the linking phase
                            throw new CompilationException(formatSource(ins.source(),
                                "Internal error: IrLabelRef '" + ref.labelName() + "' was not resolved during linking. " +
                                "This indicates a bug in LabelRefLinkingRule or a missing label definition."));
                        } else {
                            int[] coord = linearToCoord.get(address);
                            if (coord == null) throw new CompilationException(formatSource(ins.source(), "Missing coord for address " + address));
                            Integer value = encodeOperand(op, isa, ins.source());
                            machineCodeLayout.put(coord, value);
                            address++;
                        }
                    }
                }
            }
        }

        Map<Integer, String> labelAddressToName = new HashMap<>();
        layout.labelToAddress().forEach((name, addr) -> labelAddressToName.put(addr, name));

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

        String programId = Integer.toHexString(machineCodeLayout.hashCode());

        // Build label hash maps for fuzzy jump matching visualization
        Map<Integer, String> labelValueToName = new HashMap<>();
        Map<String, Integer> labelNameToValue = new HashMap<>();
        for (Map.Entry<String, Integer> entry : layout.labelToAddress().entrySet()) {
            String name = entry.getKey();
            // Hash must match the formula used in LabelRefLinkingRule (19-bit, always positive)
            int hash = name.hashCode() & 0x7FFFF;
            labelValueToName.put(hash, name);
            labelNameToValue.put(name, hash);
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
                labelAddressToName,
                registerAliasMap,
                procNameToParamNames,
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
     * Note: For CALL instructions, refOperands and valOperands are emitted as separate PUSH/POP instructions,
     * so we only format the main operands() here (which contains the procedure address/label).
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
        if (op instanceof IrReg r) {
            // Return the register name as-is (e.g., "%DR0")
            return r.name();
        }
        if (op instanceof IrImm imm) {
            // Return the numeric value as string
            return String.valueOf(imm.value());
        }
        if (op instanceof IrTypedImm ti) {
            // Format as "TYPE:VALUE" (e.g., "DATA:3")
            return ti.typeName() + ":" + ti.value();
        }
        if (op instanceof IrVec vec) {
            // Format vector components joined with "|" (e.g., "10|20")
            return Arrays.stream(vec.components())
                    .mapToObj(String::valueOf)
                    .collect(Collectors.joining("|"));
        }
        if (op instanceof IrLabelRef ref) {
            // Try to resolve label to address and format as delta from opcode
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
                    // Format delta as "x|y|..." for display
                    return Arrays.stream(delta)
                            .mapToObj(String::valueOf)
                            .collect(Collectors.joining("|"));
                }
            }
            // Fallback: return label name if resolution fails
            return ref.labelName();
        }
        return "?";
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
     * Encodes a single IR operand into its integer representation for machine code.
     * @param op The IR operand to encode.
     * @param isa The instruction set for resolving register names.
     * @param ctx The source information for error reporting.
     * @return The integer representation of the operand.
     * @throws CompilationException if the operand type is unsupported.
     */
    private Integer encodeOperand(IrOperand op, IInstructionSet isa, SourceInfo ctx) throws CompilationException {
        if (op instanceof IrReg r) {
            int regId = isa.resolveRegisterToken(r.name()).orElseThrow(() -> new RuntimeException(formatSource(ctx, "Unknown register: " + r.name())));
            return new Molecule(Config.TYPE_DATA, regId).toInt();
        }
        if (op instanceof IrImm imm) {
            return new Molecule(Config.TYPE_DATA, (int) imm.value()).toInt();
        }
        if (op instanceof IrTypedImm ti) {
            int type;
            try {
                type = MoleculeTypeRegistry.nameToType(ti.typeName());
            } catch (IllegalArgumentException e) {
                throw new CompilationException(formatSource(ctx, "Unknown molecule type: " + ti.typeName() + ". " + e.getMessage()));
            }
            return new Molecule(type, (int) ti.value()).toInt();
        }
        if (op instanceof IrVec vec) {
            return new Molecule(Config.TYPE_DATA, vec.components()[0]).toInt();
        }
        throw new CompilationException(formatSource(ctx, "Unsupported operand type: " + op.getClass().getSimpleName()));
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
        return String.format("[ERROR] %s:%d: %s", file, line, message);
    }
}
