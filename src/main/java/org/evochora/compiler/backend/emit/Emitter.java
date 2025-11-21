package org.evochora.compiler.backend.emit;

import org.evochora.runtime.Config;
import org.evochora.compiler.api.CompilationException;
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

        int address = 0;
        for (IrItem item : program.items()) {
            if (item instanceof IrInstruction ins) {
                int opcode = isa.getInstructionIdByName(ins.opcode()).orElseThrow(() ->
                        new RuntimeException(formatSource(ins.source(), "Unknown opcode: " + ins.opcode())));
                int[] opcodeCoord = linearToCoord.get(address);
                if (opcodeCoord == null) throw new CompilationException(formatSource(ins.source(), "Missing coord for address " + address));
                machineCodeLayout.put(opcodeCoord, opcode);
                address++;

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
                            // Fallback: resolve any remaining label refs here using the opcode coordinate
                            Integer targetAddr = layout.labelToAddress().get(ref.labelName());
                            if (targetAddr == null) {
                                throw new CompilationException(formatSource(ins.source(), "Unknown label reference: " + ref.labelName()));
                            }
                            int[] dstCoord = linearToCoord.get(targetAddr);
                            if (dstCoord == null) throw new CompilationException(formatSource(ins.source(), "Missing coord for label target address " + targetAddr));
                            int dims = Math.max(opcodeCoord.length, dstCoord.length);
                            int[] delta = new int[dims];
                            for (int d = 0; d < dims; d++) {
                                int s = d < opcodeCoord.length ? opcodeCoord[d] : 0;
                                int t = d < dstCoord.length ? dstCoord[d] : 0;
                                delta[d] = t - s;
                            }
                            for (int c : delta) {
                                int[] coord = linearToCoord.get(address);
                                if (coord == null) throw new CompilationException(formatSource(ins.source(), "Missing coord for address " + address));
                                machineCodeLayout.put(coord, new Molecule(Config.TYPE_DATA, c).toInt());
                                address++;
                            }
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

        // Sort both machineCodeLayout and initialObjects by coordinate to ensure deterministic iteration.
        // HashMap<int[], V> has non-deterministic iteration order because int[] uses identity-based hashCode.
        // Sorting here at compile time ensures any consumer gets consistent, deterministic iteration order.
        Map<int[], Integer> sortedMachineCodeLayout = sortMapByCoordinate(machineCodeLayout);
        Map<int[], PlacedMolecule> sortedInitialObjects = sortMapByCoordinate(initialObjects);

        String programId = Integer.toHexString(machineCodeLayout.hashCode());

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
                tokenLookup
        );
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
