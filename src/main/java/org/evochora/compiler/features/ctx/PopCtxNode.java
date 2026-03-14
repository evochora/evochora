package org.evochora.compiler.features.ctx;

import org.evochora.compiler.model.ast.AstNode;

/**
 * An AST node representing a .POP_CTX directive.
 * This is an internal directive injected by the preprocessor.
 */
public class PopCtxNode implements AstNode {
    public PopCtxNode() {
        // No token associated with this internal node
    }
}
