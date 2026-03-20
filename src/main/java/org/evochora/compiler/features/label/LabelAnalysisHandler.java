package org.evochora.compiler.features.label;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;

/**
 * Handles the semantic analysis of {@link LabelNode}s.
 * This is a no-op because labels are collected in a separate pass before the main analysis.
 */
public class LabelAnalysisHandler implements IAnalysisHandler {

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        // No-op: Labels are collected in a dedicated first pass to support forward references
    }
}
