package org.evochora.compiler.model.ir;

import org.evochora.compiler.model.expression.Value;

/**
 * A literal operand: a number, a typed value or a vector, carried as one {@link Value}. The
 * backend checks the range of a number where it becomes a cell.
 *
 * @param value The value of the literal.
 */
public record IrLiteral(Value value) implements IrOperand {}
