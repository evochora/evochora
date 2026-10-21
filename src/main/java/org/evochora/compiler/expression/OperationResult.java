package org.evochora.compiler.expression;

/**
 * What an operation computed: a value, "not yet", or the reason it cannot be computed.
 */
public sealed interface OperationResult permits OperationResult.Value, OperationResult.NotYet, OperationResult.Error {

    /**
     * The value the operation computed.
     *
     * @param value The result.
     */
    record Value(org.evochora.compiler.model.expression.Value value) implements OperationResult {
    }

    /**
     * The operation needs what only the layout knows, such as the origin the line counts its
     * vectors from; the phase that evaluates keeps the operation for a later phase.
     */
    record NotYet() implements OperationResult {
    }

    /**
     * The operation cannot be computed for these operands.
     *
     * @param reason One sentence, names quoted, such as {@code Division by zero.}
     */
    record Error(String reason) implements OperationResult {
    }
}
