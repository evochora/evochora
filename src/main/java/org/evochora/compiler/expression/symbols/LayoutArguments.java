package org.evochora.compiler.expression.symbols;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.expression.EvaluationResult;
import org.evochora.compiler.expression.Evaluator;
import org.evochora.compiler.expression.IEvaluationContext;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.LiteralNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.symbols.ExpressionOutcome;
import org.evochora.compiler.model.symbols.SymbolTable;

import java.util.Optional;
import java.util.Set;

/**
 * Checks the arguments of the directives the layout finishes, {@code .ORG}, {@code .DIR} and
 * {@code .PLACE}, for the analysis handlers of their features. Each argument is evaluated through
 * the bridge to the symbol table, as a constant's value is: a literal has the kind of its value; a
 * computation is evaluated and its outcome recorded for it, a value or the tree only the layout
 * computes; a name is answered by the bridge, a label and a definition whose value only the layout
 * knows with the kind of the value the layout will give them. What fails is reported at the
 * argument, and the kind is checked against what the directive expects there.
 */
public final class LayoutArguments {

    /** The message for an argument that has to be a number, a component or a bound of a range. */
    public static final String EXPECTED_NUMBER = "Expected a number.";

    /** The message for an argument that has to be a vector, or a position where one is allowed. */
    public static final String EXPECTED_VECTOR = "Expected a vector.";

    private final Evaluator evaluator;

    /**
     * Creates the checker.
     *
     * @param operations The operations of computations, read when the phase runs.
     */
    public LayoutArguments(OperationRegistry operations) {
        this.evaluator = new Evaluator(operations);
    }

    /**
     * Checks an argument that has to be a number: a component, a bound or the step of a range.
     *
     * @param operand     The argument.
     * @param symbols     The symbol table, standing in the scope of the directive's line.
     * @param diagnostics Where an error is reported.
     */
    public void expectNumber(OperandNode operand, SymbolTable symbols, DiagnosticsEngine diagnostics) {
        expect(operand, Set.of(Kind.NUMBER), EXPECTED_NUMBER, symbols, diagnostics);
    }

    /**
     * Checks an argument that gives a whole place or direction: a vector, a number, which is a
     * vector of one component in a one-dimensional world, or a position where the directive takes
     * a place of the program as well.
     *
     * @param operand         The argument.
     * @param positionAllowed Whether a position is accepted as well.
     * @param symbols         The symbol table, standing in the scope of the directive's line.
     * @param diagnostics     Where an error is reported.
     */
    public void expectVector(OperandNode operand, boolean positionAllowed, SymbolTable symbols,
                             DiagnosticsEngine diagnostics) {
        expect(operand, positionAllowed ? Set.of(Kind.NUMBER, Kind.VECTOR, Kind.POSITION) : Set.of(Kind.NUMBER, Kind.VECTOR),
                EXPECTED_VECTOR, symbols, diagnostics);
    }

    /**
     * Checks an argument that has to be a typed value, such as the molecule a {@code .PLACE}
     * writes; a value only the layout knows passes with the kind it will have.
     *
     * @param operand     The argument.
     * @param message     The message for a value of another kind.
     * @param symbols     The symbol table, standing in the scope of the directive's line.
     * @param diagnostics Where an error is reported.
     */
    public void expectTyped(OperandNode operand, String message, SymbolTable symbols, DiagnosticsEngine diagnostics) {
        expect(operand, Set.of(Kind.TYPED), message, symbols, diagnostics);
    }

    private void expect(OperandNode operand, Set<Kind> expected, String message, SymbolTable symbols,
                        DiagnosticsEngine diagnostics) {
        kindOf(operand, symbols, diagnostics)
                .filter(kind -> !expected.contains(kind))
                .ifPresent(kind -> report(message, operand.sourceInfo(), diagnostics));
    }

    /**
     * Evaluates an argument and gives the kind of its value, or of the value the layout will give
     * it; reports why it has none.
     *
     * @return The kind, or empty after a reported error.
     */
    private Optional<Kind> kindOf(OperandNode operand, SymbolTable symbols, DiagnosticsEngine diagnostics) {
        return switch (operand) {
            case LiteralNode literal -> Optional.of(literal.value().kind());
            case ExpressionNode expression -> {
                EvaluationResult result = evaluate(expression, symbols, diagnostics);
                if (result instanceof EvaluationResult.Value value) {
                    symbols.recordResult(expression, ExpressionOutcome.of(value.value()));
                } else if (result instanceof EvaluationResult.Partial partial) {
                    symbols.recordResult(expression, ExpressionOutcome.later(partial.expression(), partial.kind()));
                }
                yield kindOf(result);
            }
            case IdentifierNode name -> kindOf(evaluator.evaluate(new NameExpression(name.text(), name.sourceInfo()),
                    new SymbolTableResolution(symbols), IEvaluationContext.none()), name.sourceInfo(), diagnostics);
            case RegisterNode register -> {
                report("Register '" + register.name() + "' cannot be used in an expression.", register.sourceInfo(), diagnostics);
                yield Optional.empty();
            }
        };
    }

    private EvaluationResult evaluate(ExpressionNode expression, SymbolTable symbols, DiagnosticsEngine diagnostics) {
        EvaluationResult result = evaluator.evaluate(expression.expression(), new SymbolTableResolution(symbols),
                IEvaluationContext.none());
        reportError(result, expression.sourceInfo(), diagnostics);
        return result;
    }

    private static Optional<Kind> kindOf(EvaluationResult result, SourceInfo at, DiagnosticsEngine diagnostics) {
        reportError(result, at, diagnostics);
        return kindOf(result);
    }

    private static Optional<Kind> kindOf(EvaluationResult result) {
        return switch (result) {
            case EvaluationResult.Value value -> Optional.of(value.value().kind());
            case EvaluationResult.Partial partial -> Optional.of(partial.kind());
            case EvaluationResult.Error error -> Optional.empty();
            case EvaluationResult.Reported reported -> Optional.empty();
        };
    }

    private static void reportError(EvaluationResult result, SourceInfo fallback, DiagnosticsEngine diagnostics) {
        if (result instanceof EvaluationResult.Error error) {
            report(error.reason(), error.at() != null ? error.at() : fallback, diagnostics);
        }
    }

    private static void report(String message, SourceInfo at, DiagnosticsEngine diagnostics) {
        diagnostics.reportError(message, at.fileName(), at.lineNumber());
    }
}
