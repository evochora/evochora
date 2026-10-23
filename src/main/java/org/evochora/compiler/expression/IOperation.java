package org.evochora.compiler.expression;

import org.evochora.compiler.model.expression.Value;

import java.util.List;

/**
 * An operation a computation can apply: an operator or a function, with its overloads and what it
 * computes. Features register operations through
 * {@link org.evochora.compiler.IFeatureRegistrationContext#operation(IOperation)}; the
 * {@link OperationRegistry} keys them by name and notation, so {@code -} as a prefix and {@code -}
 * as an infix are two operations, and several operations under one key are the overloads of one.
 */
public interface IOperation {

    /**
     * Returns the name the operation is registered and written under: an operator character by
     * its text ({@code +}, {@code -}, {@code |}, {@code :}), the index access as {@code []}, a word
     * operator or a function by its upper-case word ({@code MOD}, {@code NOT}, {@code POSITION}).
     *
     * @return The name.
     */
    String name();

    /**
     * Returns where the operation stands among its operands.
     *
     * @return The notation.
     */
    Notation notation();

    /**
     * Returns the level the operation binds at; a function stands at {@link Level#INDEX}, a prefix
     * operator at {@link Level#PREFIX}, a chain at {@link Level#CHAIN}.
     *
     * @return The level.
     */
    Level level();

    /**
     * Returns the overloads of the operation: the kinds it takes and the kind it gives.
     *
     * @return The signatures, at least one.
     */
    List<Signature> signatures();

    /**
     * Computes the operation for operands that fit one of its signatures.
     *
     * @param operands The values of the operands, in order; their kinds fit one of
     *                 {@link #signatures()}.
     * @param context  What the phase that evaluates knows of the program's layout.
     * @return The value, "not yet" when the operation needs what only the layout knows, or the
     *         reason it cannot be computed.
     */
    OperationResult compute(List<Value> operands, IEvaluationContext context);
}
