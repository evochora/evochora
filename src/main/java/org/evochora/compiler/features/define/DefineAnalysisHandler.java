package org.evochora.compiler.features.define;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;

/**
 * Handles the semantic analysis of {@link DefineNode}s.
 * This involves defining the constant in the symbol table, with the node itself as the
 * symbol's node, so that a reference to the constant can be replaced by its value.
 */
public class DefineAnalysisHandler implements IAnalysisHandler {
    /**
     * {@inheritDoc}
     */
    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (node instanceof DefineNode defineNode) {
            symbolTable.define(new Symbol(defineNode.name(), defineNode.sourceInfo(), Symbol.Type.CONSTANT, defineNode, defineNode.exported()))
                    .ifPresent(existing -> diagnostics.reportError(
                            "Cannot define constant '" + defineNode.name() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                            defineNode.sourceInfo().fileName(), defineNode.sourceInfo().lineNumber()));
        }
    }
}
