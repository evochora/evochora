package org.evochora.compiler.ir;

/**
 * Base type for instruction operands in the IR. Keeps semantic meaning
 * of arguments intact until the backend resolves them.
 */
public sealed interface IrOperand permits IrReg, IrImm, IrVec, IrLabelRef, IrTypedImm {}


