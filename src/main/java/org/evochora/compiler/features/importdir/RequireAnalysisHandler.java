package org.evochora.compiler.features.importdir;

import java.util.Locale;
import org.evochora.compiler.api.QualifiedNames;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.expression.EvaluationResult;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.expression.symbols.ComputationAnalysis;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.symbols.ExpressionOutcome;
import org.evochora.compiler.model.symbols.Resolution;
import org.evochora.compiler.model.symbols.ResolvedSymbol;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Pass-2 analysis handler for {@code .REQUIRE} directives and for the {@link ValueBindingNode}
 * behind the constant a value requirement defines.
 *
 * <p>Reports a module requirement of the main program: nothing imports the main program, so
 * nothing can supply the module, whether or not the alias is used. A requirement of a module is
 * supplied through a {@code USING} clause of the importer's {@code .IMPORT}, which
 * {@link ImportAnalysisHandler} checks.
 *
 * <p>The constant of a value requirement was defined in pass 1 by {@link RequireSymbolCollector},
 * with the value the import of the placement bound, or else the configured value or the default,
 * behind a {@link ValueBindingNode}. At the requirement's line this handler resolves a default or
 * a configured value that is a name, as a use, reports a missing value of the main program, and
 * computes the constant's value. The value is computed for the binding node, which is also what
 * the handler is run for when a use of the name above the requirement's line needs the value
 * first; computed either way, it is recorded once.
 *
 * <p>A value that is a computation gives the constant a recorded value. A bound computation is
 * evaluated in the importer's placement, where it is written, the first time it is needed: at the
 * import line by {@link ImportAnalysisHandler}, or here; its value becomes the constant's value,
 * and a tree only the layout computes stays with the import line, the constant's value being its
 * own qualified name, under which the layout records the value the import line computes. A
 * default and a configured value that are computations are evaluated in the placement of the
 * requirement, whether or not they are the value the constant takes, as a name among them is
 * resolved here; the constant's value is the value or the tree only the layout computes. An error
 * in a default is reported at its line, an error in a configured value against the configuration,
 * naming it, without a file or line.
 */
public class RequireAnalysisHandler implements IAnalysisHandler {

    private final ComputationAnalysis computations;

    /**
     * Creates the handler.
     *
     * @param operations The operations of computations, read when the phase runs.
     */
    public RequireAnalysisHandler(OperationRegistry operations) {
        this.computations = new ComputationAnalysis(operations);
    }

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (node instanceof ValueBindingNode binding) {
            computeValue(binding, symbolTable, diagnostics);
            return;
        }
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
        // A default or a configured value that is a name is a use of that name here, bound or not
        for (AstNode given : new AstNode[]{valueNode.defaultValue(), valueNode.configured()}) {
            if (given instanceof IdentifierNode id
                    && symbolTable.resolve(id.text(), id.sourceInfo()) instanceof Resolution.Missing missing) {
                diagnostics.reportError(
                        "Cannot require value '" + valueNode.name() + "' from '" + id.text() + "': " + missing.explanation(),
                        at.fileName(), at.lineNumber());
            }
        }
        ValueBindingNode binding = symbolTable.resolve(valueNode.name(), at) instanceof ResolvedSymbol resolved
                && resolved.symbol().node() instanceof ValueBindingNode defined ? defined : null;
        if (binding == null) {
            throw new IllegalStateException("The value requirement '" + valueNode.name() + "' at " + SourceInfo.position(at)
                    + " defines no constant, which pass 1 should have defined.");
        }
        computeValue(binding, symbolTable, diagnostics);
        if (binding.value() == null && isRoot(symbolTable)) {
            diagnostics.reportError(
                    "The value '" + valueNode.name() + "' has no default and the configuration sets none; set it with values { "
                            + valueNode.name() + " = \"<value>\" } in the organism's entry or --value " + valueNode.name() + "=<value>.",
                    at.fileName(), at.lineNumber());
        }
    }

    /**
     * Computes the value of the constant behind a binding and records it for the binding; a
     * value that is no computation records nothing, as a use of the name follows it. The
     * computations are evaluated once, so running this a second time records the same value.
     */
    private void computeValue(ValueBindingNode binding, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        ValueRequirementNode valueNode = binding.requirement();
        SourceInfo at = valueNode.sourceInfo();
        boolean bound = symbolTable.getModuleScope(symbolTable.getCurrentAliasChain())
                .flatMap(scope -> scope.record(Requirements.class, Requirements::none).boundValue(valueNode.name().toUpperCase(Locale.ROOT)))
                .isPresent();
        ExpressionOutcome defaultOutcome = valueNode.defaultValue() instanceof ExpressionNode expression
                ? evaluate(expression, symbolTable, diagnostics, null) : null;
        ExpressionOutcome configuredOutcome = valueNode.configured() instanceof ExpressionNode expression
                ? evaluate(expression, symbolTable, diagnostics, valueNode) : null;
        ExpressionOutcome outcome = bound
                ? boundOutcome(binding.value(), QualifiedNames.join(symbolTable.getCurrentAliasChain(), valueNode.name()), at,
                        at.plusColumns(valueNode.name().length()), symbolTable, diagnostics)
                : valueNode.configured() != null ? configuredOutcome : defaultOutcome;
        if (outcome != null) {
            symbolTable.recordDefinitionValue(binding, outcome);
        }
    }

    /**
     * Returns the value a bound computation gives the constant: the value the import line
     * computes, or, for a tree only the layout computes, the constant's own qualified name with
     * the kind of that tree, standing from {@code at} to {@code end}, the written name. The
     * computation is evaluated where it is written, in the importer's placement, if the import
     * line has not evaluated it yet; its error is reported at its position either way. A binding
     * that is no computation, or whose computation failed, gives no recorded value.
     */
    private ExpressionOutcome boundOutcome(OperandNode bound, String qualifiedName, SourceInfo at, SourceInfo end,
                                           SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (!(bound instanceof ExpressionNode expression)) {
            return null;
        }
        return ComputationAnalysis.outcomeOf(computations.evaluate(expression, symbolTable, diagnostics))
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
        EvaluationResult result;
        if (configuredFor != null) {
            result = computations.evaluate(expression, symbolTable);
            if (result instanceof EvaluationResult.Error error) {
                diagnostics.reportError(ConfiguredValues.message(
                        configuredFor.name(), configuredFor.configuredText(), error.reason()), "", 0);
            }
        } else {
            result = computations.evaluate(expression, symbolTable, diagnostics);
        }
        ExpressionOutcome outcome = ComputationAnalysis.outcomeOf(result).orElse(null);
        return outcome;
    }

    private static boolean isRoot(SymbolTable symbolTable) {
        String root = symbolTable.rootAliasChain();
        return root != null && root.equals(symbolTable.getCurrentAliasChain());
    }
}
