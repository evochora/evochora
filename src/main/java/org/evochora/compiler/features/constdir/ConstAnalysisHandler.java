package org.evochora.compiler.features.constdir;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.symbols.Resolution;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;

/**
 * Handles the semantic analysis of {@link ConstNode}s.
 * This involves defining the constant in the symbol table, with the node itself as the
 * symbol's node, so that a reference to the constant can be replaced by its value, and checking
 * that a name given as the value is defined at this line. The value itself is followed at a use
 * of the constant.
 */
public class ConstAnalysisHandler implements IAnalysisHandler {
    /**
     * {@inheritDoc}
     */
    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (node instanceof ConstNode constNode) {
            if (constNode.value() instanceof IdentifierNode id
                    && symbolTable.resolve(id.text(), id.sourceInfo()) instanceof Resolution.Missing missing) {
                diagnostics.reportError(
                        "Cannot define constant '" + constNode.name() + "' from '" + id.text() + "': " + missing.explanation(),
                        constNode.sourceInfo().fileName(), constNode.sourceInfo().lineNumber());
            }
            // Defined even when its value is missing, so that a use of it reports nothing of its own
            symbolTable.define(new Symbol(constNode.name(), constNode.sourceInfo(), Symbol.Type.CONSTANT, constNode, constNode.exported()))
                    .ifPresent(existing -> diagnostics.reportError(
                            "Cannot define constant '" + constNode.name() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                            constNode.sourceInfo().fileName(), constNode.sourceInfo().lineNumber()));
        }
    }
}
