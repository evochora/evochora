package org.evochora.compiler.features.importdir;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.ModuleScope;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Pass-2 analysis handler for {@code .IMPORT} directives.
 *
 * <p>Validates USING clauses:
 * <ul>
 *   <li>Each USING source must be an import of the current module, or a requirement it received itself.</li>
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

        // The EXPORT prefix is read twice from the same line: once by the dependency scanner,
        // whose result decides what the module actually re-exports, and once by the parser, whose
        // result reaches here on the node. Two descriptions of one syntax can drift apart, and a
        // drift would silently change which symbols a module passes on, so it is an error here.
        Boolean scannedAsExported = currentModScope.importExported().get(alias);
        if (scannedAsExported != null && scannedAsExported != importNode.exported()) {
            diagnostics.reportError(
                    "Internal error: the dependency scan and the parser disagree on whether import '"
                            + importNode.alias() + "' is exported.",
                    importNode.sourceInfo().fileName(),
                    importNode.sourceInfo().lineNumber());
            return;
        }

        ModuleScope importedModScope = symbolTable.getModuleScope(importedAliasChain).orElse(null);

        for (ImportNode.UsingClause using : importNode.usings()) {
            String sourceAlias = using.sourceAlias().toUpperCase();
            String targetAlias = using.targetAlias().toUpperCase();

            // A USING source is either a module this one imported, or one it was given itself:
            // a requirement received from above can be handed on, so that the choice of what
            // finally arrives stays with the outermost caller.
            if (!currentModScope.imports().containsKey(sourceAlias)
                    && !currentModScope.usingBindings().containsKey(sourceAlias)) {
                diagnostics.reportError(
                        "USING source '" + using.sourceAlias()
                                + "' is neither an import nor a requirement of the current module.",
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
