package org.evochora.compiler.features.reg;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.ISymbolCollector;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.isa.IInstructionSet.RegisterBankInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Collects the alias of a {@code .REG} during pass 1: defines it on the level it stands in, as
 * an alias of the register's kind, data or location, so that the alias can be used anywhere on
 * that level, above or below the line. The register itself was checked when it was lexed and
 * parsed. An alias defined twice on one level is reported at the second definition, naming the
 * first.
 */
public class RegSymbolCollector implements ISymbolCollector {

    private final IInstructionSet isa;

    /**
     * Creates the collector for an instruction set.
     *
     * @param isa The instruction set, which tells a location register from a data register.
     */
    public RegSymbolCollector(IInstructionSet isa) {
        this.isa = isa;
    }

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        RegNode regNode = (RegNode) node;
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
