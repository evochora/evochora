package org.evochora.compiler.features.control;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Enters the level a control block opened in pass 1 before the block's children are analyzed,
 * so that the names inside the block resolve from the block's level, and leaves it afterwards.
 * The handler keeps no state.
 */
public class ControlAnalysisHandler implements IAnalysisHandler {

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
