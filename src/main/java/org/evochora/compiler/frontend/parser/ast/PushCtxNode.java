package org.evochora.compiler.frontend.parser.ast;

/**
 * An AST node representing a .PUSH_CTX directive.
 * This is an internal directive injected by the preprocessor.
 */
public class PushCtxNode implements AstNode {
    public PushCtxNode() {
        // No token associated with this internal node
    }
}
