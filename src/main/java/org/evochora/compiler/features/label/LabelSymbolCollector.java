package org.evochora.compiler.features.label;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.frontend.semantics.ISymbolCollector;

/**
 * Collects label symbols during pass 1: defines the label symbol
 * and registers export metadata.
 */
public class LabelSymbolCollector implements ISymbolCollector {

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        LabelNode lbl = (LabelNode) node;
        symbolTable.define(new Symbol(lbl.name(), lbl.sourceInfo(), Symbol.Type.LABEL, lbl, lbl.exported()))
                .ifPresent(existing -> diagnostics.reportError(
                        "Cannot define label '" + lbl.name() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                        lbl.sourceInfo().fileName(), lbl.sourceInfo().lineNumber()));
    }
}
