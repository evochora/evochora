package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.def.DefineNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;

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
            // Add the constant to the symbol table.
            symbolTable.define(new Symbol(defineNode.name(), Symbol.Type.CONSTANT));
        }
    }
}