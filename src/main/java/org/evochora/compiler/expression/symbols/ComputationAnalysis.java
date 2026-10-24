package org.evochora.compiler.expression.symbols;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.expression.EvaluationResult;
import org.evochora.compiler.expression.Evaluator;
import org.evochora.compiler.expression.IEvaluationContext;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.symbols.ExpressionOutcome;
import org.evochora.compiler.model.symbols.SymbolTable;

import java.util.Optional;

/**
 * Evaluates a written computation in the analysis phase, as every analysis handler does with the
 * computations it meets: through the bridge to the symbol table, at the scope the symbol table
 * stands in, with the outcome recorded for the computation, a value or the tree only the layout
 * computes. A computation is evaluated once: a second evaluation answers from what the first
 * recorded, its outcome or its failure, so that a handler run twice for one computation, once
 * when a name needed its value and once when the walk reached its line, evaluates and reports
 * once. What a handler checks beyond that stays with the handler.
 */
public final class ComputationAnalysis {

    private final Evaluator evaluator;

    /**
     * Creates the analysis.
     *
     * @param operations The operations of computations, read when the phase runs.
     */
    public ComputationAnalysis(OperationRegistry operations) {
        this.evaluator = new Evaluator(operations);
    }

    /**
     * Evaluates a computation and records its outcome or its failure for it; reports nothing, so
     * that the caller can report an error in words of its own. A computation evaluated before
     * answers from its record: its outcome, or {@link EvaluationResult.Reported} for a failure,
     * whose error was reported the first time.
     *
     * @param expression  The computation.
     * @param symbolTable The symbol table, standing in the scope of the computation's line.
     * @return The result of the evaluation.
     */
    public EvaluationResult evaluate(ExpressionNode expression, SymbolTable symbolTable) {
        Optional<ExpressionOutcome> recorded = symbolTable.resultOf(expression);
        if (recorded.isPresent()) {
            return resultOf(recorded.get());
        }
        if (symbolTable.failed(expression)) {
            return new EvaluationResult.Reported();
        }
        EvaluationResult result = evaluator.evaluate(expression.expression(),
                new SymbolTableResolution(symbolTable), IEvaluationContext.none());
        Optional<ExpressionOutcome> outcome = outcomeOf(result);
        if (outcome.isPresent()) {
            symbolTable.recordResult(expression, outcome.get());
        } else {
            symbolTable.recordFailure(expression);
        }
        return result;
    }

    /**
     * The result a recorded outcome stands for: the value, or the tree a later phase computes.
     */
    private static EvaluationResult resultOf(ExpressionOutcome outcome) {
        return outcome.value() != null
                ? new EvaluationResult.Value(outcome.value())
                : new EvaluationResult.Partial(outcome.remaining(), outcome.kind());
    }

    /**
     * Evaluates a computation, records its outcome for it, and reports the error of a computation
     * that fails, at the place the error names or else at the computation.
     *
     * @param expression  The computation.
     * @param symbolTable The symbol table, standing in the scope of the computation's line.
     * @param diagnostics Where the error is reported.
     * @return The result of the evaluation.
     */
    public EvaluationResult evaluate(ExpressionNode expression, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        EvaluationResult result = evaluate(expression, symbolTable);
        if (result instanceof EvaluationResult.Error error) {
            SourceInfo at = error.at() != null ? error.at() : expression.sourceInfo();
            diagnostics.reportError(error.reason(), at.fileName(), at.lineNumber());
        }
        return result;
    }

    /**
     * Returns the outcome a result records: its value, or the tree only the layout computes;
     * nothing for a computation that failed.
     *
     * @param result The result of an evaluation.
     * @return The outcome, or empty for an error or an error reported elsewhere.
     */
    public static Optional<ExpressionOutcome> outcomeOf(EvaluationResult result) {
        return switch (result) {
            case EvaluationResult.Value value -> Optional.of(ExpressionOutcome.of(value.value()));
            case EvaluationResult.Partial partial -> Optional.of(ExpressionOutcome.later(partial.expression(), partial.kind()));
            case EvaluationResult.Error error -> Optional.empty();
            case EvaluationResult.Reported reported -> Optional.empty();
        };
    }
}
