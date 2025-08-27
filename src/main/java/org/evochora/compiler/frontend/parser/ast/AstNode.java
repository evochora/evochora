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
}