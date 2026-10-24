package org.evochora.compiler.features.org;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.expression.symbols.LayoutArguments;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Handles the semantic analysis of {@link OrgNode}s: evaluates every component with the names
 * of the line's level and checks the kind of its value. A directive of one piece gives the
 * whole origin and has to be a vector, a number, which is a vector of one component, or, unmarked,
 * a position; each piece of a longer list is a number. A value only the layout knows passes with the kind it will have.
 */
public class OrgAnalysisHandler implements IAnalysisHandler {

    private final LayoutArguments arguments;

    /**
     * Creates the handler.
     *
     * @param operations The operations of computations, read when the phase runs.
     */
    public OrgAnalysisHandler(OperationRegistry operations) {
        this.arguments = new LayoutArguments(operations);
    }

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        OrgNode org = (OrgNode) node;
        if (org.components().size() == 1) {
            arguments.expectVector(org.components().getFirst(), !org.relative().getFirst(), symbolTable, diagnostics);
            return;
        }
        org.components().forEach(component -> arguments.expectNumber(component, symbolTable, diagnostics));
    }
}
