package org.evochora.compiler.model.ir;

/**
 * Scalar literal value.
 */
public record IrImm(long value) implements IrOperand {}


