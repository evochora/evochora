package org.evochora.compiler.frontend;

import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.parser.features.scope.ScopeNode;
import org.evochora.compiler.frontend.parser.ast.*;

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

        // Führe den Handler für den aktuellen Knoten aus, falls einer registriert ist
        handlers.getOrDefault(node.getClass(), n -> {}).accept(node);

        // Steige rekursiv in die Kinder des Knotens ab
        if (node instanceof LabelNode n) {
            walk(n.statement());
        } else if (node instanceof ProcedureNode n) {
            walk(n.body());
        } else if (node instanceof ScopeNode n) {
            walk(n.body());
        } else if (node instanceof InstructionNode n) {
            walk(n.arguments());
        }
        // Weitere Knotentypen mit Kindern hier hinzufügen...
    }
}