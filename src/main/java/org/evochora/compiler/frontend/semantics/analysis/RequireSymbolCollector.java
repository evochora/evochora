package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.token.Token;
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
        Token t = requireNode.alias();
        symbolTable.define(new Symbol(t.text(), new SourceInfo(t.fileName(), t.line(), t.column()), Symbol.Type.ALIAS, requireNode));
    }
}
