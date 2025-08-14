package org.evochora.compiler.ir;

/**
 * Scalar literal value.
 */
public record IrImm(long value) implements IrOperand {}


