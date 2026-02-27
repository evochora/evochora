package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.require.RequireNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;

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
        symbolTable.define(new Symbol(requireNode.alias(), Symbol.Type.ALIAS, requireNode));
    }
}
