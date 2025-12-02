package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SymbolTable;

import java.util.Map;

/**
 * Opens a new scope for a procedure and defines its formal parameters as symbols
 * so that identifier operands (e.g., A, B) inside the body can be validated as register placeholders.
 */
public class ProcedureAnalysisHandler extends ScopeAnalysisHandler {

    /**
     * Constructs a new procedure analysis handler.
     * @param scopeMap The map to store the scope for each node.
     */
    public ProcedureAnalysisHandler(Map<AstNode, SymbolTable.Scope> scopeMap) {
        super(scopeMap);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        // Call the base class logic to enter the scope.
        super.analyze(node, symbolTable, diagnostics);
        // The parameters have already been defined in collectLabels, nothing more to do here.
    }
}


