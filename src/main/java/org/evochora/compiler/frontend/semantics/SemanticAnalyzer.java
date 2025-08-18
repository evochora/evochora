package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.features.def.DefineNode;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.parser.features.proc.PregNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.parser.features.scope.ScopeNode;
import org.evochora.compiler.frontend.parser.features.require.RequireNode;
import org.evochora.compiler.frontend.semantics.analysis.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SemanticAnalyzer {

    private final DiagnosticsEngine diagnostics;
    private final SymbolTable symbolTable;
    private final Map<Class<? extends AstNode>, IAnalysisHandler> handlers = new HashMap<>();

    public SemanticAnalyzer(DiagnosticsEngine diagnostics) {
        this.diagnostics = diagnostics;
        this.symbolTable = new SymbolTable(diagnostics);
        registerDefaultHandlers();
    }

    private void registerDefaultHandlers() {
        handlers.put(DefineNode.class, new DefineAnalysisHandler());
        handlers.put(LabelNode.class, new LabelAnalysisHandler());
        handlers.put(ScopeNode.class, new ScopeAnalysisHandler());
        handlers.put(ProcedureNode.class, new ProcedureAnalysisHandler());
        handlers.put(InstructionNode.class, new InstructionAnalysisHandler(symbolTable, diagnostics));
        handlers.put(PregNode.class, new PregAnalysisHandler());
    }

    public void analyze(List<AstNode> statements) {
        // First pass: collect labels (and respect scopes) to allow forward references
        collectLabels(statements);
        // Second pass: full analysis (types, arity, scope rules)
        traverseAndAnalyze(statements);
    }

    private void collectLabels(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            if (node == null) continue;

            // Prozedurnamen als globale Labels definieren, damit CALL <PROCNAME> aufgelöst werden kann
            if (node instanceof ProcedureNode proc) {
                // Registriere Metadaten (Export-Flag) für grenzüberschreitenden Zugriff
                symbolTable.registerProcedureMeta(proc.name(), proc.exported());
                // Registriere die Prozedur als eigenes Symbol (Datei-bezogen). Für CALL wird PROCEDURE als Label-äquivalent akzeptiert.
                symbolTable.define(new Symbol(proc.name(), Symbol.Type.PROCEDURE));
            }
            // Registriere .REQUIRE Aliasse pro Datei für spätere Namensraumauflösung
            if (node instanceof RequireNode req) {
                if (req.alias() != null && req.path() != null && req.path().value() instanceof String) {
                    String aliasU = req.alias().text().toUpperCase();
                    String file = req.alias().fileName();
                    String target = (String) req.path().value();
                    // Normalisiere Pfadtrennzeichen wie im Preprocessor (Lexer/Include nutzt absolute Pfade und speichert fileName)
                    String normalizedTarget = java.nio.file.Path.of(target).normalize().toString();
                    symbolTable.registerRequireAlias(file, aliasU, normalizedTarget);
                }
            }
            if (node instanceof ScopeNode || node instanceof ProcedureNode) {
                symbolTable.enterScope();
            }
            // Definiere formale Parameter im Prozedur-Scope, sodass sie in der Analyse als Register-Platzhalter gelten
            if (node instanceof ProcedureNode proc2) {
                if (proc2.parameters() != null) {
                    for (org.evochora.compiler.frontend.lexer.Token p : proc2.parameters()) {
                        symbolTable.define(new Symbol(p, Symbol.Type.VARIABLE));
                    }
                }
            }
            if (node instanceof org.evochora.compiler.frontend.parser.features.label.LabelNode lbl) {
                symbolTable.define(new Symbol(lbl.labelToken(), Symbol.Type.LABEL));
            }
            collectLabels(node.getChildren());
            if (node instanceof ScopeNode || node instanceof ProcedureNode) {
                symbolTable.leaveScope();
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