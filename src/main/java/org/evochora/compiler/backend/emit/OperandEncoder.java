package org.evochora.compiler.backend.emit;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.ir.IrImm;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrLabelRef;
import org.evochora.compiler.model.ir.IrOperand;
import org.evochora.compiler.model.ir.IrReg;
import org.evochora.compiler.model.ir.IrTypedImm;
import org.evochora.compiler.model.ir.IrVec;

/**
 * Turns IR items into the packed molecules of the machine code: an opcode into its CODE
 * molecule, a label into its LABEL molecule, an operand into the cells it occupies. This is
 * the whole encoding; the emitter only decides where the cells go.
 */
public final class OperandEncoder {

    private final IInstructionSet isa;
    private final int dataType;
    private final int registerType;
    private final int labelType;

    /**
     * Creates an encoder for an instruction set.
     *
     * @param isa The instruction set that resolves names to ids and packs molecules into cells.
     */
    public OperandEncoder(IInstructionSet isa) {
        this.isa = isa;
        this.dataType = typeOf("DATA");
        this.registerType = typeOf("REGISTER");
        this.labelType = typeOf("LABEL");
    }

    /**
     * The code of a molecule type every program needs; an instruction set without it cannot
     * be a target at all.
     */
    private int typeOf(String name) {
        return isa.moleculeType(name).orElseThrow(() ->
                new IllegalStateException("The instruction set has no molecule type " + name));
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
                new CompilationException(SourceInfo.locate(instruction.source(), "Unknown opcode: " + instruction.opcode())));
    }

    /**
     * Encodes a label definition.
     *
     * @param value The value the layout assigned to the label.
     * @return The packed LABEL molecule carrying that value.
     */
    public int encodeLabel(int value) {
        return isa.encodeCell(labelType, value);
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
                        new CompilationException(SourceInfo.locate(src, "Unknown register: " + r.name())));
                yield new int[]{isa.encodeCell(registerType, regId)};
            }
            case IrImm imm -> new int[]{isa.encodeCell(dataType, (int) imm.value())};
            case IrTypedImm ti -> {
                int type = isa.moleculeType(ti.typeName()).orElseThrow(() ->
                        new CompilationException(SourceInfo.locate(src, "Unknown molecule type: " + ti.typeName())));
                yield new int[]{isa.encodeCell(type, (int) ti.value())};
            }
            case IrVec vec -> {
                int[] cells = new int[vec.components().length];
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = isa.encodeCell(dataType, vec.components()[i]);
                }
                yield cells;
            }
            // A label reference is turned into a LABELREF literal by the linking rule of the
            // label feature; one that reaches the emitter names a label the layout does not have.
            case IrLabelRef ref -> throw new CompilationException(SourceInfo.locate(src,
                    "Internal error: IrLabelRef '" + ref.labelName() + "' was not resolved during linking. " +
                    "This indicates a bug in LabelRefLinkingRule or a missing label definition."));
        };
    }

}
