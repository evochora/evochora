package org.evochora.compiler.features.require;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.ISymbolCollector;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Pass-1 symbol collector for {@code .REQUIRE} directives.
 *
 * <p>Registers the require alias as a symbol for conflict detection
 * (prevents labels, procedures, or constants from using the same name). The symbol is never
 * exported: a requirement is satisfied by the importer, so a path from outside never continues
 * through it. A requirement is a name of the module level: inside any level the symbol table has
 * opened, such as a procedure, the directive is reported and defines nothing.
 *
 * <p>The actual require relationship (alias → path) is registered by
 * {@code Compiler.setupModuleRelationships()} from the DependencyScanner's data.
 */
public class RequireSymbolCollector implements ISymbolCollector {

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        RequireNode requireNode = (RequireNode) node;
        if (symbolTable.getCurrentScope() != symbolTable.getRootScope()) {
            diagnostics.reportError(".REQUIRE may stand only at the module level.",
                    requireNode.sourceInfo().fileName(), requireNode.sourceInfo().lineNumber());
            return;
        }
        symbolTable.define(new Symbol(requireNode.alias(), requireNode.sourceInfo(), Symbol.Type.MODULE_ALIAS, requireNode))
                .ifPresent(existing -> diagnostics.reportError(
                        "Cannot require as '" + requireNode.alias() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                        requireNode.sourceInfo().fileName(), requireNode.sourceInfo().lineNumber()));
    }
}
