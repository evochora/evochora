package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.require.RequireNode;
import org.evochora.compiler.frontend.semantics.ModuleId;
import org.evochora.compiler.frontend.semantics.ModuleScope;
import org.evochora.compiler.frontend.semantics.SymbolTable;

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
        String alias = requireNode.alias().text().toUpperCase();

        ModuleId currentModule = symbolTable.getCurrentModuleId();
        ModuleScope modScope = symbolTable.getModuleScope(currentModule).orElse(null);
        if (modScope == null) {
            diagnostics.reportError(
                    "Internal error: no module scope registered for current module.",
                    requireNode.alias().fileName(),
                    requireNode.alias().line());
            return;
        }

        if (!modScope.requires().containsKey(alias)) {
            diagnostics.reportError(
                    "Require alias '" + requireNode.alias().text()
                            + "' was not found in dependency scan results.",
                    requireNode.alias().fileName(),
                    requireNode.alias().line());
        }
    }
}
