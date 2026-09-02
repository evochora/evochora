package org.evochora.compiler.model.ir;

/**
 * Typed scalar literal value, used when the source contains an explicit type annotation.
 *
 * @param typeName Name of the type.
 * @param value The scalar numeric value.
 */
public record IrTypedImm(String typeName, long value) implements IrOperand {}


