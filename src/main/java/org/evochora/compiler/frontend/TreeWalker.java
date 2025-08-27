package org.evochora.compiler.frontend;

import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A generic class for traversing an Abstract Syntax Tree.
 * Instead of the Visitor pattern, this walker uses a handler-based system
 * to minimize coupling between compiler phases and the AST structure.
 */
public class TreeWalker {

    private final Map<Class<? extends AstNode>, Consumer<AstNode>> handlers;

    /**
     * Constructs a new TreeWalker.
     * @param handlers A map from AST node classes to their corresponding handlers.
     */
    public TreeWalker(Map<Class<? extends AstNode>, Consumer<AstNode>> handlers) {
        this.handlers = handlers;
    }

    /**
     * Walks a list of AST nodes.
     * @param nodes The list of nodes to walk.
     */
    public void walk(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            walk(node);
        }
    }

    /**
     * Walks a single AST node and its children recursively.
     * @param node The node to walk.
     */
    public void walk(AstNode node) {
        if (node == null) {
            return;
        }

        // Execute the handler for the current node if one is registered.
        handlers.getOrDefault(node.getClass(), n -> {}).accept(node);

        // Descend recursively into ALL children without knowing their type.
        for (AstNode child : node.getChildren()) {
            walk(child);
        }
    }
}