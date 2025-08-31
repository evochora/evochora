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
        if (!isValidDataRegister(registerText)) {
            diagnostics.reportError(
                String.format("Invalid register '%s'. .REG directive only supports data registers %%DR0-%%DR%d.", 
                    registerText, Config.NUM_DATA_REGISTERS - 1),
                regNode.register().fileName(),
                regNode.register().line()
            );
            return;
        }

        // Everything is valid, so define the alias in the symbol table
        symbolTable.define(new Symbol(regNode.alias(), Symbol.Type.ALIAS));
    }

    /**
     * Validates that a register string represents a valid data register for .REG directive.
     * @param registerText The register text to validate (e.g., "%DR0", "%DR7")
     * @return true if the register is a valid DR register, false otherwise
     */
    private boolean isValidDataRegister(String registerText) {
        if (registerText == null || !registerText.startsWith("%")) {
            return false;
        }
        
        String registerType = registerText.substring(1, 3); // Extract "DR"
        if (!registerType.equals("DR")) {
            return false;
        }
        
        try {
            int registerNumber = Integer.parseInt(registerText.substring(3));
            return registerNumber >= 0 && registerNumber < Config.NUM_DATA_REGISTERS; // Valid range: 0 to NUM_DATA_REGISTERS-1
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
