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
 *
 * @param targetPath The absolute path of the module being entered, or {@code null} where no
 *                   separate module is entered, as for .SOURCE text inclusions.
 * @param aliasChain The import alias chain the traversal switches to (e.g., "PRED.MATH"), or
 *                   {@code null} to keep the enclosing module context.
 */
public record PushCtxNode(String targetPath, String aliasChain) implements AstNode, IModuleContextBoundary {

    @Override
    public boolean isPush() {
        return true;
    }
}
