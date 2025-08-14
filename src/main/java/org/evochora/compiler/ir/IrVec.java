package org.evochora.compiler.ir;

/**
 * n-dimensional vector literal. The backend validates dimensionality
 * against the configured world shape.
 */
public record IrVec(int[] components) implements IrOperand {}


