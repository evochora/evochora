package org.evochora.compiler.features.proc;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrOperand;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * IR instruction for CALL with REF/VAL/LREF/LVAL parameter passing.
 * Extends the generic {@link IrInstruction} with additional operand lists
 * for reference, value, location reference, and location value parameters.
 */
public final class IrCallInstruction extends IrInstruction {

    private final List<IrOperand> refOperands;
    private final List<IrOperand> valOperands;
    private final List<IrOperand> lrefOperands;
    private final List<IrOperand> lvalOperands;

    /**
     * @param opcode       The instruction opcode (always "CALL").
     * @param operands     The main operands (procedure name).
     * @param refOperands  The REF parameter operands (scalar by reference).
     * @param valOperands  The VAL parameter operands (scalar by value).
     * @param lrefOperands The LREF parameter operands (location by reference).
     * @param lvalOperands The LVAL parameter operands (location by value).
     * @param source       The source location.
     */
    public IrCallInstruction(
            String opcode,
            List<IrOperand> operands,
            List<IrOperand> refOperands,
            List<IrOperand> valOperands,
            List<IrOperand> lrefOperands,
            List<IrOperand> lvalOperands,
            SourceInfo source) {
        super(opcode, operands, source);
        this.refOperands = Collections.unmodifiableList(refOperands);
        this.valOperands = Collections.unmodifiableList(valOperands);
        this.lrefOperands = Collections.unmodifiableList(lrefOperands);
        this.lvalOperands = Collections.unmodifiableList(lvalOperands);
    }

    /** Scalar reference parameter operands. */
    public List<IrOperand> refOperands() { return refOperands; }

    /** Scalar value parameter operands. */
    public List<IrOperand> valOperands() { return valOperands; }

    /** Location reference parameter operands. */
    public List<IrOperand> lrefOperands() { return lrefOperands; }

    /** Location value parameter operands. */
    public List<IrOperand> lvalOperands() { return lvalOperands; }

    @Override
    public String toString() {
        return "IrCallInstruction{" +
                "opcode='" + opcode() + '\'' +
                ", operands=" + operands() +
                ", refOperands=" + refOperands +
                ", valOperands=" + valOperands +
                ", lrefOperands=" + lrefOperands +
                ", lvalOperands=" + lvalOperands +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        IrCallInstruction that = (IrCallInstruction) o;
        return Objects.equals(refOperands, that.refOperands) &&
                Objects.equals(valOperands, that.valOperands) &&
                Objects.equals(lrefOperands, that.lrefOperands) &&
                Objects.equals(lvalOperands, that.lvalOperands);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), refOperands, valOperands, lrefOperands, lvalOperands);
    }
}
