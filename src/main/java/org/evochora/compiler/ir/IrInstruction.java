package org.evochora.compiler.ir;

import org.evochora.compiler.api.SourceInfo;

import java.util.List;

/**
 * IR instruction with opcode and typed operands. Keeps symbolic
 * information (register names, labels) for later backend resolution.
 */
public record IrInstruction(String opcode, List<IrOperand> operands, SourceInfo source) implements IrItem {}


