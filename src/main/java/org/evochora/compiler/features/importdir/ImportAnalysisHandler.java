package org.evochora.compiler.features.importdir;

import java.util.Locale;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.expression.symbols.ComputationAnalysis;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.symbols.Resolution;
import org.evochora.compiler.model.symbols.ModuleScope;
import org.evochora.compiler.model.symbols.SymbolTable;

import java.util.Map;

/**
 * Pass-2 analysis handler for {@code .IMPORT} directives.
 *
 * <p>Validates USING clauses, against the aliases of the current placement and the
 * {@link Requirements} of the imported one, which pass 1 has completed:
 * <ul>
 *   <li>Each USING source must be an alias of the current placement: a module it imported, or
 *       one it received itself.</li>
 *   <li>Each USING target must be a module requirement of the imported placement.</li>
 *   <li>Every module requirement of the imported placement must be named as target by a USING
 *       clause of this import.</li>
 *   <li>Each USING VALUE name must be a value requirement of the imported placement.</li>
 *   <li>Every value requirement of the imported placement without a default must be bound by
 *       this import.</li>
 *   <li>A bound value that is a name must be defined in the current placement at this line; it
 *       is resolved here as a use.</li>
 * </ul>
 * <p>A bound value that is a computation is evaluated here, in the importer's placement, with the
 * names of the importer's level; {@code @} is the importer's cursor. Its outcome is recorded for
 * the computation, where {@link RequireAnalysisHandler} finds it through the binding the module's
 * {@link Requirements} carry: a value, or the tree only the layout computes. A computation that
 * fails is reported here. A use of the module's value that needed the value first had it
 * evaluated already, by {@link RequireAnalysisHandler}, which this line then finds recorded.
 */
public class ImportAnalysisHandler implements IAnalysisHandler {

    private final ComputationAnalysis computations;

    /**
     * Creates the handler.
     *
     * @param operations The operations of computations, read when the phase runs.
     */
    public ImportAnalysisHandler(OperationRegistry operations) {
        this.computations = new ComputationAnalysis(operations);
    }

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        ImportNode importNode = (ImportNode) node;
        String alias = importNode.alias().toUpperCase(Locale.ROOT);

        ModuleScope currentModScope = symbolTable.getModuleScope(symbolTable.getCurrentAliasChain()).orElse(null);
        if (currentModScope == null) {
            diagnostics.reportError(
                    "Internal error: no module scope registered for current module.",
                    importNode.sourceInfo().fileName(),
                    importNode.sourceInfo().lineNumber());
            return;
        }

        String importedAliasChain = currentModScope.aliases().get(alias);
        if (importedAliasChain == null) {
            diagnostics.reportError(
                    "Internal error: import alias '" + importNode.alias()
                            + "' is not registered in the module scope.",
                    importNode.sourceInfo().fileName(),
                    importNode.sourceInfo().lineNumber());
            return;
        }

        ModuleScope importedModScope = symbolTable.getModuleScope(importedAliasChain).orElse(null);
        Requirements record = importedModScope != null
                ? importedModScope.record(Requirements.class, Requirements::none)
                : Requirements.none();
        Map<String, String> requirements = record.moduleRequirements();
        Map<String, Boolean> valueRequirements = record.valueRequirements();

        for (ImportNode.UsingClause using : importNode.usings()) {
            String sourceAlias = using.sourceAlias().toUpperCase(Locale.ROOT);
            String targetAlias = using.targetAlias().toUpperCase(Locale.ROOT);

            // A USING source is either a module this one imported, or one it was given itself:
            // a requirement received from above can be handed on, so that the choice of what
            // finally arrives stays with the outermost caller.
            if (!currentModScope.aliases().containsKey(sourceAlias)) {
                diagnostics.reportError(
                        "USING source '" + using.sourceAlias()
                                + "' is neither an import nor a requirement of the current module.",
                        using.sourceSourceInfo().fileName(),
                        using.sourceSourceInfo().lineNumber());
            }

            // USING target must match a .REQUIRE in the imported module
            if (importedModScope != null && !requirements.containsKey(targetAlias)) {
                diagnostics.reportError(
                        "USING target '" + using.targetAlias()
                                + "' does not match any .REQUIRE in the imported module.",
                        using.targetSourceInfo().fileName(),
                        using.targetSourceInfo().lineNumber());
            }
        }

        // All .REQUIREs in the imported module must be satisfied
        if (importedModScope != null) {
            for (String requiredAlias : requirements.keySet()) {
                boolean satisfied = importNode.usings().stream()
                        .anyMatch(u -> u.targetAlias().equalsIgnoreCase(requiredAlias));
                if (!satisfied) {
                    diagnostics.reportError(
                            "Cannot import " + importNode.alias() + ": it requires '" + requiredAlias
                                    + "'; add USING MODULE <module> AS " + requiredAlias + " to the import.",
                            importNode.sourceInfo().fileName(),
                            importNode.sourceInfo().lineNumber());
                }
            }
        }

        for (ImportNode.ValueBinding binding : importNode.values()) {
            if (importedModScope != null && !valueRequirements.containsKey(binding.name().toUpperCase(Locale.ROOT))) {
                diagnostics.reportError(
                        "USING VALUE '" + binding.name()
                                + "' does not match any .REQUIRE VALUE in the imported module.",
                        binding.nameSourceInfo().fileName(),
                        binding.nameSourceInfo().lineNumber());
            }
            // A bound name is a constant of the importer, used at this line
            if (binding.value() instanceof IdentifierNode id
                    && symbolTable.resolve(id.text(), id.sourceInfo()) instanceof Resolution.Missing missing) {
                diagnostics.reportError(
                        "Cannot bind VALUE " + binding.name() + " from '" + id.text() + "': " + missing.explanation(),
                        importNode.sourceInfo().fileName(),
                        importNode.sourceInfo().lineNumber());
            }
            if (binding.value() instanceof ExpressionNode expression) {
                evaluate(expression, symbolTable, diagnostics);
            }
        }

        // Every value requirement without a default must be bound
        for (Map.Entry<String, Boolean> required : valueRequirements.entrySet()) {
            boolean bound = importNode.values().stream()
                    .anyMatch(v -> v.name().equalsIgnoreCase(required.getKey()));
            if (!required.getValue() && !bound) {
                diagnostics.reportError(
                        "Cannot import " + importNode.alias() + ": it requires the value '" + required.getKey()
                                + "'; add USING VALUE " + required.getKey() + " <value> to the import.",
                        importNode.sourceInfo().fileName(),
                        importNode.sourceInfo().lineNumber());
            }
        }
    }

    /**
     * Evaluates a bound computation and records its outcome for it; reports the error of a
     * computation that fails.
     */
    private void evaluate(ExpressionNode expression, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        computations.evaluate(expression, symbolTable, diagnostics);
    }
}
