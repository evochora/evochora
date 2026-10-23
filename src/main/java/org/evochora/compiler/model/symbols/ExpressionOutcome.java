package org.evochora.compiler.model.symbols;

import org.evochora.compiler.model.expression.Expression;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.Value;

import java.util.Objects;

/**
 * What phase 4 made of a computation, as the symbol table records it: its value, or the tree a
 * later phase computes together with the kind its value will have. A computation that failed
 * has no outcome; its error has been reported.
 *
 * @param value     The value; {@code null} when only a later phase can compute it.
 * @param remaining The tree a later phase computes, in which every name is qualified;
 *                  {@code null} when the computation has a value.
 * @param kind      The kind of the value, now or later.
 */
public record ExpressionOutcome(Value value, Expression remaining, Kind kind) {

    /**
     * Requires exactly one of the value and the tree, and a kind that matches a value.
     *
     * @throws IllegalArgumentException if both or neither are given, or the kind does not match
     *         the value.
     */
    public ExpressionOutcome {
        Objects.requireNonNull(kind, "kind");
        if ((value == null) == (remaining == null)) {
            throw new IllegalArgumentException("An outcome is either a value or a remaining tree.");
        }
        if (value != null && value.kind() != kind) {
            throw new IllegalArgumentException("The kind " + kind + " does not match the value " + value.text() + ".");
        }
    }

    /**
     * The outcome of a computation that has a value.
     *
     * @param value The value.
     * @return The outcome.
     */
    public static ExpressionOutcome of(Value value) {
        return new ExpressionOutcome(Objects.requireNonNull(value, "value"), null, value.kind());
    }

    /**
     * The outcome of a computation only a later phase can compute.
     *
     * @param remaining The tree that remains, with every name qualified.
     * @param kind      The kind its value will have.
     * @return The outcome.
     */
    public static ExpressionOutcome later(Expression remaining, Kind kind) {
        return new ExpressionOutcome(null, Objects.requireNonNull(remaining, "remaining"), kind);
    }
}
