package org.evochora.compiler.features.control;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.ISymbolCollector;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Collects the name of a case of a control block during pass 1: a label on the block's level,
 * defined when the walk reaches the case, so that names are defined in text order and a clash
 * with a name written earlier in the block is reported at the case. The collector keeps no state.
 */
public class ControlCaseSymbolCollector implements ISymbolCollector {

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        ControlCase controlCase = (ControlCase) node;
        symbolTable.define(new Symbol(controlCase.name(), controlCase.sourceInfo(), Symbol.Type.LABEL, controlCase,
                        controlCase.exported()))
                .ifPresent(existing -> diagnostics.reportError(
                        "Cannot define case '" + controlCase.name() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                        controlCase.sourceInfo().fileName(), controlCase.sourceInfo().lineNumber()));
    }
}
