package org.evochora.compiler.frontend.parser.ast;

import java.util.Collections;
import java.util.List;

/**
 * The base interface for all nodes in the Abstract Syntax Tree (AST).
 */
public interface AstNode {
    /**
     * Returns a list of the direct child nodes.
     * This allows a generic TreeWalker to traverse the tree
     * without knowing the specific structure of each node.
     *
     * @return A list of child nodes. Returns an empty list if the node has no children.
     */
    default List<AstNode> getChildren() {
        return Collections.emptyList();
    }
    
    /**
     * Creates a new instance of this node with the given children.
     * This allows the TreeWalker to reconstruct nodes generically without
     * knowing their specific types.
     * 
     * @param newChildren The new children for this node
     * @return A new instance of this node with the new children, or this node if reconstruction isn't supported
     */
    default AstNode reconstructWithChildren(List<AstNode> newChildren) {
        // Default implementation: return this node unchanged
        // Subclasses should override this if they support reconstruction
        return this;
    }
}