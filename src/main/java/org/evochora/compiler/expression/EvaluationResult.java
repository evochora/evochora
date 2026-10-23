package org.evochora.compiler.expression;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.expression.Expression;
import org.evochora.compiler.model.expression.Kind;

/**
 * What the {@link Evaluator} made of a computation: a value, a smaller tree for a later phase, an
 * error for the caller to report, or nothing because the error has been reported already.
 */
public sealed interface EvaluationResult
        permits EvaluationResult.Value, EvaluationResult.Partial, EvaluationResult.Error, EvaluationResult.Reported {

    /**
     * The computation has a value.
     *
     * @param value The value.
     */
    record Value(org.evochora.compiler.model.expression.Value value) implements EvaluationResult {
    }

    /**
     * The computation needs what a later phase knows. The tree holds only names a later phase
     * knows and operations that answered "not yet"; every part that could be computed stands in
     * it as its literal.
     *
     * @param expression The remaining tree.
     * @param kind       The kind its value will have, derived from the signatures.
     */
    record Partial(Expression expression, Kind kind) implements EvaluationResult {
    }

    /**
     * The computation has no value; the caller reports the reason.
     *
     * @param reason One sentence, names quoted.
     * @param at     The position of the node that failed.
     */
    record Error(String reason, SourceInfo at) implements EvaluationResult {
    }

    /**
     * The computation has no value, and there is nothing to say: the error stands at a definition
     * it uses and has been reported there.
     */
    record Reported() implements EvaluationResult {
    }
}
