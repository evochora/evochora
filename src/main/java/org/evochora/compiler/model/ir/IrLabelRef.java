package org.evochora.compiler.model.ir;

/**
 * Symbolic label reference used by control-flow instructions.
 *
 * @param labelName Target label name.
 */
public record IrLabelRef(String labelName) implements IrOperand {}


