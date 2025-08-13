package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SymbolTable;

public class ScopeAnalysisHandler implements IAnalysisHandler {

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        // Dieser Handler wird aufgerufen, BEVOR in die Kinder des Knotens abgestiegen wird.
        symbolTable.enterScope();
    }

    /**
     * Spezielle Methode, die aufgerufen wird, NACHDEM die Kinder eines Scopes analysiert wurden.
     * @param symbolTable Die zu verwendende Symboltabelle.
     */
    public void afterChildren(SymbolTable symbolTable) {
        symbolTable.leaveScope();
    }
}