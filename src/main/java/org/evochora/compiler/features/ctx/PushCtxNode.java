package org.evochora.compiler.features.ctx;

import org.evochora.compiler.model.ast.AstNode;

/**
 * An AST node representing a .PUSH_CTX directive.
 * This is an internal directive injected by the preprocessor.
 *
 * <p>For .IMPORT directives, the target path identifies the imported module
 * and the alias chain identifies its placement in the import hierarchy.
 * For .SOURCE directives, the alias chain is null — the enclosing module
 * context is preserved.</p>
 */
public class PushCtxNode implements AstNode {
    private final String targetPath;
    private final String aliasChain;

    public PushCtxNode() {
        this(null, null);
    }

    public PushCtxNode(String targetPath) {
        this(targetPath, null);
    }

    public PushCtxNode(String targetPath, String aliasChain) {
        this.targetPath = targetPath;
        this.aliasChain = aliasChain;
    }

    /**
     * Returns the absolute path of the module being entered, or null for .SOURCE contexts.
     */
    public String targetPath() {
        return targetPath;
    }

    /**
     * Returns the import alias chain (e.g., "PRED.MATH") for .IMPORT placements,
     * or null for .SOURCE text inclusions.
     */
    public String aliasChain() {
        return aliasChain;
    }
}
