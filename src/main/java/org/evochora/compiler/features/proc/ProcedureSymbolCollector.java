package org.evochora.compiler.features.proc;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.ISymbolCollector;
import org.evochora.compiler.api.SourceInfo;
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
        symbolTable.define(new Symbol(proc.name(), proc.sourceInfo(), Symbol.Type.PROCEDURE, proc, proc.exported()))
                .ifPresent(existing -> diagnostics.reportError(
                        "Cannot define procedure '" + proc.name() + "': the name is already used at " + SourceInfo.position(existing.sourceInfo()) + ".",
                        proc.sourceInfo().fileName(), proc.sourceInfo().lineNumber()));

        String currentChain = symbolTable.getCurrentAliasChain();
        String scopeName = (currentChain != null && !currentChain.isEmpty())
            ? currentChain + "." + proc.name().toUpperCase()
            : proc.name().toUpperCase();
        SymbolTable.Scope newScope = symbolTable.enterScope(scopeName);
        symbolTable.registerNodeScope(node, newScope);

        int dataIndex = 0;
        if (proc.refParameters() != null) {
            for (ProcedureNode.ParamDecl p : proc.refParameters()) {
                declareParameter(proc, symbolTable, diagnostics, new Symbol(p.name(), p.sourceInfo(), Symbol.Type.PARAMETER_DATA,
                        new ParameterBinding("%FDR" + dataIndex++)));
            }
        }
        if (proc.valParameters() != null) {
            for (ProcedureNode.ParamDecl p : proc.valParameters()) {
                declareParameter(proc, symbolTable, diagnostics, new Symbol(p.name(), p.sourceInfo(), Symbol.Type.PARAMETER_DATA,
                        new ParameterBinding("%FDR" + dataIndex++)));
            }
        }
        int locationIndex = 0;
        if (proc.lrefParameters() != null) {
            for (ProcedureNode.ParamDecl p : proc.lrefParameters()) {
                declareParameter(proc, symbolTable, diagnostics, new Symbol(p.name(), p.sourceInfo(), Symbol.Type.PARAMETER_LOCATION,
                        new ParameterBinding("%FLR" + locationIndex++)));
            }
        }
        if (proc.lvalParameters() != null) {
            for (ProcedureNode.ParamDecl p : proc.lvalParameters()) {
                declareParameter(proc, symbolTable, diagnostics, new Symbol(p.name(), p.sourceInfo(), Symbol.Type.PARAMETER_LOCATION,
                        new ParameterBinding("%FLR" + locationIndex++)));
            }
        }
    }

    @Override
    public void collectAfterChildren(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        symbolTable.leaveScope();
    }

    /**
     * Declares one formal parameter; a name declared twice in the same procedure is reported.
     */
    private static void declareParameter(ProcedureNode proc, SymbolTable symbolTable, DiagnosticsEngine diagnostics, Symbol parameter) {
        symbolTable.define(parameter).ifPresent(existing -> diagnostics.reportError(
                "Cannot declare parameter '" + parameter.name() + "' twice in procedure '" + proc.name() + "'.",
                parameter.sourceInfo().fileName(), parameter.sourceInfo().lineNumber()));
    }
}
