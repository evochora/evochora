package org.evochora.compiler.features.require;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.analysis.ISymbolCollector;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Pass-1 symbol collector for {@code .REQUIRE} directives.
 *
 * <p>Registers the require alias as a symbol for conflict detection
 * (prevents labels, procedures, or constants from using the same name).
 *
 * <p>The actual require relationship (alias → path) is registered by
 * {@code Compiler.setupModuleRelationships()} from the DependencyScanner's data.
 */
public class RequireSymbolCollector implements ISymbolCollector {

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        RequireNode requireNode = (RequireNode) node;
        symbolTable.define(new Symbol(requireNode.alias(), requireNode.sourceInfo(), Symbol.Type.ALIAS, requireNode));
    }
}
