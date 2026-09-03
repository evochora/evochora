package org.evochora.compiler.backend.emit;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.ir.IrImm;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrLabelDef;
import org.evochora.compiler.model.ir.IrLabelRef;
import org.evochora.compiler.model.ir.IrOperand;
import org.evochora.compiler.model.ir.IrReg;
import org.evochora.compiler.model.ir.IrTypedImm;
import org.evochora.compiler.model.ir.IrVec;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MoleculeTypeRegistry;

/**
 * Turns IR items into the packed molecules of the machine code: an opcode into its CODE
 * molecule, a label into its LABEL molecule, an operand into the cells it occupies. This is
 * the whole encoding; the emitter only decides where the cells go.
 */
public final class OperandEncoder {

    private final IInstructionSet isa;

    /**
     * Creates an encoder for an instruction set.
     *
     * @param isa The instruction set that resolves opcode names and register names to ids.
     */
    public OperandEncoder(IInstructionSet isa) {
        this.isa = isa;
    }

    /**
     * Encodes the opcode of an instruction.
     *
     * @param instruction The instruction.
     * @return The packed CODE molecule.
     * @throws CompilationException if the instruction set does not know the opcode.
     */
    public int encodeOpcode(IrInstruction instruction) throws CompilationException {
        return isa.getInstructionIdByName(instruction.opcode()).orElseThrow(() ->
                new CompilationException(located(instruction.source(), "Unknown opcode: " + instruction.opcode())));
    }

    /**
     * Encodes a label definition.
     *
     * @param label The label.
     * @return The packed LABEL molecule carrying the label's value.
     */
    public int encodeLabel(IrLabelDef label) {
        return new Molecule(Config.TYPE_LABEL, label.value()).toInt();
    }

    /**
     * Encodes an operand into the cells it occupies in the machine code: one for a register
     * or a literal, one per component for a vector.
     *
     * @param op  The operand to encode.
     * @param src The source location of the instruction, for error reporting.
     * @return The packed molecules of the operand's cells, in placement order.
     * @throws CompilationException if the operand cannot be encoded: an unknown register, an
     *         unknown molecule type, or a label reference that linking left unresolved.
     */
    public int[] encodeOperand(IrOperand op, SourceInfo src) throws CompilationException {
        return switch (op) {
            case IrReg r -> {
                int regId = isa.resolveRegisterToken(r.name()).orElseThrow(() ->
                        new CompilationException(located(src, "Unknown register: " + r.name())));
                yield new int[]{new Molecule(Config.TYPE_REGISTER, regId).toInt()};
            }
            case IrImm imm -> new int[]{new Molecule(Config.TYPE_DATA, (int) imm.value()).toInt()};
            case IrTypedImm ti -> {
                int type;
                try {
                    type = MoleculeTypeRegistry.nameToType(ti.typeName());
                } catch (IllegalArgumentException e) {
                    throw new CompilationException(located(src, "Unknown molecule type: " + ti.typeName() + ". " + e.getMessage()));
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
            case IrLabelRef ref -> throw new CompilationException(located(src,
                    "Internal error: IrLabelRef '" + ref.labelName() + "' was not resolved during linking. " +
                    "This indicates a bug in LabelRefLinkingRule or a missing label definition."));
        };
    }

    /**
     * Prefixes a message with the file and line it concerns, as the compiler reports errors.
     *
     * @param src     The source location, or {@code null} for a message without one.
     * @param message The message.
     * @return "file:line: message", or the message alone without a location.
     */
    static String located(SourceInfo src, String message) {
        if (src == null) return message;
        String file = src.fileName() != null ? src.fileName() : "<unknown>";
        return String.format("%s:%d: %s", file, src.lineNumber(), message);
    }
}
