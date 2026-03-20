package org.evochora.compiler.features.reg;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;
import org.evochora.runtime.Config;

/**
 * Handles the semantic analysis of .REG directives.
 * Its sole responsibility is to define register aliases in the symbol table.
 */
public class RegAnalysisHandler implements IAnalysisHandler {

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (node instanceof RegNode regNode) {
            processRegDirective(regNode, symbolTable, diagnostics);
        }
    }

    private void processRegDirective(RegNode regNode, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        String registerText = regNode.register();
        if (!isValidRegister(registerText)) {
            diagnostics.reportError(
                String.format("Invalid register '%s'. .REG directive supports data registers %%DR0-%%DR%d and location registers %%LR0-%%LR%d.",
                    registerText, Config.NUM_DATA_REGISTERS - 1, Config.NUM_LOCATION_REGISTERS - 1),
                regNode.sourceInfo().fileName(),
                regNode.sourceInfo().lineNumber()
            );
            return;
        }

        symbolTable.define(new Symbol(regNode.alias(), regNode.sourceInfo(), Symbol.Type.ALIAS));
    }

    /**
     * Validates that a register string represents a valid register for .REG directive.
     * Supports both data registers (%DRx) and location registers (%LRx).
     * @param registerText The register text to validate (e.g., "%DR0", "%LR3")
     * @return true if the register is valid, false otherwise
     */
    private boolean isValidRegister(String registerText) {
        if (registerText == null || !registerText.startsWith("%")) {
            return false;
        }

        if (registerText.length() < 4) {
            return false;
        }

        String registerType = registerText.substring(1, 3);

        try {
            int registerNumber = Integer.parseInt(registerText.substring(3));

            if (registerType.equals("DR")) {
                return registerNumber >= 0 && registerNumber < Config.NUM_DATA_REGISTERS;
            } else if (registerType.equals("LR")) {
                return registerNumber >= 0 && registerNumber < Config.NUM_LOCATION_REGISTERS;
            }

            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
