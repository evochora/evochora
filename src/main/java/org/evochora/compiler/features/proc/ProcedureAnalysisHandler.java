package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SymbolTable;

/**
 * Opens a new scope for a procedure and defines its formal parameters as symbols
 * so that identifier operands (e.g., A, B) inside the body can be validated as register placeholders.
 */
public class ProcedureAnalysisHandler implements IAnalysisHandler {

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        SymbolTable.Scope prebuiltScope = symbolTable.getNodeScope(node);
        if (prebuiltScope != null) {
            symbolTable.setCurrentScope(prebuiltScope);
        }
    }

    @Override
    public void afterChildren(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        symbolTable.leaveScope();
    }
}


