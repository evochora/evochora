package org.evochora.compiler.features.proc;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.analysis.ISymbolCollector;
import org.evochora.compiler.model.ast.AstNode;

import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Collects procedure symbols during pass 1: defines the procedure symbol,
 * registers export metadata, enters a scope, and defines formal parameters.
 */
public class ProcedureSymbolCollector implements ISymbolCollector {

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        ProcedureNode proc = (ProcedureNode) node;
        symbolTable.define(new Symbol(proc.name(), proc.sourceInfo(), Symbol.Type.PROCEDURE, proc, proc.exported()));

        String currentChain = symbolTable.getCurrentAliasChain();
        String scopeName = (currentChain != null && !currentChain.isEmpty())
            ? currentChain + "." + proc.name().toUpperCase()
            : proc.name().toUpperCase();
        SymbolTable.Scope newScope = symbolTable.enterScope(scopeName);
        symbolTable.registerNodeScope(node, newScope);

        if (proc.refParameters() != null) {
            for (ProcedureNode.ParamDecl p : proc.refParameters()) {
                symbolTable.define(new Symbol(p.name(), p.sourceInfo(), Symbol.Type.PARAMETER_DATA));
            }
        }
        if (proc.valParameters() != null) {
            for (ProcedureNode.ParamDecl p : proc.valParameters()) {
                symbolTable.define(new Symbol(p.name(), p.sourceInfo(), Symbol.Type.PARAMETER_DATA));
            }
        }
        if (proc.lrefParameters() != null) {
            for (ProcedureNode.ParamDecl p : proc.lrefParameters()) {
                symbolTable.define(new Symbol(p.name(), p.sourceInfo(), Symbol.Type.PARAMETER_LOCATION));
            }
        }
        if (proc.lvalParameters() != null) {
            for (ProcedureNode.ParamDecl p : proc.lvalParameters()) {
                symbolTable.define(new Symbol(p.name(), p.sourceInfo(), Symbol.Type.PARAMETER_LOCATION));
            }
        }
    }

    @Override
    public void collectAfterChildren(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        symbolTable.leaveScope();
    }
}
