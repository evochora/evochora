package org.evochora.compiler.features.importdir;

import org.evochora.compiler.api.QualifiedNames;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.expression.EvaluationResult;
import org.evochora.compiler.expression.Evaluator;
import org.evochora.compiler.expression.IEvaluationContext;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.expression.symbols.SymbolTableResolution;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.symbols.ExpressionOutcome;
import org.evochora.compiler.model.symbols.Resolution;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Pass-2 analysis handler for {@code .REQUIRE} directives.
 *
 * <p>Reports a module requirement of the main program: nothing imports the main program, so
 * nothing can supply the module, whether or not the alias is used. A requirement of a module is
 * supplied through a {@code USING} clause of the importer's {@code .IMPORT}, which
 * {@link ImportAnalysisHandler} checks.
 *
 * <p>Defines the constant of a value requirement, as {@code .CONST} defines its constant in this
 * pass: with the value the import of the placement bound, found in the placement's
 * {@link Requirements}, or else the default, through a {@link ValueBindingNode}. A default that is
 * a name is resolved here, at the line, as a use. Without binding and default the constant is
 * defined without a value, so that its uses report nothing of their own; in the main program the
 * missing value is reported here, in a module by the import.
 *
 * <p>A value that is a computation gives the constant a recorded value. A bound computation was
 * evaluated at the import line, in the importer's placement, by {@link ImportAnalysisHandler}: its
 * value becomes the constant's value; a tree only the layout computes stays with the import line,
 * and the constant's value is its own qualified name, under which the layout records the value
 * the import line computes. A default and a configured value that are computations are evaluated
 * here, in the placement of the requirement, whether or not they are the value the constant
 * takes, as a name among them is resolved here; the constant's value is the value or the tree
 * only the layout computes. An error in a default is reported at its line, an error in a
 * configured value against the configuration, naming it, without a file or line.
 */
public class RequireAnalysisHandler implements IAnalysisHandler {

    private final Evaluator evaluator;

