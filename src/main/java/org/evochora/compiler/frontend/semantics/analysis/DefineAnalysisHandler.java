package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.def.DefineNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;

public class DefineAnalysisHandler implements IAnalysisHandler {
    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (node instanceof DefineNode defineNode) {
            // Füge die Konstante zur Symboltabelle hinzu.
            symbolTable.define(new Symbol(defineNode.name(), Symbol.Type.CONSTANT));
        }
    }
}