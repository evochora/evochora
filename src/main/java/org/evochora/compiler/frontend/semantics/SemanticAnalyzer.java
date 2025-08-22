package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.features.def.DefineNode;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.parser.features.require.RequireNode;
import org.evochora.compiler.frontend.parser.features.scope.ScopeNode;
import org.evochora.compiler.frontend.semantics.analysis.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path; // NEUER IMPORT

public class SemanticAnalyzer {

    private final DiagnosticsEngine diagnostics;
    private final SymbolTable symbolTable;
    private final Map<Class<? extends AstNode>, IAnalysisHandler> handlers = new HashMap<>();
    private final Map<AstNode, SymbolTable.Scope> scopeMap = new HashMap<>();

    public SemanticAnalyzer(DiagnosticsEngine diagnostics, SymbolTable symbolTable) {
        this.diagnostics = diagnostics;
        this.symbolTable = symbolTable;
        registerDefaultHandlers();
    }

    private void registerDefaultHandlers() {
        handlers.put(DefineNode.class, new DefineAnalysisHandler());
        handlers.put(LabelNode.class, new LabelAnalysisHandler());
        handlers.put(ScopeNode.class, new ScopeAnalysisHandler(this.scopeMap));
        handlers.put(ProcedureNode.class, new ProcedureAnalysisHandler(this.scopeMap));
        handlers.put(InstructionNode.class, new InstructionAnalysisHandler(symbolTable, diagnostics));
    }

    public void analyze(List<AstNode> statements) {
        collectLabels(statements);
        symbolTable.resetScope();
        traverseAndAnalyze(statements);
    }

    private void collectLabels(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            if (node == null) continue;

            if (node instanceof ProcedureNode proc) {
                symbolTable.define(new Symbol(proc.name(), Symbol.Type.PROCEDURE));
                symbolTable.registerProcedureMeta(proc.name(), proc.exported());
            }
            if (node instanceof RequireNode req) {
                if (req.alias() != null && req.path() != null && req.path().value() instanceof String) {
                    String aliasU = req.alias().text().toUpperCase();
                    String file = req.alias().fileName();
                    String target = (String) req.path().value();

                    // KORREKTUR: Sicherstellen, dass der Pfad normalisiert ist
                    String normalizedTarget = Path.of(target).normalize().toString().replace('\\', '/');
                    symbolTable.registerRequireAlias(file, aliasU, normalizedTarget);
                }
            }
            if (node instanceof LabelNode lbl) {
                symbolTable.define(new Symbol(lbl.labelToken(), Symbol.Type.LABEL));
            }

            if (node instanceof ScopeNode || node instanceof ProcedureNode) {
                SymbolTable.Scope newScope = symbolTable.enterScope();
                scopeMap.put(node, newScope);

                if (node instanceof ProcedureNode proc2 && proc2.parameters() != null) {
                    for (org.evochora.compiler.frontend.lexer.Token p : proc2.parameters()) {
                        symbolTable.define(new Symbol(p, Symbol.Type.VARIABLE));
                    }
                }

                collectLabels(node.getChildren());
                symbolTable.leaveScope();
            } else {
                collectLabels(node.getChildren());
            }
        }
    }

    private void traverseAndAnalyze(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            if (node == null) continue;
            IAnalysisHandler handler = handlers.get(node.getClass());
            if (handler != null) {
                handler.analyze(node, symbolTable, diagnostics);
            }
            traverseAndAnalyze(node.getChildren());
            if (handler instanceof ScopeAnalysisHandler scopeHandler) {
                scopeHandler.afterChildren(symbolTable);
            }
        }
    }
}