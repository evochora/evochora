package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SymbolTable;

import java.util.Map;

/**
 * Handles the semantic analysis of scope-defining nodes (e.g., .SCOPE, .PROC).
 * It manages entering and leaving scopes in the symbol table.
 */
public class ScopeAnalysisHandler implements IAnalysisHandler {

    private final Map<AstNode, SymbolTable.Scope> scopeMap;

    /**
     * Constructs a new scope analysis handler.
     * @param scopeMap The map to store the scope for each node.
     */
    public ScopeAnalysisHandler(Map<AstNode, SymbolTable.Scope> scopeMap) {
        this.scopeMap = scopeMap;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        // Enters the scope already created in the first pass.
        SymbolTable.Scope prebuiltScope = scopeMap.get(node);
        if (prebuiltScope != null) {
            symbolTable.setCurrentScope(prebuiltScope);
        }
    }

    @Override
    public void afterChildren(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        symbolTable.leaveScope();
    }
}