package org.evochora.compiler.features.constdir;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.expression.EvaluationResult;
import org.evochora.compiler.expression.Evaluator;
import org.evochora.compiler.expression.IEvaluationContext;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.expression.symbols.SymbolTableResolution;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.symbols.ExpressionOutcome;
import org.evochora.compiler.model.symbols.Resolution;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;

/**
 * Handles the semantic analysis of {@link ConstNode}s.
 * This involves defining the constant in the symbol table, with the node itself as the
 * symbol's node, so that a reference to the constant can be replaced by its value, and checking
 * that a name given as the value is defined at this line. The value itself is followed at a use
 * of the constant.
 * <p>
 * A computation given as the value is evaluated at this line, with the names defined above it.
 * Its outcome is recorded for the computation and as the value of the constant: a value, or the
 * tree only the layout computes, which makes the constant a layout value. A computation that
 * fails is reported here; the constant is defined either way, so that a use of it reports nothing
 * of its own.
 */
public class ConstAnalysisHandler implements IAnalysisHandler {

    private final Evaluator evaluator;

    /**
     * Creates the handler.
     *
     * @param operations The operations of computations, read when the phase runs.
     */
    public ConstAnalysisHandler(OperationRegistry operations) {
        this.evaluator = new Evaluator(operations);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (node instanceof ConstNode constNode) {
            if (constNode.value() instanceof IdentifierNode id
                    && symbolTable.resolve(id.text(), id.sourceInfo()) instanceof Resolution.Missing missing) {
                diagnostics.reportError(
                        "Cannot define constant '" + constNode.name() + "' from '" + id.text() + "': " + missing.explanation(),
                        constNode.sourceInfo().fileName(), constNode.sourceInfo().lineNumber());
            }
            if (constNode.value() instanceof ExpressionNode expression) {
                evaluate(constNode, expression, symbolTable, diagnostics);
            }
            // Defined even when its value is missing, so that a use of it reports nothing of its own
            symbolTable.define(new Symbol(constNode.name(), constNode.sourceInfo(), Symbol.Type.CONSTANT, constNode, constNode.exported()))
                    .ifPresent(existing -> diagnostics.reportError(
                            "Cannot define constant '" + constNode.name() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                            constNode.sourceInfo().fileName(), constNode.sourceInfo().lineNumber()));
        }
    }

    /**
     * Evaluates the computation a constant is given and records its outcome, for the computation
     * and as the constant's value; reports the error of a computation that fails.
     */
    private void evaluate(ConstNode constNode, ExpressionNode expression, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        ExpressionOutcome outcome = switch (evaluator.evaluate(expression.expression(),
                new SymbolTableResolution(symbolTable), IEvaluationContext.none())) {
            case EvaluationResult.Value value -> ExpressionOutcome.of(value.value());
            case EvaluationResult.Partial partial -> ExpressionOutcome.later(partial.expression(), partial.kind());
            case EvaluationResult.Error error -> {
                SourceInfo at = error.at() != null ? error.at() : expression.sourceInfo();
                diagnostics.reportError(error.reason(), at.fileName(), at.lineNumber());
                yield null;
            }
            case EvaluationResult.Reported reported -> null;
        };
        if (outcome != null) {
            symbolTable.recordResult(expression, outcome);
            symbolTable.recordDefinitionValue(constNode, outcome);
        }
    }
}
