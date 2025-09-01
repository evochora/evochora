package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.PregNode;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.runtime.Config;

/**
 * Semantic analysis handler for .PREG directives.
 * Validates procedure register aliases and adds them to the symbol table with proper scoping.
 */
public class PregAnalysisHandler implements IAnalysisHandler {

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (node instanceof PregNode pregNode) {
            processPregDirective(pregNode, symbolTable, diagnostics);
        }
    }

    /**
     * Processes a .PREG directive by validating the alias and adding it to the symbol table.
     *
     * @param pregNode the PregNode to process
     * @param symbolTable the symbol table to add the alias to
     * @param diagnostics the diagnostics engine for error reporting
     */
    private void processPregDirective(PregNode pregNode, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        // Validate the target procedure register
        if (!isValidProcedureRegister(pregNode.targetRegister())) {
            diagnostics.reportError(
                String.format("Invalid procedure register '%s'. Valid range: %%PR0-%%PR%d.", 
                    pregNode.targetRegister().text(), Config.NUM_PROC_REGISTERS - 1),
                pregNode.targetRegister().fileName(),
                pregNode.targetRegister().line()
            );
            return;
        }

        // Validate the alias name
        if (!isValidAliasName(pregNode.alias())) {
            diagnostics.reportError(
                "Invalid alias name '%s'. Alias names must be valid identifiers or register tokens.",
                pregNode.alias().fileName(),
                pregNode.alias().line()
            );
            return;
        }

        // Add the alias to the symbol table with ALIAS type
        // Create a token for the alias name
        org.evochora.compiler.frontend.lexer.Token aliasToken = new org.evochora.compiler.frontend.lexer.Token(
            pregNode.alias().type(),
            pregNode.alias().text(),
            pregNode.alias().value(),
            pregNode.alias().line(),
            pregNode.alias().column(),
            pregNode.alias().fileName()
        );
        
        Symbol aliasSymbol = new Symbol(aliasToken, Symbol.Type.ALIAS);
        symbolTable.define(aliasSymbol);
    }

    /**
     * Validates that the target register is a valid procedure register (%PR0 to %PR{NUM_PROC_REGISTERS-1}).
     *
     * @param targetRegister the register token to validate
     * @return true if the register is valid, false otherwise
     */
    private boolean isValidProcedureRegister(org.evochora.compiler.frontend.lexer.Token targetRegister) {
        if (targetRegister.type() != TokenType.REGISTER) {
            return false;
        }
        
        String registerText = targetRegister.text();
        if (!registerText.startsWith("%PR")) {
            return false;
        }
        
        try {
            int regNum = Integer.parseInt(registerText.substring(3));
            return regNum >= 0 && regNum < Config.NUM_PROC_REGISTERS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Validates that the alias name is acceptable.
     *
     * @param alias the alias token to validate
     * @return true if the alias is valid, false otherwise
     */
    private boolean isValidAliasName(org.evochora.compiler.frontend.lexer.Token alias) {
        // Alias can be IDENTIFIER or REGISTER token
        return alias.type() == TokenType.IDENTIFIER || alias.type() == TokenType.REGISTER;
    }
}
