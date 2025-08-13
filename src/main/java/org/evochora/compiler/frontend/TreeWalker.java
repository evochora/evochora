package org.evochora.compiler.frontend;

import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Eine generische Klasse zum Durchlaufen (Traversieren) eines Abstract Syntax Tree.
 * Anstatt des Visitor-Patterns verwendet dieser Walker ein Handler-basiertes System,
 * um die Kopplung zwischen den Compiler-Phasen und der AST-Struktur zu minimieren.
 */
public class TreeWalker {

    private final Map<Class<? extends AstNode>, Consumer<AstNode>> handlers;

    public TreeWalker(Map<Class<? extends AstNode>, Consumer<AstNode>> handlers) {
        this.handlers = handlers;
    }

    public void walk(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            walk(node);
        }
    }

    public void walk(AstNode node) {
        if (node == null) {
            return;
        }

        // Führe den Handler für den aktuellen Knoten aus, falls einer registriert ist.
        handlers.getOrDefault(node.getClass(), n -> {}).accept(node);

        // Steige rekursiv in ALLE Kinder ab, ohne ihren Typ zu kennen.
        for (AstNode child : node.getChildren()) {
            walk(child);
        }
    }
}