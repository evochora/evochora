package org.evochora.compiler.features.reg;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.isa.IInstructionSet.RegisterBankInfo;

/**
 * Handles the semantic analysis of .REG directives.
 * Its sole responsibility is to define register aliases in the symbol table.
 */
public class RegAnalysisHandler implements IAnalysisHandler {

    private final IInstructionSet isa;

    /**
     * @param isa The instruction set, whose register banks decide what a register text names.
     */
    public RegAnalysisHandler(IInstructionSet isa) {
        this.isa = isa;
    }

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (node instanceof RegNode regNode) {
            processRegDirective(regNode, symbolTable, diagnostics);
        }
    }

    private void processRegDirective(RegNode regNode, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        String registerText = regNode.register();
        RegisterBankInfo bank = resolveRegisterBank(registerText);
        if (bank == null) {
            StringBuilder validRanges = new StringBuilder();
            for (RegisterBankInfo b : isa.registerBanks()) {
                if (b.count() > 0 && !b.forbidden()) {
                    if (!validRanges.isEmpty()) validRanges.append(", ");
                    validRanges.append(b.prefix()).append("0-").append(b.prefix()).append(b.count() - 1);
                }
            }
            diagnostics.reportError(
                String.format("Invalid register '%s'. Valid registers: %s.", registerText, validRanges),
                regNode.sourceInfo().fileName(),
                regNode.sourceInfo().lineNumber()
            );
            return;
        }

        Symbol.Type aliasType = bank.location() ? Symbol.Type.REGISTER_ALIAS_LOCATION : Symbol.Type.REGISTER_ALIAS_DATA;
        symbolTable.define(new Symbol(regNode.alias(), regNode.sourceInfo(), aliasType, regNode));
    }

    /**
     * Resolves a register string to its bank, validating syntax, bounds, and
     * forbidden status. Returns {@code null} if the register is invalid.
     *
     * @param registerText the register text to resolve (e.g., "%DR0", "%PDR2", "%LR3")
     * @return the matching bank, or {@code null} if invalid
     */
    private RegisterBankInfo resolveRegisterBank(String registerText) {
        if (registerText == null || !registerText.startsWith("%")) {
            return null;
        }
        String upper = registerText.toUpperCase();
        try {
            for (RegisterBankInfo bank : isa.registerBanks()) {
                if (bank.count() > 0 && !bank.forbidden() && upper.startsWith(bank.prefix())) {
                    int index = Integer.parseInt(upper.substring(bank.prefix().length()));
                    if (index >= 0 && index < bank.count()) {
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
