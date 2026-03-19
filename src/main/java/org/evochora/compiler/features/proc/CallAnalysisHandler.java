package org.evochora.compiler.features.proc;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.frontend.semantics.ResolvedSymbol;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;

import java.util.Optional;

/**
 * Semantic analysis handler for CALL instructions.
 * Validates procedure references and REF/VAL/legacy argument types and counts.
 */
public class CallAnalysisHandler implements IAnalysisHandler {

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (!(node instanceof CallNode callNode)) return;

        // New syntax: REF/VAL arguments
        if (!callNode.refArguments().isEmpty() || !callNode.valArguments().isEmpty()) {
            analyzeNewSyntax(callNode, symbolTable, diagnostics);
        } else {
            analyzeLegacySyntax(callNode, symbolTable, diagnostics);
        }
    }

    private void analyzeNewSyntax(CallNode callNode, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (!(callNode.procedureName() instanceof IdentifierNode procIdentifier)) {
            diagnostics.reportError("CALL with REF/VAL requires a procedure name.",
                    callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
            return;
        }

        Optional<ResolvedSymbol> procSymbolOpt = symbolTable.resolve(procIdentifier.text(), procIdentifier.sourceInfo().fileName());
        if (procSymbolOpt.isEmpty() || procSymbolOpt.get().symbol().type() != Symbol.Type.PROCEDURE) {
            diagnostics.reportError("Procedure '" + procIdentifier.text() + "' not found or is not a procedure.",
                    procIdentifier.sourceInfo().fileName(), procIdentifier.sourceInfo().lineNumber());
            return;
        }

        Symbol procSymbol = procSymbolOpt.get().symbol();
        if (!(procSymbol.node() instanceof ProcedureNode procedureNode)) {
            diagnostics.reportError("Internal error: Symbol for procedure '" + procIdentifier.text() + "' does not contain a valid ProcedureNode.",
                    procIdentifier.sourceInfo().fileName(), procIdentifier.sourceInfo().lineNumber());
            return;
        }

        // Validate argument counts
        if (callNode.refArguments().size() != procedureNode.refParameters().size()) {
            diagnostics.reportError(String.format("Procedure '%s' expects %d REF argument(s), but received %d.",
                    procedureNode.name(), procedureNode.refParameters().size(), callNode.refArguments().size()),
                    callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
        }
        if (callNode.valArguments().size() != procedureNode.valParameters().size()) {
            diagnostics.reportError(String.format("Procedure '%s' expects %d VAL argument(s), but received %d.",
                    procedureNode.name(), procedureNode.valParameters().size(), callNode.valArguments().size()),
                    callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
        }

        // Validate REF argument types
        for (AstNode refArg : callNode.refArguments()) {
            if (refArg instanceof RegisterNode) continue;
            if (refArg instanceof IdentifierNode) continue;
            diagnostics.reportError("REF arguments must be registers.",
                    callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
        }

        // Validate VAL argument types
        for (AstNode valArg : callNode.valArguments()) {
            if (valArg instanceof RegisterNode) continue;
            if (valArg instanceof NumberLiteralNode) continue;
            if (valArg instanceof TypedLiteralNode) continue;
            if (valArg instanceof IdentifierNode) continue;
            diagnostics.reportError("VAL arguments must be registers, literals, or labels.",
                    callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
        }
    }

    private void analyzeLegacySyntax(CallNode callNode, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        // Validation of legacy actuals: allow registers or formal parameter names
        int withIdx = -1;
        for (int i = 0; i < callNode.legacyArguments().size(); i++) {
            AstNode a = callNode.legacyArguments().get(i);
            if (a instanceof IdentifierNode id) {
                String t = id.text().toUpperCase();
                if ("WITH".equals(t) || ".WITH".equals(t)) {
                    withIdx = i;
                    break;
                }
            }
        }
        // Do not allow additional tokens between target and WITH
        int unexpectedEnd = withIdx >= 0 ? withIdx : callNode.legacyArguments().size();
        if (unexpectedEnd > 0) {
            // Legacy arguments after proc name — check for stray tokens before WITH
            // (In CallNode, legacyArguments doesn't include the proc name)
        }
        int actualsStart = withIdx >= 0 ? withIdx + 1 : 0;
        for (int j = actualsStart; j < callNode.legacyArguments().size(); j++) {
            AstNode arg = callNode.legacyArguments().get(j);
            if (arg instanceof RegisterNode) continue;
            if (arg instanceof IdentifierNode id) {
                var res = symbolTable.resolve(id.text(), id.sourceInfo().fileName());
                if (res.isPresent() && (res.get().symbol().type() == Symbol.Type.VARIABLE || res.get().symbol().type() == Symbol.Type.ALIAS))
                    continue;
            }
            diagnostics.reportError("CALL actuals must be registers or parameter names.",
                    callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
            return;
        }
    }
}
