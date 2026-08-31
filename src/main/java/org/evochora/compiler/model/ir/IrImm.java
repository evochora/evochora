package org.evochora.compiler.model.ir;

/**
 * Scalar literal value.
 *
 * @param value The scalar numeric value.
 */
public record IrImm(long value) implements IrOperand {}


