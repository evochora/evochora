package org.evochora.compiler.ir;

/**
 * Symbolic label reference used by control-flow instructions.
 */
public record IrLabelRef(String labelName) implements IrOperand {}


