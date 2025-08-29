package org.evochora.compiler.frontend.parser.ast;

/**
 * An AST node representing a .POP_CTX directive.
 * This is an internal directive injected by the preprocessor.
 */
public class PopCtxNode implements AstNode {
    public PopCtxNode() {
        // No token associated with this internal node
    }
}
