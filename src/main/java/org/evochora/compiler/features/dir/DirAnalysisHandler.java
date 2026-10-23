package org.evochora.compiler.features.dir;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.expression.symbols.LayoutArguments;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Handles the semantic analysis of {@link DirNode}s: evaluates a written-out direction with the
 * names defined above the line and checks that its value is a vector, or a number, a vector of
 * one component. A value only the layout
 * knows passes with the kind it will have; a rotation has numbers the parser read.
 */
public class DirAnalysisHandler implements IAnalysisHandler {

    private final LayoutArguments arguments;

    /**
     * Creates the handler.
     *
     * @param operations The operations of computations, read when the phase runs.
     */
    public DirAnalysisHandler(OperationRegistry operations) {
        this.arguments = new LayoutArguments(operations);
    }

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (((DirNode) node).mode() instanceof DirNode.Mode.Absolute absolute) {
            arguments.expectVector(absolute.vector(), false, symbolTable, diagnostics);
        }
    }
}
