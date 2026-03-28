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
        RegisterBank bank = resolveRegisterBank(registerText);
        if (bank == null) {
            StringBuilder validRanges = new StringBuilder();
            for (RegisterBank b : RegisterBank.values()) {
                if (b.count > 0 && !b.isForbidden) {
                    if (!validRanges.isEmpty()) validRanges.append(", ");
                    validRanges.append(b.prefix).append("0-").append(b.prefix).append(b.count - 1);
                }
            }
            diagnostics.reportError(
                String.format("Invalid register '%s'. Valid registers: %s.", registerText, validRanges),
                regNode.sourceInfo().fileName(),
                regNode.sourceInfo().lineNumber()
            );
            return;
        }

        Symbol.Type aliasType = bank.isLocation ? Symbol.Type.REGISTER_ALIAS_LOCATION : Symbol.Type.REGISTER_ALIAS_DATA;
        symbolTable.define(new Symbol(regNode.alias(), regNode.sourceInfo(), aliasType, regNode));
    }

    /**
     * Resolves a register string to its {@link RegisterBank}, validating syntax, bounds, and
     * forbidden status. Returns {@code null} if the register is invalid.
     *
     * @param registerText the register text to resolve (e.g., "%DR0", "%PDR2", "%LR3")
     * @return the matching bank, or {@code null} if invalid
     */
    private RegisterBank resolveRegisterBank(String registerText) {
        if (registerText == null || !registerText.startsWith("%")) {
            return null;
        }
        String upper = registerText.toUpperCase();
        try {
            for (RegisterBank bank : RegisterBank.values()) {
                if (bank.count > 0 && !bank.isForbidden && upper.startsWith(bank.prefix)) {
                    int index = Integer.parseInt(upper.substring(bank.prefixLength));
                    if (index >= 0 && index < bank.count) {
                        return bank;
                    }
                    return null;
                }
            }
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
