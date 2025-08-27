package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.IdentifierNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;

import java.util.Optional;

/**
 * Handles the semantic analysis of {@link IdentifierNode}s.
 * This involves resolving the identifier in the symbol table and checking
 * for context-specific errors (e.g., using a constant as a jump target).
 */
public class IdentifierAnalysisHandler implements IAnalysisHandler {

    private final boolean isJumpContext; // Indicates whether the identifier is used as a jump target.

    /**
     * Constructs a new identifier analysis handler.
     * @param isJumpContext True if the identifier is used as a jump target, false otherwise.
     */
    public IdentifierAnalysisHandler(boolean isJumpContext) {
        this.isJumpContext = isJumpContext;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (!(node instanceof IdentifierNode identifierNode)) {
            return;
        }

        Optional<Symbol> symbolOpt = symbolTable.resolve(identifierNode.identifierToken());

        if (symbolOpt.isEmpty()) {
            diagnostics.reportError(
                    String.format("Symbol '%s' is not defined.", identifierNode.identifierToken().text()),
                    identifierNode.identifierToken().fileName(),
                    identifierNode.identifierToken().line()
            );
        } else {
            Symbol symbol = symbolOpt.get();
            // If we are in a jump context, the symbol must not be a CONSTANT.
            if (isJumpContext && symbol.type() == Symbol.Type.CONSTANT) {
                diagnostics.reportError(
                        String.format("Symbol '%s' is a CONSTANT and cannot be used as a jump target.", symbol.name().text()),
                        symbol.name().fileName(),
                        symbol.name().line()
                );
            }
        }
    }
}