package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;

/**
 * Collects label symbols during pass 1: defines the label symbol
 * and registers export metadata.
 */
public class LabelSymbolCollector implements ISymbolCollector {

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        LabelNode lbl = (LabelNode) node;
        symbolTable.define(new Symbol(lbl.labelToken(), Symbol.Type.LABEL));
        symbolTable.registerLabelMeta(lbl.labelToken(), lbl.exported());
    }
}
