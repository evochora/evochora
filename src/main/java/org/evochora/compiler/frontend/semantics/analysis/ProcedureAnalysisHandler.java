package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SymbolTable;

import java.util.Map;

/**
 * Opens a new scope for a procedure and defines its formal parameters as symbols
 * so that identifier operands (e.g., A, B) inside the body can be validated as register placeholders.
 */
public class ProcedureAnalysisHandler implements IAnalysisHandler {

    private final Map<AstNode, SymbolTable.Scope> scopeMap;

    /**
     * Constructs a new procedure analysis handler.
     * @param scopeMap The map to store the scope for each node.
     */
    public ProcedureAnalysisHandler(Map<AstNode, SymbolTable.Scope> scopeMap) {
        this.scopeMap = scopeMap;
    }

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
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


