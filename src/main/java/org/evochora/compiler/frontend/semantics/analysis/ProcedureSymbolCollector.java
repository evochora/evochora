package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;

import java.util.Map;

/**
 * Collects procedure symbols during pass 1: defines the procedure symbol,
 * registers export metadata, enters a scope, and defines formal parameters.
 */
public class ProcedureSymbolCollector implements ISymbolCollector {

    private final Map<AstNode, SymbolTable.Scope> scopeMap;

    public ProcedureSymbolCollector(Map<AstNode, SymbolTable.Scope> scopeMap) {
        this.scopeMap = scopeMap;
    }

    @Override
    public void collect(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        ProcedureNode proc = (ProcedureNode) node;
        symbolTable.define(new Symbol(proc.name(), Symbol.Type.PROCEDURE, proc));
        symbolTable.registerProcedureMeta(proc.name(), proc.exported());

        SymbolTable.Scope newScope = symbolTable.enterScope();
        scopeMap.put(node, newScope);

        if (proc.parameters() != null) {
            for (Token p : proc.parameters()) {
                symbolTable.define(new Symbol(p, Symbol.Type.VARIABLE));
            }
        }
        if (proc.refParameters() != null) {
            for (Token p : proc.refParameters()) {
                symbolTable.define(new Symbol(p, Symbol.Type.VARIABLE));
            }
        }
        if (proc.valParameters() != null) {
            for (Token p : proc.valParameters()) {
                symbolTable.define(new Symbol(p, Symbol.Type.VARIABLE));
            }
        }
    }

    @Override
    public void collectAfterChildren(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        symbolTable.leaveScope();
    }
}
