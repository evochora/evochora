package org.evochora.compiler.features.control;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.ISymbolCollector;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Collects the names of a control block during pass 1. The block's name is a label on the level
 * around the block; the block then opens a level of its own under that name, registered for its
 * node, and defines the label {@code END} on it before any name written inside the block. The
 * level is left after the block's children. A block name defined twice on one level is reported
 * at the second definition, naming the first. The collector keeps no state.
 */
public class ControlSymbolCollector implements ISymbolCollector {

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        ControlNode block = (ControlNode) node;
        symbolTable.define(new Symbol(block.name(), block.sourceInfo(), Symbol.Type.LABEL, block, block.exported()))
                .ifPresent(existing -> diagnostics.reportError(
                        "Cannot define block '" + block.name() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                        block.sourceInfo().fileName(), block.sourceInfo().lineNumber()));

        SymbolTable.Scope scope = symbolTable.enterScope(block.name());
        symbolTable.registerNodeScope(node, scope);

        symbolTable.define(new Symbol("END", block.endSourceInfo(), Symbol.Type.LABEL,
                        new ControlEnd(block.endSourceInfo()), block.endExported()))
                // The level was opened two statements above and holds nothing yet, so END cannot
                // clash with a name a program wrote; a clash would be a defect of the compiler.
                .ifPresent(existing -> diagnostics.reportError(
                        "Internal error: END is already defined in the level of block '" + block.name() + "' that was just opened.",
                        block.endSourceInfo().fileName(), block.endSourceInfo().lineNumber()));
    }

    @Override
    public void collectAfterChildren(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        symbolTable.leaveScope();
    }
}
