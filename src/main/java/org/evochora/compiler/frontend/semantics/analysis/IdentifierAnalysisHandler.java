package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.IdentifierNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;

import java.util.Optional;

public class IdentifierAnalysisHandler implements IAnalysisHandler {

    private final boolean isJumpContext; // Gibt an, ob der Identifier als Sprungziel verwendet wird.

    public IdentifierAnalysisHandler(boolean isJumpContext) {
        this.isJumpContext = isJumpContext;
    }

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (!(node instanceof IdentifierNode identifierNode)) {
            return;
        }

        Optional<Symbol> symbolOpt = symbolTable.resolve(identifierNode.identifierToken());

        if (symbolOpt.isEmpty()) {
            diagnostics.reportError(
                    String.format("Symbol '%s' is not defined.", identifierNode.identifierToken().text()),
                    "Unknown",
                    identifierNode.identifierToken().line()
            );
        } else {
            Symbol symbol = symbolOpt.get();
            // Wenn wir in einem Sprung-Kontext sind, darf das Symbol kein CONSTANT sein.
            if (isJumpContext && symbol.type() == Symbol.Type.CONSTANT) {
                diagnostics.reportError(
                        String.format("Symbol '%s' is a CONSTANT and cannot be used as a jump target.", symbol.name().text()),
                        "Unknown",
                        symbol.name().line()
                );
            }
        }
    }
}