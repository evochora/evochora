package org.evochora.compiler.ir;

/**
 * Symbolic register reference. Resolution to numeric IDs happens in backend.
 */
public record IrReg(String name) implements IrOperand {}


