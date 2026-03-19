package org.evochora.compiler.features.proc;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrOperand;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * IR instruction for CALL with REF/VAL parameter passing.
 * Extends the generic {@link IrInstruction} with additional operand lists
 * for reference and value parameters.
 */
public final class IrCallInstruction extends IrInstruction {

    private final List<IrOperand> refOperands;
    private final List<IrOperand> valOperands;

    /**
     * @param opcode      The instruction opcode (always "CALL").
     * @param operands    The main operands (procedure name).
     * @param refOperands The REF parameter operands.
     * @param valOperands The VAL parameter operands.
     * @param source      The source location.
     */
    public IrCallInstruction(
            String opcode,
            List<IrOperand> operands,
            List<IrOperand> refOperands,
            List<IrOperand> valOperands,
            SourceInfo source) {
        super(opcode, operands, source);
        this.refOperands = Collections.unmodifiableList(refOperands);
        this.valOperands = Collections.unmodifiableList(valOperands);
    }

    public List<IrOperand> refOperands() {
        return refOperands;
    }

    public List<IrOperand> valOperands() {
        return valOperands;
    }

    @Override
    public String toString() {
        return "IrCallInstruction{" +
                "opcode='" + opcode() + '\'' +
                ", operands=" + operands() +
                ", refOperands=" + refOperands +
                ", valOperands=" + valOperands +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        IrCallInstruction that = (IrCallInstruction) o;
        return Objects.equals(refOperands, that.refOperands) &&
                Objects.equals(valOperands, that.valOperands);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), refOperands, valOperands);
    }
}
