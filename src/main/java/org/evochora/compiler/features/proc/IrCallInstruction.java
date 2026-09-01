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
     * Creates a call that carries its parameter passing lists next to the ordinary operands. The four
     * parameter lists are stored as unmodifiable views of the given lists, which are not copied, and
     * none of them may be null; pass an empty list for a parameter kind the call does not use.
     *
     * @param opcode       The instruction opcode (always "CALL").
     * @param operands     The main operands (procedure name).
     * @param refOperands  The REF parameter operands (scalar by reference).
     * @param valOperands  The VAL parameter operands (scalar by value).
     * @param lrefOperands The LREF parameter operands (location by reference).
     * @param lvalOperands The LVAL parameter operands (location by value).
     * @param source       The source location.
     * @throws NullPointerException if one of the four parameter operand lists is null.
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

    /**
     * Keeps the parameter lists when the main operands are rewritten.
     * <p>
     * The procedure name of a call is resolved to a label reference during linking, which replaces
     * the main operands. Without this, the call would come out of that step as a plain instruction
     * and the REF/VAL/LREF/LVAL lists would be gone - along with everything that reads them.
     *
     * @param newOperands The main operands of the returned call.
     * @return A call with those main operands and this call's parameter lists.
     */
    @Override
    public IrCallInstruction withOperands(final List<IrOperand> newOperands) {
        return new IrCallInstruction(opcode(), newOperands,
                refOperands, valOperands, lrefOperands, lvalOperands, source());
    }

    /**
     * Scalar reference parameter operands.
     *
     * @return An unmodifiable view of the operands passed with REF, in the order written at the call
     *         site, which is the order in which they bind to the procedure's formal scalar reference
     *         parameters. Empty if the call passes none of this kind.
     */
    public List<IrOperand> refOperands() { return refOperands; }

    /**
     * Scalar value parameter operands.
     *
     * @return An unmodifiable view of the operands passed with VAL, in the order written at the call
     *         site, which is the order in which they bind to the procedure's formal scalar value
     *         parameters. Empty if the call passes none of this kind.
     */
    public List<IrOperand> valOperands() { return valOperands; }

    /**
     * Location reference parameter operands.
     *
     * @return An unmodifiable view of the operands passed with LREF, in the order written at the call
     *         site, which is the order in which they bind to the procedure's formal location reference
     *         parameters. Empty if the call passes none of this kind.
     */
    public List<IrOperand> lrefOperands() { return lrefOperands; }

    /**
     * Location value parameter operands.
     *
     * @return An unmodifiable view of the operands passed with LVAL, in the order written at the call
     *         site, which is the order in which they bind to the procedure's formal location value
     *         parameters. Empty if the call passes none of this kind.
     */
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
