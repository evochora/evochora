package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.proc.PregNode;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.runtime.Config;

public class PregAnalysisHandler implements IAnalysisHandler {
    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (node instanceof PregNode pregNode) {
            int index = (int) pregNode.index().value();
            if (index < 0 || index >= Config.NUM_PROC_REGISTERS) {
                diagnostics.reportError(
                        "Invalid index '" + index + "' for .PREG directive. Must be between 0 and " + (Config.NUM_PROC_REGISTERS - 1) + ".",
                        pregNode.index().fileName(),
                        pregNode.index().line()
                );
            }
        }
    }
}