package org.evochora.compiler.features.reg;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;
import org.evochora.runtime.isa.RegisterBank;

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
            StringBuilder validRanges = new StringBuilder();
            for (RegisterBank bank : RegisterBank.values()) {
                if (bank.count > 0 && !bank.isForbidden) {
                    if (!validRanges.isEmpty()) validRanges.append(", ");
                    validRanges.append(bank.prefix).append("0-").append(bank.prefix).append(bank.count - 1);
                }
            }
            diagnostics.reportError(
                String.format("Invalid register '%s'. Valid registers: %s.", registerText, validRanges),
                regNode.sourceInfo().fileName(),
                regNode.sourceInfo().lineNumber()
            );
            return;
        }

        symbolTable.define(new Symbol(regNode.alias(), regNode.sourceInfo(), Symbol.Type.ALIAS, regNode));
    }

    /**
     * Validates that a register string represents a valid register with an in-bounds index.
     * This is defense-in-depth validation at analysis time — the primary validation happens
     * at parse time in {@code RegDirectiveHandler}. This method also validates register references
     * in instructions (e.g., {@code ADDI %PDR99 DATA:1}).
     *
     * @param registerText the register text to validate (e.g., "%DR0", "%PDR2", "%FDR1", "%LR3")
     * @return {@code true} if the register is syntactically valid and within bounds
     */
    private boolean isValidRegister(String registerText) {
        if (registerText == null || !registerText.startsWith("%")) {
            return false;
        }
        String upper = registerText.toUpperCase();
        try {
            for (RegisterBank bank : RegisterBank.values()) {
                if (bank.count > 0 && upper.startsWith(bank.prefix)) {
                    int index = Integer.parseInt(upper.substring(bank.prefixLength));
                    return index >= 0 && index < bank.count;
                }
            }
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
