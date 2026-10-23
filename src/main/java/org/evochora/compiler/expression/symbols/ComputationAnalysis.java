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
 * computes. What a handler checks beyond that stays with the handler.
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
     * Evaluates a computation and records its outcome for it; reports nothing, so that the caller
     * can report an error in words of its own.
     *
     * @param expression  The computation.
     * @param symbolTable The symbol table, standing in the scope of the computation's line.
     * @return The result of the evaluation.
     */
    public EvaluationResult evaluate(ExpressionNode expression, SymbolTable symbolTable) {
        EvaluationResult result = evaluator.evaluate(expression.expression(),
                new SymbolTableResolution(symbolTable), IEvaluationContext.none());
        outcomeOf(result).ifPresent(outcome -> symbolTable.recordResult(expression, outcome));
        return result;
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
