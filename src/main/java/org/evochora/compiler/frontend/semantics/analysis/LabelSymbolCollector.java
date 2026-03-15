package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.token.Token;
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
        Token t = lbl.labelToken();
        symbolTable.define(new Symbol(t.text(), new SourceInfo(t.fileName(), t.line(), t.column()), Symbol.Type.LABEL, null, lbl.exported()));
    }
}
