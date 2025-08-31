package org.evochora.compiler.frontend;

import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.ArrayList;
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

    /**
     * Transforms an AST by replacing nodes according to a replacement map.
     * This method is type-agnostic and doesn't require knowledge of specific node types.
     * @param node The root node to transform.
     * @param replacements A map from old nodes to their replacements.
     * @return The transformed node (may be the same or a new node).
     */
    public AstNode transform(AstNode node, Map<AstNode, AstNode> replacements) {
        if (node == null) {
            return null;
        }
        
        // Check if this node should be replaced
        if (replacements.containsKey(node)) {
            return replacements.get(node);
        }
        
        // Get the children and transform them
        List<AstNode> children = node.getChildren();
        List<AstNode> transformedChildren = new ArrayList<>();
        boolean childrenChanged = false;
        
        for (AstNode child : children) {
            AstNode transformedChild = transform(child, replacements);
            if (transformedChild != child) {
                childrenChanged = true;
            }
            transformedChildren.add(transformedChild);
        }
        
        // If children changed, we need to create a new node
        if (childrenChanged) {
            return reconstructNode(node, transformedChildren);
        }
        
        return node;
    }
    
    /**
     * Reconstructs a node with new children. This method is type-agnostic and doesn't
     * require knowledge of specific node types.
     * @param originalNode The original node to reconstruct
     * @param newChildren The new children for the node
     * @return A new node instance with the new children, or the original if reconstruction isn't possible
     */
    private AstNode reconstructNode(AstNode originalNode, List<AstNode> newChildren) {
        // Use the node's own reconstruction method - this is truly type-agnostic!
        return originalNode.reconstructWithChildren(newChildren);
    }
}