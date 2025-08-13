package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.TreeWalker;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.parser.features.scope.ScopeNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Führt die semantische Analyse durch, indem er dem TreeWalker spezialisierte
 * Handler für die Analyse von AST-Knoten bereitstellt.
 */
public class SemanticAnalyzer {

    private final DiagnosticsEngine diagnostics;
    private final SymbolTable symbolTable;
    private final TreeWalker definitionCollector;
    // Hier kommt später ein zweiter TreeWalker für die eigentliche Analyse (Typ-Prüfung etc.)

    public SemanticAnalyzer(DiagnosticsEngine diagnostics) {
        this.diagnostics = diagnostics;
        this.symbolTable = new SymbolTable(diagnostics);

        // Konfiguriere die Handler für die erste Phase: Definitionen sammeln
        Map<Class<? extends AstNode>, Consumer<AstNode>> definitionHandlers = new HashMap<>();

        definitionHandlers.put(LabelNode.class, node -> {
            symbolTable.define(new Symbol(((LabelNode) node).labelToken(), Symbol.Type.LABEL));
        });

        // Handler für Scopes, der die Symboltabelle steuert
        // Anmerkung: Wir brauchen keine expliziten Handler mehr, um Scopes zu betreten/verlassen,
        // da der TreeWalker das jetzt selbst steuern kann. Dies ist eine Vereinfachung.
        // Stattdessen traversieren wir den Baum einfach.

        this.definitionCollector = new TreeWalker(definitionHandlers);
    }

    /**
     * Startet die Analyse für eine Liste von AST-Knoten.
     */
    public void analyze(List<AstNode> statements) {
        // Phase 1: Alle Definitionen sammeln.
        // Wir erstellen einen speziellen Walker, der die Scopes korrekt verwaltet.
        collectDefinitions(statements);

        // TODO: Phase 2: Hier kommt ein zweiter TreeWalker für die eigentliche Analyse
        // (z.B. Typ-Prüfung, Auflösung von Variablen etc.)
    }

    private void collectDefinitions(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            if (node == null) continue;

            // Handler für den aktuellen Knoten ausführen
            if (node instanceof LabelNode) {
                symbolTable.define(new Symbol(((LabelNode) node).labelToken(), Symbol.Type.LABEL));
            }

            // Scopes betreten/verlassen und rekursiv absteigen
            if (node instanceof ProcedureNode || node instanceof ScopeNode) {
                symbolTable.enterScope();
                collectDefinitions(node.getChildren());
                symbolTable.leaveScope();
            } else {
                collectDefinitions(node.getChildren());
            }
        }
    }
}