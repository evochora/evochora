package org.evochora.compiler.features.label;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.frontend.semantics.analysis.ISymbolCollector;

/**
 * Collects label symbols during pass 1: defines the label symbol
 * and registers export metadata.
 */
public class LabelSymbolCollector implements ISymbolCollector {

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        LabelNode lbl = (LabelNode) node;
        symbolTable.define(new Symbol(lbl.name(), lbl.sourceInfo(), Symbol.Type.LABEL, null, lbl.exported()));
    }
}
