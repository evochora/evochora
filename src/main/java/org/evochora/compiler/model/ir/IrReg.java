package org.evochora.compiler.model.ir;

/**
 * Symbolic register reference. Resolution to numeric IDs happens in backend.
 *
 * @param name Register name or alias.
 */
public record IrReg(String name) implements IrOperand {}


