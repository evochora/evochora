package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.importdir.ImportNode;
import org.evochora.compiler.frontend.semantics.ModuleId;
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
        String alias = importNode.alias().text().toUpperCase();

        ModuleScope currentModScope = symbolTable.getModuleScope(symbolTable.getCurrentModuleId()).orElse(null);
        if (currentModScope == null) {
            diagnostics.reportError(
                    "Internal error: no module scope registered for current module.",
                    importNode.alias().fileName(),
                    importNode.alias().line());
            return;
        }

        ModuleId importedModuleId = currentModScope.imports().get(alias);
        if (importedModuleId == null) {
            diagnostics.reportError(
                    "Import alias '" + importNode.alias().text()
                            + "' is not registered in the module scope.",
                    importNode.alias().fileName(),
                    importNode.alias().line());
            return;
        }

        ModuleScope importedModScope = symbolTable.getModuleScope(importedModuleId).orElse(null);

        for (ImportNode.UsingClause using : importNode.usings()) {
            String sourceAlias = using.sourceAlias().text().toUpperCase();
            String targetAlias = using.targetAlias().text().toUpperCase();

            // USING source must be a known import alias in the current module
            if (!currentModScope.imports().containsKey(sourceAlias)) {
                diagnostics.reportError(
                        "USING source '" + using.sourceAlias().text()
                                + "' is not a known import alias in the current module.",
                        using.sourceAlias().fileName(),
                        using.sourceAlias().line());
            }

            // USING target must match a .REQUIRE in the imported module
            if (importedModScope != null && !importedModScope.requires().containsKey(targetAlias)) {
                diagnostics.reportError(
                        "USING target '" + using.targetAlias().text()
                                + "' does not match any .REQUIRE in the imported module.",
                        using.targetAlias().fileName(),
                        using.targetAlias().line());
            }
        }

        // All .REQUIREs in the imported module must be satisfied
        if (importedModScope != null) {
            for (String requiredAlias : importedModScope.requires().keySet()) {
                boolean satisfied = importNode.usings().stream()
                        .anyMatch(u -> u.targetAlias().text().equalsIgnoreCase(requiredAlias));
                if (!satisfied) {
                    diagnostics.reportError(
                            "Imported module requires '" + requiredAlias
                                    + "' but no USING clause provides it.",
                            importNode.alias().fileName(),
                            importNode.alias().line());
                }
            }
        }
    }
}
