package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.reg.RegNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
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
        // Validate that the target is actually a register
        if (regNode.register().type() != org.evochora.compiler.frontend.lexer.TokenType.REGISTER) {
            diagnostics.reportError(
                "Target in .REG directive must be a register.",
                regNode.register().fileName(),
                regNode.register().line()
            );
            return;
        }

        // Validate register bounds and format
        String registerText = regNode.register().text();
        if (!isValidRegister(registerText)) {
            diagnostics.reportError(
                String.format("Invalid register '%s'. .REG directive supports data registers %%DR0-%%DR%d and location registers %%LR0-%%LR%d.", 
                    registerText, Config.NUM_DATA_REGISTERS - 1, Config.NUM_LOCATION_REGISTERS - 1),
                regNode.register().fileName(),
                regNode.register().line()
            );
            return;
        }

        // Everything is valid, so define the alias in the symbol table
        symbolTable.define(new Symbol(regNode.alias(), Symbol.Type.ALIAS));
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
        
        if (registerText.length() < 4) { // Minimum: %DR0 or %LR0
            return false;
        }
        
        String registerType = registerText.substring(1, 3); // Extract "DR" or "LR"
        
        try {
            int registerNumber = Integer.parseInt(registerText.substring(3));
            
            if (registerType.equals("DR")) {
                return registerNumber >= 0 && registerNumber < Config.NUM_DATA_REGISTERS;
            } else if (registerType.equals("LR")) {
                return registerNumber >= 0 && registerNumber < Config.NUM_LOCATION_REGISTERS;
            }
            
            return false; // Unknown register type
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
