package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SymbolTable;

import java.util.Map;

/**
 * Collects scope boundaries during pass 1: enters a new scope
 * and records the scope mapping for the analysis pass.
 */
public class ScopeSymbolCollector implements ISymbolCollector {

    private final Map<AstNode, SymbolTable.Scope> scopeMap;

    public ScopeSymbolCollector(Map<AstNode, SymbolTable.Scope> scopeMap) {
        this.scopeMap = scopeMap;
    }

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        SymbolTable.Scope newScope = symbolTable.enterScope();
        scopeMap.put(node, newScope);
    }

    @Override
    public void collectAfterChildren(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        symbolTable.leaveScope();
    }
}
