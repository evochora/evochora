/*
 * SPDX-FileCopyrightText: 2024-2024 EvoChora contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evochora.compiler.ir;

import org.evochora.compiler.api.SourceInfo;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents an instruction in the intermediate representation.
 */
public final class IrInstruction implements IrItem {
    private final String opcode;
    private final List<IrOperand> operands;
    private final List<IrOperand> refOperands;
    private final List<IrOperand> valOperands;
    private final SourceInfo source;

    /**
     * Constructor for instructions with standard operands.
     *
     * @param opcode   The instruction opcode.
     * @param operands The list of operands.
     * @param source The source information.
     */
    public IrInstruction(final String opcode, final List<IrOperand> operands, final SourceInfo source) {
        this(opcode, operands, Collections.emptyList(), Collections.emptyList(), source);
    }

    /**
     * Constructor for CALL instructions with REF and VAL operands.
     *
     * @param opcode    The instruction opcode.
     * @param operands The main operands (procedure name).
     * @param refOperands The list of REF operands.
     * @param valOperands The list of VAL operands.
     * @param source The source information.
     */
    public IrInstruction(
            final String opcode,
            final List<IrOperand> operands,
            final List<IrOperand> refOperands,
            final List<IrOperand> valOperands,
            final SourceInfo source) {
        this.opcode = opcode;
        this.operands = Collections.unmodifiableList(operands);
        this.refOperands = Collections.unmodifiableList(refOperands);
        this.valOperands = Collections.unmodifiableList(valOperands);
        this.source = source;
    }

    public String opcode() {
        return opcode;
    }

    public List<IrOperand> operands() {
        return operands;
    }

    public List<IrOperand> refOperands() {
        return refOperands;
    }

    public List<IrOperand> valOperands() {
        return valOperands;
    }

    @Override
    public SourceInfo source() {
        return source;
    }

    @Override
    public String toString() {
        if (refOperands.isEmpty() && valOperands.isEmpty()) {
            return "IrInstruction{" +
                    "opcode='" + opcode + '\'' +
                    ", operands=" + operands +
                    '}';
        }
        return "IrInstruction{" +
                "opcode='" + opcode + '\'' +
                ", operands=" + operands +
                ", refOperands=" + refOperands +
                ", valOperands=" + valOperands +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IrInstruction that = (IrInstruction) o;
        return Objects.equals(opcode, that.opcode) &&
                Objects.equals(operands, that.operands) &&
                Objects.equals(refOperands, that.refOperands) &&
                Objects.equals(valOperands, that.valOperands);
    }

    @Override
    public int hashCode() {
        return Objects.hash(opcode, operands, refOperands, valOperands);
    }
}
