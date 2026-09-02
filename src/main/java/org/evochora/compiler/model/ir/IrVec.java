package org.evochora.compiler.model.ir;

/**
 * n-dimensional vector literal. The backend validates dimensionality
 * against the configured world shape.
 *
 * @param components Vector component values.
 */
public record IrVec(int[] components) implements IrOperand {}


