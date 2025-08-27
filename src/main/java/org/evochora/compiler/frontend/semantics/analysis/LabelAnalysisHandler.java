package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.semantics.SymbolTable;

/**
 * Handles the semantic analysis of {@link org.evochora.compiler.frontend.parser.features.label.LabelNode}s.
 * This is a no-op because labels are collected in a separate pass before the main analysis.
 */
public class LabelAnalysisHandler implements IAnalysisHandler {

    /**
     * {@inheritDoc}
     * <p>
     * This implementation does nothing.
     */
    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        // No-op: Labels are collected in a dedicated first pass to support forward references
    }
}