    /**
     * Creates the handler.
     *
     * @param operations The operations of computations, read when the phase runs.
     */
    public RequireAnalysisHandler(OperationRegistry operations) {
        this.evaluator = new Evaluator(operations);
    }

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (node instanceof ValueRequirementNode valueNode) {
            analyzeValue(valueNode, symbolTable, diagnostics);
            return;
        }
        RequireNode requireNode = (RequireNode) node;
        if (isRoot(symbolTable)) {
            diagnostics.reportError(
                    "The main program cannot require a module: nothing imports it, so nothing can supply '"
                            + requireNode.alias() + "'; import \"" + requireNode.path() + "\" instead.",
                    requireNode.sourceInfo().fileName(),
                    requireNode.sourceInfo().lineNumber());
        }
    }

    private void analyzeValue(ValueRequirementNode valueNode, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        // A requirement inside a level was reported in pass 1 and defines nothing
        if (symbolTable.getCurrentScope() != symbolTable.getRootScope()) {
            return;
        }
        SourceInfo at = valueNode.sourceInfo();
        OperandNode bound = symbolTable.getModuleScope(symbolTable.getCurrentAliasChain())
                .flatMap(scope -> scope.record(Requirements.class, Requirements::none).boundValue(valueNode.name().toUpperCase()))
                .orElse(null);
        // A default or a configured value that is a name is a use of that name here, bound or
        // not: it is defined before this line, whatever the importer binds
        for (AstNode given : new AstNode[]{valueNode.defaultValue(), valueNode.configured()}) {
            if (given instanceof IdentifierNode id
                    && symbolTable.resolve(id.text(), id.sourceInfo()) instanceof Resolution.Missing missing) {
                diagnostics.reportError(
                        "Cannot require value '" + valueNode.name() + "' from '" + id.text() + "': " + missing.explanation(),
                        at.fileName(), at.lineNumber());
            }
        }
        ExpressionOutcome defaultOutcome = valueNode.defaultValue() instanceof ExpressionNode expression
                ? evaluate(expression, symbolTable, diagnostics, null) : null;
        ExpressionOutcome configuredOutcome = valueNode.configured() instanceof ExpressionNode expression
                ? evaluate(expression, symbolTable, diagnostics, valueNode) : null;
        // The importer's binding first, then the configuration, then the default
        OperandNode value = bound != null ? bound
                : valueNode.configured() != null ? valueNode.configured()
                : valueNode.defaultValue() instanceof OperandNode defaultValue ? defaultValue : null;
        ExpressionOutcome outcome = bound != null
                ? boundOutcome(bound, QualifiedNames.join(symbolTable.getCurrentAliasChain(), valueNode.name()), at,
                        at.plusColumns(valueNode.name().length()), symbolTable)
                : valueNode.configured() != null ? configuredOutcome : defaultOutcome;
        ValueBindingNode definition = new ValueBindingNode(value);
        if (outcome != null) {
            symbolTable.recordDefinitionValue(definition, outcome);
        }
        // Defined even without a value, so that a use of it reports nothing of its own
        symbolTable.define(new Symbol(valueNode.name(), at, Symbol.Type.CONSTANT, definition, valueNode.exported()))
                .ifPresent(existing -> diagnostics.reportError(
                        "Cannot require value '" + valueNode.name() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                        at.fileName(), at.lineNumber()));
        if (value == null && isRoot(symbolTable)) {
            diagnostics.reportError(
                    "The value '" + valueNode.name() + "' has no default and the configuration sets none; set it with values { "
                            + valueNode.name() + " = \"<value>\" } in the organism's entry or --value " + valueNode.name() + "=<value>.",
                    at.fileName(), at.lineNumber());
        }
    }

    /**
     * Returns the value a bound computation gives the constant: the value the import line
     * computed, or, for a tree only the layout computes, the constant's own qualified name with
     * the kind of that tree, standing from {@code at} to {@code end}, the written name. A binding
     * that is no computation, or whose computation failed, gives no recorded value.
     */
    private static ExpressionOutcome boundOutcome(OperandNode bound, String qualifiedName, SourceInfo at, SourceInfo end,
                                                  SymbolTable symbolTable) {
        if (!(bound instanceof ExpressionNode expression)) {
            return null;
        }
        return symbolTable.resultOf(expression)
                .map(result -> result.value() != null ? result
                        : ExpressionOutcome.later(new NameExpression(qualifiedName, at, end), result.kind()))
                .orElse(null);
    }

    /**
     * Evaluates a default or a configured value that is a computation and records its outcome for
     * it; reports the error of a computation that fails, at the position of the error for a
     * default, against the configuration for a configured value.
     *
     * @param configuredFor The requirement whose configured value the computation is; {@code null}
     *                      for a default.
     * @return The outcome; {@code null} when the computation failed.
     */
    private ExpressionOutcome evaluate(ExpressionNode expression, SymbolTable symbolTable, DiagnosticsEngine diagnostics,
                                       ValueRequirementNode configuredFor) {
        ExpressionOutcome outcome = switch (evaluator.evaluate(expression.expression(),
                new SymbolTableResolution(symbolTable), IEvaluationContext.none())) {
            case EvaluationResult.Value value -> ExpressionOutcome.of(value.value());
            case EvaluationResult.Partial partial -> ExpressionOutcome.later(partial.expression(), partial.kind());
            case EvaluationResult.Error error -> {
                if (configuredFor != null) {
                    diagnostics.reportError(ConfiguredValues.message(
                            configuredFor.name(), configuredFor.configuredText(), error.reason()), "", 0);
                } else {
                    SourceInfo errorAt = error.at() != null ? error.at() : expression.sourceInfo();
                    diagnostics.reportError(error.reason(), errorAt.fileName(), errorAt.lineNumber());
                }
                yield null;
            }
            case EvaluationResult.Reported reported -> null;
        };
        if (outcome != null) {
            symbolTable.recordResult(expression, outcome);
        }
        return outcome;
    }

    private static boolean isRoot(SymbolTable symbolTable) {
        String root = symbolTable.rootAliasChain();
        return root != null && root.equals(symbolTable.getCurrentAliasChain());
    }
}
