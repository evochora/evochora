package org.evochora.compiler.features.importdir;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.frontend.semantics.ModuleScope;
import org.evochora.compiler.frontend.semantics.SymbolTable;

/**
 * Pass-2 analysis handler for {@code .IMPORT} directives.
 *
 * <p>Validates USING clauses:
 * <ul>
 *   <li>Each USING source must be a known import alias in the current module.</li>
 *   <li>Each USING target must correspond to a {@code .REQUIRE} declaration in the imported module.</li>
 *   <li>All {@code .REQUIRE} declarations in the imported module must be satisfied by USING clauses.</li>
 * </ul>
 */
public class ImportAnalysisHandler implements IAnalysisHandler {

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        ImportNode importNode = (ImportNode) node;
        String alias = importNode.alias().toUpperCase();

        ModuleScope currentModScope = symbolTable.getModuleScope(symbolTable.getCurrentAliasChain()).orElse(null);
        if (currentModScope == null) {
            diagnostics.reportError(
                    "Internal error: no module scope registered for current module.",
                    importNode.sourceInfo().fileName(),
                    importNode.sourceInfo().lineNumber());
            return;
        }

        String importedAliasChain = currentModScope.imports().get(alias);
        if (importedAliasChain == null) {
            diagnostics.reportError(
                    "Import alias '" + importNode.alias()
                            + "' is not registered in the module scope.",
                    importNode.sourceInfo().fileName(),
                    importNode.sourceInfo().lineNumber());
            return;
        }

        ModuleScope importedModScope = symbolTable.getModuleScope(importedAliasChain).orElse(null);

        for (ImportNode.UsingClause using : importNode.usings()) {
            String sourceAlias = using.sourceAlias().toUpperCase();
            String targetAlias = using.targetAlias().toUpperCase();

            // USING source must be a known import alias in the current module
            if (!currentModScope.imports().containsKey(sourceAlias)) {
                diagnostics.reportError(
                        "USING source '" + using.sourceAlias()
                                + "' is not a known import alias in the current module.",
                        using.sourceSourceInfo().fileName(),
                        using.sourceSourceInfo().lineNumber());
            }

            // USING target must match a .REQUIRE in the imported module
            if (importedModScope != null && !importedModScope.requires().containsKey(targetAlias)) {
                diagnostics.reportError(
                        "USING target '" + using.targetAlias()
                                + "' does not match any .REQUIRE in the imported module.",
                        using.targetSourceInfo().fileName(),
                        using.targetSourceInfo().lineNumber());
            }
        }

        // All .REQUIREs in the imported module must be satisfied
        if (importedModScope != null) {
            for (String requiredAlias : importedModScope.requires().keySet()) {
                boolean satisfied = importNode.usings().stream()
                        .anyMatch(u -> u.targetAlias().equalsIgnoreCase(requiredAlias));
                if (!satisfied) {
                    diagnostics.reportError(
                            "Imported module requires '" + requiredAlias
                                    + "' but no USING clause provides it.",
                            importNode.sourceInfo().fileName(),
                            importNode.sourceInfo().lineNumber());
                }
            }
        }
    }
}
