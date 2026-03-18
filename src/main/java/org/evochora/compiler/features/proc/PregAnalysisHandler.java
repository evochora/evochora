package org.evochora.compiler.features.proc;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.frontend.semantics.Symbol;

/**
 * Semantic analysis handler for .PREG directives.
 * Adds procedure register aliases to the symbol table with proper scoping.
 * Token-type validation (register format, alias type) is handled at parse time
 * by {@link PregDirectiveHandler}.
 */
public class PregAnalysisHandler implements IAnalysisHandler {

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (node instanceof PregNode pregNode) {
            symbolTable.define(new Symbol(pregNode.alias(), pregNode.sourceInfo(), Symbol.Type.ALIAS));
        }
    }
}
