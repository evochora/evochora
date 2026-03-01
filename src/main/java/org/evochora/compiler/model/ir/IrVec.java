package org.evochora.compiler.model.ir;

/**
 * n-dimensional vector literal. The backend validates dimensionality
 * against the configured world shape.
 */
public record IrVec(int[] components) implements IrOperand {}


