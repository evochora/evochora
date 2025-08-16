package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;

/**
 * Opens a new scope for a procedure and defines its formal parameters as symbols
 * so that identifier operands (e.g., A, B) inside the body can be validated as register placeholders.
 */
public class ProcedureAnalysisHandler extends ScopeAnalysisHandler {

	@Override
	public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
		// Enter procedure scope
		symbolTable.enterScope();
		if (node instanceof ProcedureNode proc && proc.parameters() != null) {
			for (Token p : proc.parameters()) {
				symbolTable.define(new Symbol(p, Symbol.Type.VARIABLE));
			}
		}
	}
}


