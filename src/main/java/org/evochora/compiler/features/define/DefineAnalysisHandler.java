package org.evochora.compiler.features.define;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;

/**
 * Handles the semantic analysis of {@link DefineNode}s.
 * This involves defining the constant in the symbol table.
 */
public class DefineAnalysisHandler implements IAnalysisHandler {
    /**
     * {@inheritDoc}
     */
    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (node instanceof DefineNode defineNode) {
            symbolTable.define(new Symbol(defineNode.name(), defineNode.sourceInfo(), Symbol.Type.CONSTANT, null, defineNode.exported()));
        }
    }
}
