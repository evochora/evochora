package org.evochora.compiler.features.require;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.ModuleScope;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Pass-2 analysis handler for {@code .REQUIRE} directives.
 *
 * <p>Validates that the require declaration is properly formed. The actual satisfaction
 * of require dependencies (via USING clauses on the importer's .IMPORT) is validated
 * by {@link ImportAnalysisHandler}.
 */
public class RequireAnalysisHandler implements IAnalysisHandler {

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        RequireNode requireNode = (RequireNode) node;
        String alias = requireNode.alias().toUpperCase();

        String currentChain = symbolTable.getCurrentAliasChain();
        ModuleScope modScope = symbolTable.getModuleScope(currentChain).orElse(null);
        if (modScope == null) {
            diagnostics.reportError(
                    "Internal error: no module scope registered for current module.",
                    requireNode.sourceInfo().fileName(),
                    requireNode.sourceInfo().lineNumber());
            return;
        }

        if (!modScope.requires().containsKey(alias)) {
            diagnostics.reportError(
                    "Require alias '" + requireNode.alias()
                            + "' was not found in dependency scan results.",
                    requireNode.sourceInfo().fileName(),
                    requireNode.sourceInfo().lineNumber());
        }
    }
}
