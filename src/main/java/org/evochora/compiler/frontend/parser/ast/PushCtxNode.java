package org.evochora.compiler.frontend.parser.ast;

/**
 * An AST node representing a .PUSH_CTX directive.
 * This is an internal directive injected by the preprocessor.
 *
 * <p>For .IMPORT directives, the target path identifies the imported module
 * so the module context can switch immediately upon entering the block.
 * For .SOURCE directives, the target path is null — the enclosing module
 * context is preserved.</p>
 */
public class PushCtxNode implements AstNode {
    private final String targetPath;

    public PushCtxNode() {
        this(null);
    }

    public PushCtxNode(String targetPath) {
        this.targetPath = targetPath;
    }

    /**
     * Returns the absolute path of the module being entered, or null for .SOURCE contexts.
     */
    public String targetPath() {
        return targetPath;
    }
}
