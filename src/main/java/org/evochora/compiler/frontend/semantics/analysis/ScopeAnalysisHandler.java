package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer; // Import hinzufügen
import org.evochora.compiler.frontend.semantics.SymbolTable;

import java.util.Map; // Import hinzufügen

public class ScopeAnalysisHandler implements IAnalysisHandler {

    private final Map<AstNode, SymbolTable.Scope> scopeMap;

    public ScopeAnalysisHandler(Map<AstNode, SymbolTable.Scope> scopeMap) {
        this.scopeMap = scopeMap;
    }

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        // Betritt den bereits im ersten Durchlauf erstellten Scope.
        SymbolTable.Scope prebuiltScope = scopeMap.get(node);
        if (prebuiltScope != null) {
            symbolTable.setCurrentScope(prebuiltScope);
        }
    }

    /**
     * Spezielle Methode, die aufgerufen wird, NACHDEM die Kinder eines Scopes analysiert wurden.
     * @param symbolTable Die zu verwendende Symboltabelle.
     */
    public void afterChildren(SymbolTable symbolTable) {
        symbolTable.leaveScope();
    }
}