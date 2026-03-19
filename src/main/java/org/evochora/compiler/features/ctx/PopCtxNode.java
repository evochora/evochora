package org.evochora.compiler.features.ctx;

import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IModuleContextBoundary;

/**
 * An AST node representing a .POP_CTX directive.
 * This is an internal directive injected by the preprocessor.
 */
public class PopCtxNode implements AstNode, IModuleContextBoundary {
    public PopCtxNode() {
    }

    @Override
    public boolean isPush() {
        return false;
    }

    @Override
    public String aliasChain() {
        return null;
    }
}
