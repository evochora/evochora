package org.evochora.compiler.features.importdir;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Pass-2 analysis handler for {@code .REQUIRE} directives.
 *
 * <p>Reports a module requirement of the main program: nothing imports the main program, so
 * nothing can supply the module, whether or not the alias is used. A requirement of a module is
 * supplied through a {@code USING} clause of the importer's {@code .IMPORT}, which
 * {@link ImportAnalysisHandler} checks.
 */
public class RequireAnalysisHandler implements IAnalysisHandler {

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        RequireNode requireNode = (RequireNode) node;
        String root = symbolTable.rootAliasChain();
        if (root != null && root.equals(symbolTable.getCurrentAliasChain())) {
            diagnostics.reportError(
                    "The main program cannot require a module: nothing imports it, so nothing can supply '"
                            + requireNode.alias() + "'; import \"" + requireNode.path() + "\" instead.",
                    requireNode.sourceInfo().fileName(),
                    requireNode.sourceInfo().lineNumber());
        }
    }
}
