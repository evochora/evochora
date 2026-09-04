package org.evochora.compiler.features.reg;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.isa.IInstructionSet.RegisterBankInfo;

/**
 * Handles the semantic analysis of .REG directives.
 * Its sole responsibility is to define register aliases in the symbol table; the register
 * itself was checked when it was lexed and parsed.
 */
public class RegAnalysisHandler implements IAnalysisHandler {

    private final IInstructionSet isa;

    /**
     * Creates the handler for an instruction set.
     *
     * @param isa The instruction set, which tells a location register from a data register.
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
        RegisterBankInfo bank = isa.parseRegister(regNode.register())
                .orElseThrow(() -> new IllegalStateException("Register the instruction set cannot read: " + regNode.register()))
                .bank();
        Symbol.Type aliasType = bank.location() ? Symbol.Type.REGISTER_ALIAS_LOCATION : Symbol.Type.REGISTER_ALIAS_DATA;
        symbolTable.define(new Symbol(regNode.alias(), regNode.sourceInfo(), aliasType, regNode))
                .ifPresent(existing -> diagnostics.reportError(
                        "Cannot define register alias '" + regNode.alias() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                        regNode.sourceInfo().fileName(), regNode.sourceInfo().lineNumber()));
    }
}
