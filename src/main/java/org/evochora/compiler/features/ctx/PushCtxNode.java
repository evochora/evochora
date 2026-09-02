package org.evochora.compiler.features.ctx;

import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IModuleContextBoundary;

/**
 * An AST node representing a .PUSH_CTX directive.
 * This is an internal directive injected by the preprocessor.
 *
 * <p>For .IMPORT directives, the target path identifies the imported module
 * and the alias chain identifies its placement in the import hierarchy.
 * For .SOURCE directives, the alias chain is null — the enclosing module
 * context is preserved.</p>
 */
public class PushCtxNode implements AstNode, IModuleContextBoundary {
    private final String targetPath;
    private final String aliasChain;

    /**
     * Creates a boundary for a module placement.
     *
     * @param targetPath The absolute path of the module being entered, or null for .SOURCE contexts.
     * @param aliasChain The import alias chain the traversal switches to (e.g., "PRED.MATH"), or null
     *                   to keep the enclosing module context.
     */
    public PushCtxNode(String targetPath, String aliasChain) {
        this.targetPath = targetPath;
        this.aliasChain = aliasChain;
    }

    /**
     * Returns the absolute path of the module being entered, or null for .SOURCE contexts.
     *
     * @return The absolute path of the entered module, or {@code null} where no separate module is
     *         entered, as for .SOURCE text inclusions.
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

    @Override
    public boolean isPush() {
        return true;
    }
}
