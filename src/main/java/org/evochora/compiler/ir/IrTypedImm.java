package org.evochora.compiler.ir;

/**
 * Typed scalar literal value, used when the source contains an explicit type annotation.
 */
public record IrTypedImm(String typeName, long value) implements IrOperand {}


