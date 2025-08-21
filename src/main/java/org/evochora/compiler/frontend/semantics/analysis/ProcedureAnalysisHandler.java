package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;

import java.util.Map;

/**
 * Opens a new scope for a procedure and defines its formal parameters as symbols
 * so that identifier operands (e.g., A, B) inside the body can be validated as register placeholders.
 */
public class ProcedureAnalysisHandler extends ScopeAnalysisHandler {

    public ProcedureAnalysisHandler(Map<AstNode, SymbolTable.Scope> scopeMap) {
        super(scopeMap);
    }

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        // Rufe die Logik der Basisklasse auf, um den Scope zu betreten.
        super.analyze(node, symbolTable, diagnostics);
        // Die Parameter wurden bereits in collectLabels definiert, hier ist nichts mehr zu tun.
    }
}


